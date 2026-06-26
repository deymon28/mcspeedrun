package org.speedrun.speedrun.webconfig;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.json.JSONObject;
import org.speedrun.speedrun.Speedrun;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local-only HTTP server for browser-based plugin configuration.
 */
public class WebConfigManager {
    private static final String RESOURCE_ROOT = "web-config/";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Speedrun plugin;
    private HttpServer server;
    private ExecutorService executor;
    private String token;
    private String bindAddress;
    private int port;

    public WebConfigManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() {
        if (!plugin.getConfigManager().getRawConfig().getBoolean("web-config.enabled", true)) {
            plugin.getLogger().info("Web config is disabled in config.yml.");
            return;
        }
        if (server != null) {
            return;
        }

        bindAddress = plugin.getConfigManager().getRawConfig().getString("web-config.bind", "127.0.0.1");
        port = plugin.getConfigManager().getRawConfig().getInt("web-config.port", 8765);
        token = newToken();

        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(bindAddress), port);
            server = HttpServer.create(address, 0);
            server.createContext("/", this::handleStatic);
            server.createContext("/api/config", this::handleConfig);
            server.createContext("/api/config/validate", exchange -> handleMutation(exchange, MutationMode.VALIDATE));
            server.createContext("/api/config/save", exchange -> handleMutation(exchange, MutationMode.SAVE));
            server.createContext("/api/config/apply", exchange -> handleMutation(exchange, MutationMode.APPLY));
            server.createContext("/api/config/save-apply", exchange -> handleMutation(exchange, MutationMode.SAVE_APPLY));
            server.createContext("/api/status", this::handleStatus);
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Speedrun-WebConfig");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();

            if (!isLoopbackBind(bindAddress)) {
                plugin.getLogger().warning("Web config is bound to " + bindAddress
                        + ". This can expose configuration controls outside this machine.");
            }
            plugin.getLogger().info("Speedrun web config: " + getUrl());
        } catch (IOException ex) {
            server = null;
            plugin.getLogger().severe("Failed to start web config server on " + bindAddress + ":" + port + ": " + ex.getMessage());
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            token = null;
            plugin.getLogger().info("Speedrun web config server stopped.");
        }
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public boolean isRunning() {
        return server != null;
    }

    public String getUrl() {
        if (server == null || token == null) {
            return "Web config is not running.";
        }
        return "http://" + bindAddress + ":" + port + "/?token=" + token;
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET") || !requireToken(exchange)) {
            return;
        }
        sendJson(exchange, callSync(() -> new JSONObject()
                .put("schema", WebConfigSchema.fieldsJson())
                .put("values", WebConfigSchema.currentValues(plugin.getConfigManager().getRawConfig()))));
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET") || !requireToken(exchange)) {
            return;
        }
        sendJson(exchange, callSync(() -> new JSONObject()
                .put("running", plugin.getGameManager().isRunning())
                .put("paused", plugin.getGameManager().isPaused())
                .put("gamemode", plugin.getConfigManager().getGameMode().name())
                .put("casualActive", plugin.getCasualGameModeManager() != null && plugin.getCasualGameModeManager().isCasualModeActive())
                .put("hardcore", plugin.getConfigManager().isHardcoreModeEnabled())
                .put("traceEnabled", plugin.getConfigManager().isTraceEnabled())
                .put("webConfigRunning", isRunning())));
    }

    private void handleMutation(HttpExchange exchange, MutationMode mode) throws IOException {
        if (!requireMethod(exchange, "POST") || !requireToken(exchange)) {
            return;
        }
        String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JSONObject body;
        try {
            body = rawBody.isBlank() ? new JSONObject() : new JSONObject(rawBody);
        } catch (RuntimeException ex) {
            sendJson(exchange, 400, new JSONObject()
                    .put("ok", false)
                    .put("error", "Invalid JSON body."));
            return;
        }
        JSONObject patch = body.optJSONObject("changes");
        if (patch == null) {
            patch = body;
        }

        JSONObject finalPatch = patch;
        sendJson(exchange, callSync(() -> mutate(finalPatch, mode)));
    }

    private JSONObject mutate(JSONObject patch, MutationMode mode) throws Exception {
        WebConfigValidationResult validation = WebConfigSchema.validatePatch(patch);
        if (!validation.valid() || mode == MutationMode.VALIDATE) {
            return new JSONObject()
                    .put("ok", validation.valid())
                    .put("validation", validation.toJson());
        }

        FileConfiguration previousConfig = plugin.getConfigManager().getRawConfig();
        YamlConfiguration stagedConfig = WebConfigSchema.patchedCopy(previousConfig, patch);
        plugin.getConfigManager().replaceRuntimeConfig(stagedConfig);

        try {
            WebConfigApplyResult applyResult = null;
            if (mode == MutationMode.APPLY || mode == MutationMode.SAVE_APPLY) {
                applyResult = new RuntimeConfigApplier(plugin).apply(patch);
            }

            if (mode == MutationMode.SAVE || mode == MutationMode.SAVE_APPLY) {
                stagedConfig.save(plugin.getConfigFile());
            }

            return new JSONObject()
                    .put("ok", true)
                    .put("validation", validation.toJson())
                    .put("apply", applyResult == null ? JSONObject.NULL : applyResult.toJson())
                    .put("values", WebConfigSchema.currentValues(plugin.getConfigManager().getRawConfig()));
        } catch (Exception ex) {
            plugin.getConfigManager().replaceRuntimeConfig(previousConfig);
            throw ex;
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String resource = switch (path) {
            case "/", "" -> RESOURCE_ROOT + "index.html";
            case "/app.js" -> RESOURCE_ROOT + "app.js";
            case "/styles.css" -> RESOURCE_ROOT + "styles.css";
            default -> RESOURCE_ROOT + path.substring(1);
        };

        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(resource));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return false;
        }
        return true;
    }

    private boolean requireToken(HttpExchange exchange) throws IOException {
        String provided = exchange.getRequestHeaders().getFirst("X-Speedrun-Token");
        if (provided == null || provided.isBlank()) {
            provided = query(exchange).get("token");
        }
        if (!Objects.equals(token, provided)) {
            sendJson(exchange, 403, new JSONObject()
                    .put("ok", false)
                    .put("error", "Invalid or missing web config token."));
            return false;
        }
        return true;
    }

    private JSONObject callSync(Callable<JSONObject> callable) {
        if (Bukkit.isPrimaryThread()) {
            return callUnchecked(callable);
        }

        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(callable.call());
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });

        try {
            return future.join();
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Web config request failed: " + ex.getMessage());
            return new JSONObject()
                    .put("ok", false)
                    .put("error", "Request failed: " + ex.getMessage());
        }
    }

    private JSONObject callUnchecked(Callable<JSONObject> callable) {
        try {
            return callable.call();
        } catch (Exception ex) {
            plugin.getLogger().severe("Web config request failed: " + ex.getMessage());
            return new JSONObject()
                    .put("ok", false)
                    .put("error", "Request failed: " + ex.getMessage());
        }
    }

    private void sendJson(HttpExchange exchange, JSONObject json) throws IOException {
        sendJson(exchange, 200, json);
    }

    private void sendJson(HttpExchange exchange, int status, JSONObject json) throws IOException {
        byte[] bytes = json.toString(2).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new java.util.HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private boolean isLoopbackBind(String rawBind) {
        String normalized = rawBind.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String contentType(String resource) {
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private enum MutationMode {
        VALIDATE,
        SAVE,
        APPLY,
        SAVE_APPLY
    }
}
