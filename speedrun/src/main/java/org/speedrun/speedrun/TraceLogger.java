package org.speedrun.speedrun;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Optional JSONL diagnostics for runtime behavior that is difficult to infer
 * from normal server logs, such as compass metadata and background scans.
 */
public class TraceLogger {
    private final Speedrun plugin;
    private PrintWriter writer;
    private boolean active;
    private boolean mirrorToConsole;
    private Set<String> categories = Set.of();

    public TraceLogger(Speedrun plugin) {
        this.plugin = plugin;
    }

    public synchronized void reload() {
        close();
        active = plugin.getConfigManager().isTraceEnabled();
        mirrorToConsole = plugin.getConfigManager().isTraceMirrorToConsoleEnabled();
        categories = normalizeCategories(plugin.getConfigManager().getTraceCategories());
        if (!active) {
            return;
        }

        try {
            File traceDir = new File(plugin.getDataFolder(), "traces");
            if (!traceDir.exists() && !traceDir.mkdirs()) {
                plugin.getLogger().severe("Could not create trace diagnostics directory!");
                active = false;
                return;
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File traceFile = new File(traceDir, "trace-" + timestamp + ".jsonl");
            writer = new PrintWriter(new FileWriter(traceFile, true));
            trace("diagnostics", "trace_started",
                    "file", traceFile.getAbsolutePath(),
                    "categories", categories.toString(),
                    "mirror_to_console", mirrorToConsole);
            plugin.getLogger().info("Speedrun trace diagnostics enabled: " + traceFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize trace diagnostics!", ex);
            active = false;
            close();
        }
    }

    public synchronized void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        active = false;
    }

    public synchronized void trace(String category, String event, Object... attributes) {
        if (!active || writer == null || !isCategoryEnabled(category)) {
            return;
        }

        JSONObject json = new JSONObject();
        json.put("timestamp", new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()));
        json.put("m_seconds", System.currentTimeMillis());
        json.put("category", category);
        json.put("event", event);

        for (int i = 0; i + 1 < attributes.length; i += 2) {
            json.put(String.valueOf(attributes[i]), normalizeValue(attributes[i + 1]));
        }

        writer.println(json);
        writer.flush();

        if (mirrorToConsole) {
            plugin.getLogger().info("[trace:" + category + "] " + event + " " + json);
        }
    }

    private boolean isCategoryEnabled(String category) {
        return categories.contains("ALL") || categories.contains(category.toUpperCase(Locale.ROOT));
    }

    private Set<String> normalizeCategories(List<String> rawCategories) {
        Set<String> normalized = new HashSet<>();
        for (String category : rawCategories) {
            if (category == null || category.isBlank()) {
                continue;
            }
            normalized.add(category.toUpperCase(Locale.ROOT));
        }
        if (normalized.isEmpty()) {
            normalized.add("ALL");
        }
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof Location location) {
            JSONObject json = new JSONObject();
            World world = location.getWorld();
            json.put("world", world != null ? world.getName() : JSONObject.NULL);
            json.put("environment", world != null ? world.getEnvironment().name() : JSONObject.NULL);
            json.put("x", location.getX());
            json.put("y", location.getY());
            json.put("z", location.getZ());
            json.put("block_x", location.getBlockX());
            json.put("block_y", location.getBlockY());
            json.put("block_z", location.getBlockZ());
            json.put("chunk_x", location.getBlockX() >> 4);
            json.put("chunk_z", location.getBlockZ() >> 4);
            return json;
        }
        if (value instanceof World world) {
            JSONObject json = new JSONObject();
            json.put("name", world.getName());
            json.put("environment", world.getEnvironment().name());
            json.put("uid", world.getUID().toString());
            return json;
        }
        if (value instanceof Player player) {
            JSONObject json = new JSONObject();
            json.put("name", player.getName());
            json.put("uuid", player.getUniqueId().toString());
            json.put("world", player.getWorld().getName());
            json.put("location", normalizeValue(player.getLocation()));
            return json;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value;
    }
}
