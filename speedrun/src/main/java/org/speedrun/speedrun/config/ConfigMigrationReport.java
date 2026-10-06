package org.speedrun.speedrun.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Value-free summary of a configuration upgrade, safe for logs, chat, and the web editor.
 * It names setting paths and actions but never contains configured values.
 *
 * <p>Status is one of {@code CREATED}, {@code CURRENT}, {@code MIGRATED}, or {@code FAILED}.</p>
 */
public record ConfigMigrationReport(String status, int fromSchema, int toSchema,
                                    String fromPluginVersion, String toPluginVersion,
                                    String backupFile, int transferred, List<Change> changes) {
    public ConfigMigrationReport {
        changes = List.copyOf(changes);
    }

    public static ConfigMigrationReport failed(String pluginVersion, String reason) {
        return new ConfigMigrationReport("FAILED", 0, ConfigMigrationService.CURRENT_SCHEMA_VERSION,
                "unknown", pluginVersion, null, 0, List.of(new Change("config.yml", "FAILED", reason)));
    }

    /**
     * One affected setting. Actions: DEFAULTED (new setting), RESET (incompatible value replaced),
     * RENAMED, REMOVED, UNKNOWN_KEPT, INVALID (current config, value ignored), FAILED.
     */
    public record Change(String path, String action, String reason) {
        public Change {
            path = path.replaceAll("\\p{Cntrl}", "?");
            if (path.length() > 256) {
                path = path.substring(0, 256);
            }
        }
    }

    public long count(String action) {
        return changes.stream().filter(change -> change.action().equals(action)).count();
    }

    /** True when an administrator should be told about this report. */
    public boolean needsAttention() {
        return "FAILED".equals(status) || count("RESET") > 0 || count("INVALID") > 0
                || count("REMOVED") > 0 || count("UNKNOWN_KEPT") > 0;
    }

    /** Human-readable multi-line report for the log file next to the backup. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("Speedrun configuration " + status.toLowerCase(java.util.Locale.ROOT)
                + " (" + fromPluginVersion + " -> " + toPluginVersion + ", schema " + fromSchema + " -> " + toSchema + ")");
        if (backupFile != null) {
            lines.add("Backup of the previous file: " + backupFile);
        }
        lines.add("Transferred settings: " + transferred
                + ", new defaults: " + count("DEFAULTED")
                + ", reset: " + count("RESET")
                + ", renamed: " + count("RENAMED")
                + ", removed: " + count("REMOVED")
                + ", unknown kept: " + count("UNKNOWN_KEPT")
                + (count("INVALID") > 0 ? ", invalid: " + count("INVALID") : ""));
        for (Change change : changes) {
            lines.add("  [" + change.action() + "] " + change.path() + " - " + change.reason());
        }
        return lines;
    }

    public JSONObject toJson() {
        JSONArray entries = new JSONArray();
        changes.forEach(change -> entries.put(new JSONObject()
                .put("path", change.path()).put("action", change.action()).put("reason", change.reason())));
        return new JSONObject()
                .put("status", status).put("fromSchema", fromSchema).put("toSchema", toSchema)
                .put("fromPluginVersion", fromPluginVersion).put("toPluginVersion", toPluginVersion)
                .put("backupFile", backupFile == null ? JSONObject.NULL : backupFile)
                .put("transferred", transferred)
                .put("changes", entries);
    }
}
