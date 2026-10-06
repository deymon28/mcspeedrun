package org.speedrun.speedrun.webconfig;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONArray;
import org.json.JSONObject;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.config.ConfigFileStore;
import org.speedrun.speedrun.config.LanguageFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Token-protected HTTP server for the browser configuration editor.
 *
 * <p>The server owns transport only: authentication, request limits, static files, and
 * security headers. Configuration logic lives in {@link WebConfigSession} and is always
 * executed on the server thread. The server never runs console commands.</p>
 *
 * <p>Security model: the server binds to 127.0.0.1 by default; every API call needs a random
 * per-start token in the {@code X-Speedrun-Token} header. The access link carries the token in
 * the URL fragment, which browsers never send to servers, so it does not end up in request
 * logs or referrers. Host headers are checked against the bind address to block DNS
 * rebinding, and repeated failed authentication is throttled.</p>
 */
public class WebConfigManager {
    private static final String RESOURCE_ROOT = "web-config/";
    /** Static files served by the editor. Anything else is 404. */
    private static final Map<String, String> STATIC_FILES = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/editor-state.js", "editor-state.js",
            "/styles.css", "styles.css");
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final long SYNC_TIMEOUT_SECONDS = 10;
    static final int MAX_FAILED_ATTEMPTS = 20;
    private static final long FAILURE_WINDOW_MILLIS = 60_000;

    private final Speedrun plugin;
    private final WebConfigSession session;
    private HttpServer server;
    private ExecutorService executor;
    private volatile String token;
    private String bindAddress;
    private int port;
    private final Object failureLock = new Object();
    private long failureWindowStart;
    private int failedAttempts;
    private boolean throttleReported;

    public WebConfigManager(Speedrun plugin) {
        this.plugin = plugin;
        this.session = new WebConfigSession(new PluginAccess(plugin));
    }

    public synchronized void start() {
        FileConfiguration config = plugin.getConfigManager().getRawConfig();
        if (!config.getBoolean("web-config.enabled", true)) {
            plugin.getLogger().info("Web config editor is disabled in config.yml.");
            return;
        }
        if (server != null) {
            return;
        }

        bindAddress = config.getString("web-config.bind", "127.0.0.1").trim();
        port = config.getInt("web-config.port", 8765);
        token = newToken();
        resetFailures();

        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(bindAddress), port);
            server = HttpServer.create(address, 16);
            server.createContext("/", closedHandler(null, this::handleStatic));
            server.createContext("/api/config", closedHandler("/api/config", this::handleConfig));
            server.createContext("/api/config/validate", closedHandler("/api/config/validate", exchange -> handleMutation(exchange, WebConfigSession.Mode.VALIDATE)));
            server.createContext("/api/config/save", closedHandler("/api/config/save", exchange -> handleMutation(exchange, WebConfigSession.Mode.SAVE)));
            server.createContext("/api/config/apply", closedHandler("/api/config/apply", exchange -> handleMutation(exchange, WebConfigSession.Mode.APPLY)));
            server.createContext("/api/config/save-apply", closedHandler("/api/config/save-apply", exchange -> handleMutation(exchange, WebConfigSession.Mode.SAVE_APPLY)));
            server.createContext("/api/status", closedHandler("/api/status", this::handleStatus));
            // A small pool so one slow connection cannot block the editor for everyone else.
            executor = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "Speedrun-WebConfig");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();

            if (!isLoopbackBind(bindAddress)) {
                plugin.getLogger().warning("Web config editor is bound to " + bindAddress
                        + ". It is reachable from other machines; anyone with the access link can change the configuration.");
            }
            plugin.getLogger().info("Web config editor is ready on " + displayHost() + ":" + port
                    + ". Administrators can open it with /run webconfig.");
        } catch (IOException | RuntimeException ex) {
            server = null;
            token = null;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            plugin.getLogger().severe("Failed to start the web config editor on " + bindAddress + ":" + port + ": "
                    + ex.getClass().getSimpleName() + " (" + ex.getMessage() + "). Change web-config.port if the port is in use.");
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
            plugin.getLogger().info("Web config editor stopped.");
        }
    }

    /** Restarts the server with a new access token; old links stop working. */
    public synchronized void restart() {
        stop();
        session.markRecovered();
        start();
    }

    public boolean isRunning() {
        return server != null;
    }

    /** The private access link. The token is in the fragment so browsers never send it in requests. */
    public String getUrl() {
        if (server == null || token == null) {
            return null;
        }
        return "http://" + displayHost() + ":" + port + "/#token=" + token;
    }

    private String displayHost() {
        if (bindAddress.equals("0.0.0.0") || bindAddress.equals("::") || bindAddress.isEmpty()) {
            return "127.0.0.1";
        }
        return bindAddress.contains(":") && !bindAddress.startsWith("[") ? "[" + bindAddress + "]" : bindAddress;
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET") || !requireToken(exchange)) {
            return;
        }
        sendJson(exchange, 200, callSync(session::snapshot));
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET") || !requireToken(exchange)) {
            return;
        }
        sendJson(exchange, 200, callSync(() -> new JSONObject().put("ok", true).put("metadata", new PluginAccess(plugin).metadata())));
    }

    private void handleMutation(HttpExchange exchange, WebConfigSession.Mode mode) throws IOException {
        if (!requireMethod(exchange, "POST") || !requireToken(exchange)) {
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            sendJson(exchange, 415, error("unsupported-media-type", "Requests must use application/json."));
            return;
        }
        String rawBody;
        try {
            rawBody = new String(readRequestBody(exchange.getRequestBody()), StandardCharsets.UTF_8);
        } catch (RequestBodyTooLargeException ex) {
            sendJson(exchange, 413, error("too-large", "Request body exceeds the 1 MiB limit."));
            return;
        }
        JSONObject body;
        try {
            body = rawBody.isBlank() ? new JSONObject() : new JSONObject(rawBody);
        } catch (RuntimeException ex) {
            sendJson(exchange, 400, error("invalid-json", "Invalid JSON body."));
            return;
        }
        JSONObject changes = body.optJSONObject("changes");
        if (changes == null) {
            changes = new JSONObject();
        }
        String revision = body.optString("revision", null);

        JSONObject finalChanges = changes;
        CompletableFuture<WebConfigSession.Reply> reply = new CompletableFuture<>();
        JSONObject failure = callSync(() -> {
            WebConfigSession.Reply result = session.mutate(finalChanges, revision, mode);
            reply.complete(result);
            return result.body();
        });
        WebConfigSession.Reply result = reply.getNow(null);
        if (result == null) {
            sendJson(exchange, 500, failure);
        } else {
            if (result.status() == 200 && (mode == WebConfigSession.Mode.SAVE || mode == WebConfigSession.Mode.SAVE_APPLY)) {
                plugin.getLogger().info("Configuration saved from the web editor (" + mode.name().toLowerCase(Locale.ROOT) + ").");
            }
            sendJson(exchange, result.status(), result.body());
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        if (!hostAllowed(exchange)) {
            exchange.sendResponseHeaders(421, -1);
            return;
        }
        String file = staticFile(exchange.getRequestURI().getPath());
        if (file == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        try (InputStream input = plugin.getResource(RESOURCE_ROOT + file)) {
            if (input == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = input.readAllBytes();
            applySecurityHeaders(exchange.getResponseHeaders());
            exchange.getResponseHeaders().set("Content-Type", contentType(file));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    /** Maps a request path to a bundled file, or null. Translation files are limited to bundled languages. */
    static String staticFile(String path) {
        String file = STATIC_FILES.get(path);
        if (file != null) {
            return file;
        }
        if (path != null && path.startsWith("/i18n/") && path.endsWith(".json")) {
            String code = path.substring("/i18n/".length(), path.length() - ".json".length());
            if (LanguageFiles.SUPPORTED.contains(code)) {
                return "i18n/" + code + ".json";
            }
        }
        return null;
    }

    static void applySecurityHeaders(Headers headers) {
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            exchange.sendResponseHeaders(405, -1);
            return false;
        }
        return true;
    }

    private boolean requireToken(HttpExchange exchange) throws IOException {
        if (!hostAllowed(exchange)) {
            sendJson(exchange, 421, error("host", "Unexpected Host header."));
            return false;
        }
        String provided = exchange.getRequestHeaders().getFirst("X-Speedrun-Token");
        if (tokensMatch(token, provided)) {
            return true;
        }
        // Requests without a key (for example from other web pages) never count, so they cannot lock the owner out.
        if (provided != null && !provided.isBlank()) {
            if (isThrottled()) {
                sendJson(exchange, 429, error("throttled", "Too many failed attempts. Wait a minute and open a fresh link with /run webconfig."));
                return false;
            }
            recordFailure();
        }
        sendJson(exchange, 403, error("token", "Invalid or missing access token. Open a fresh link with /run webconfig."));
        return false;
    }

    /** Blocks DNS rebinding: for loopback binds only loopback host names are accepted. */
    private boolean hostAllowed(HttpExchange exchange) {
        return hostAllowed(exchange.getRequestHeaders().getFirst("Host"), bindAddress, port);
    }

    static boolean hostAllowed(String hostHeader, String bindAddress, int port) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        String expectedPort = ":" + port;
        if (host.endsWith(expectedPort)) {
            host = host.substring(0, host.length() - expectedPort.length());
        } else if (host.matches(".*:\\d+") && !host.endsWith("]")) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        boolean loopbackHost = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
        if (isLoopbackBind(bindAddress)) {
            return loopbackHost;
        }
        // Deliberately exposed editors accept any host name; the token still protects every API call.
        return true;
    }

    private boolean isThrottled() {
        synchronized (failureLock) {
            if (System.currentTimeMillis() - failureWindowStart > FAILURE_WINDOW_MILLIS) {
                failureWindowStart = System.currentTimeMillis();
                failedAttempts = 0;
                throttleReported = false;
            }
            return failedAttempts >= MAX_FAILED_ATTEMPTS;
        }
    }

    private void recordFailure() {
        synchronized (failureLock) {
            failedAttempts++;
            if (failedAttempts >= MAX_FAILED_ATTEMPTS && !throttleReported) {
                throttleReported = true;
                plugin.getLogger().warning("Web config editor: too many requests with an invalid token; access is paused for a minute.");
            }
        }
    }

    private void resetFailures() {
        synchronized (failureLock) {
            failureWindowStart = System.currentTimeMillis();
            failedAttempts = 0;
            throttleReported = false;
        }
    }

    private JSONObject callSync(Callable<JSONObject> callable) {
        if (Bukkit.isPrimaryThread()) {
            return callUnchecked(callable);
        }

        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        BukkitTask task;
        try {
            task = Bukkit.getScheduler().runTask(plugin, () -> {
                if (future.isDone()) {
                    return;
                }
                try {
                    future.complete(callable.call());
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }
            });
        } catch (RuntimeException ex) {
            return requestFailure(ex);
        }

        try {
            return future.get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            task.cancel();
            return error("interrupted", "Request interrupted. Reload the editor before retrying.");
        } catch (TimeoutException ex) {
            future.cancel(false);
            task.cancel();
            return error("timeout", "The server did not answer in time. Reload the editor to check whether the change completed.");
        } catch (ExecutionException ex) {
            return requestFailure(ex.getCause());
        }
    }

    private JSONObject callUnchecked(Callable<JSONObject> callable) {
        try {
            return callable.call();
        } catch (Exception ex) {
            return requestFailure(ex);
        }
    }

    private JSONObject requestFailure(Throwable failure) {
        plugin.getLogger().severe("Web config request failed (" + failure.getClass().getSimpleName() + ").");
        return error("failed", "Request failed. Check the server console.");
    }

    private static JSONObject error(String code, String message) {
        return new JSONObject().put("ok", false).put("errorCode", code).put("error", message);
    }

    static byte[] readRequestBody(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_REQUEST_BODY_BYTES) {
            throw new RequestBodyTooLargeException();
        }
        return body;
    }

    static boolean tokensMatch(String expected, String provided) {
        return expected != null && !expected.isBlank() && provided != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    static HttpHandler closedHandler(String exactPath, HttpHandler handler) {
        return exchange -> {
            try {
                if (exactPath != null && !exactPath.equals(exchange.getRequestURI().getRawPath())) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        };
    }

    static final class RequestBodyTooLargeException extends IOException {
    }

    private void sendJson(HttpExchange exchange, int status, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        applySecurityHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static boolean isLoopbackBind(String rawBind) {
        String normalized = rawBind == null ? "" : rawBind.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || normalized.startsWith("127.") || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (resource.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    /** Bridges the session to the live plugin. Runs on the server thread. */
    private record PluginAccess(Speedrun plugin) implements WebConfigSession.Access {
        @Override
        public FileConfiguration active() {
            return plugin.getConfigManager().getRawConfig();
        }

        @Override
        public YamlConfiguration saved() throws IOException, InvalidConfigurationException {
            if (!Files.exists(plugin.getConfigFile().toPath())) {
                return ConfigFileStore.copy(active());
            }
            return ConfigFileStore.read(plugin.getConfigFile().toPath());
        }

        @Override
        public YamlConfiguration defaults() {
            return plugin.getConfigManager().getBundledDefaults();
        }

        @Override
        public void save(FileConfiguration candidate) throws IOException {
            ConfigFileStore.writeAtomically(plugin.getConfigFile().toPath(), candidate);
        }

        @Override
        public void replaceActive(FileConfiguration candidate) {
            plugin.getConfigManager().replaceRuntimeConfig(candidate);
        }

        @Override
        public WebConfigApplyResult apply(JSONObject changes) {
            return new RuntimeConfigApplier(plugin).apply(changes);
        }

        @Override
        public boolean runActive() {
            return plugin.getGameManager().isRunning();
        }

        @Override
        public boolean consoleRewardCommandsAllowed() {
            return plugin.getConfigManager().areConsoleRewardCommandsEditableOnWeb();
        }

        @Override
        public JSONObject migration() {
            return plugin.getConfigManager().getMigrationReport() == null
                    ? new JSONObject() : plugin.getConfigManager().getMigrationReport().toJson();
        }

        @Override
        public JSONObject metadata() {
            JSONObject run = new JSONObject()
                    .put("running", plugin.getGameManager().isRunning())
                    .put("paused", plugin.getGameManager().isPaused())
                    .put("time", plugin.getGameManager().getFormattedTime())
                    .put("finished", plugin.getGameManager().isDragonKilledEnd())
                    .put("stage", plugin.getTaskManager().getCurrentStageName().orElse(null))
                    .put("players", Bukkit.getOnlinePlayers().size());
            return new JSONObject()
                    .put("pluginVersion", plugin.getDescription().getVersion())
                    .put("gamemode", plugin.getConfigManager().getGameMode().name())
                    .put("casualActive", plugin.getCasualGameModeManager() != null && plugin.getCasualGameModeManager().isCasualModeActive())
                    .put("languages", new JSONArray(LanguageFiles.SUPPORTED))
                    .put("platform", plugin.getPlatform() == null ? new JSONObject() : new JSONObject(plugin.getPlatform().diagnostics()))
                    .put("run", run);
        }
    }
}
