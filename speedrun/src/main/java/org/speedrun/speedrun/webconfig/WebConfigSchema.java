package org.speedrun.speedrun.webconfig;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.speedrun.speedrun.webconfig.WebConfigField.ApplyImpact.INSTANT;
import static org.speedrun.speedrun.webconfig.WebConfigField.ApplyImpact.RESTART_TASK;
import static org.speedrun.speedrun.webconfig.WebConfigField.ApplyImpact.RUN_SENSITIVE;
import static org.speedrun.speedrun.webconfig.WebConfigField.ApplyImpact.STARTUP_ONLY;
import static org.speedrun.speedrun.webconfig.WebConfigField.DangerLevel.CAUTION;
import static org.speedrun.speedrun.webconfig.WebConfigField.DangerLevel.DANGEROUS;
import static org.speedrun.speedrun.webconfig.WebConfigField.DangerLevel.NONE;
import static org.speedrun.speedrun.webconfig.WebConfigField.FieldType.BOOLEAN;
import static org.speedrun.speedrun.webconfig.WebConfigField.FieldType.DECIMAL;
import static org.speedrun.speedrun.webconfig.WebConfigField.FieldType.ENUM;
import static org.speedrun.speedrun.webconfig.WebConfigField.FieldType.INTEGER;
import static org.speedrun.speedrun.webconfig.WebConfigField.FieldType.STRING;
import static org.speedrun.speedrun.webconfig.WebConfigField.FieldType.STRING_LIST;

public final class WebConfigSchema {
    private static final Map<String, WebConfigField> FIELDS = buildFields();
    private static final Set<String> STRUCTURED_SECTIONS = Set.of("progression", "rewards");
    private static final Set<String> WORLDS = Set.of("NORMAL", "NETHER", "THE_END");

    private WebConfigSchema() {
    }

    public static Collection<WebConfigField> fields() {
        return FIELDS.values();
    }

    public static WebConfigField field(String path) {
        return FIELDS.get(path);
    }

    public static JSONArray fieldsJson() {
        JSONArray fields = new JSONArray();
        FIELDS.values().forEach(field -> fields.put(field.toJson()));
        return fields;
    }

    public static JSONObject currentValues(FileConfiguration config) {
        JSONObject values = new JSONObject();
        for (WebConfigField field : FIELDS.values()) {
            values.put(field.path(), configValue(config, field));
        }
        values.put("progression", sectionToJson(config.getConfigurationSection("progression")));
        values.put("rewards", sectionToJson(config.getConfigurationSection("rewards")));
        return values;
    }

    public static WebConfigValidationResult validatePatch(JSONObject patch) {
        WebConfigValidationResult result = new WebConfigValidationResult();

        for (String key : patch.keySet()) {
            if (STRUCTURED_SECTIONS.contains(key)) {
                validateStructuredSection(key, patch.opt(key), result);
                continue;
            }

            WebConfigField field = FIELDS.get(key);
            if (field == null) {
                result.error("Unsupported config path: " + key);
                continue;
            }
            validateField(field, patch.opt(key), result);
        }
        return result;
    }

    public static void applyPatch(FileConfiguration config, JSONObject patch) {
        for (String key : patch.keySet()) {
            Object value = patch.opt(key);
            if ("progression".equals(key) || "rewards".equals(key)) {
                config.set(key, jsonToConfigValue(value));
                continue;
            }

            WebConfigField field = FIELDS.get(key);
            if (field != null) {
                config.set(key, normalizedValue(field, value));
            }
        }
    }

    public static List<WebConfigField.ApplyImpact> impactsForPatch(JSONObject patch) {
        List<WebConfigField.ApplyImpact> impacts = new ArrayList<>();
        for (String key : patch.keySet()) {
            if ("progression".equals(key)) {
                addImpact(impacts, RUN_SENSITIVE);
            } else if ("rewards".equals(key)) {
                addImpact(impacts, INSTANT);
            } else {
                WebConfigField field = FIELDS.get(key);
                if (field != null) {
                    addImpact(impacts, field.impact());
                }
            }
        }
        return impacts;
    }

    private static void addImpact(List<WebConfigField.ApplyImpact> impacts, WebConfigField.ApplyImpact impact) {
        if (!impacts.contains(impact)) {
            impacts.add(impact);
        }
    }

    private static Object configValue(FileConfiguration config, WebConfigField field) {
        return switch (field.type()) {
            case BOOLEAN -> config.getBoolean(field.path(), Boolean.TRUE.equals(field.defaultValue()));
            case INTEGER -> config.getInt(field.path(), field.defaultValue() instanceof Number number ? number.intValue() : 0);
            case DECIMAL -> config.getDouble(field.path(), field.defaultValue() instanceof Number number ? number.doubleValue() : 0.0);
            case STRING, ENUM -> config.getString(field.path(), String.valueOf(field.defaultValue()));
            case STRING_LIST -> new JSONArray(config.getStringList(field.path()));
        };
    }

    private static void validateField(WebConfigField field, Object value, WebConfigValidationResult result) {
        if (value == null || value == JSONObject.NULL) {
            result.error(field.path() + " cannot be empty.");
            return;
        }

        switch (field.type()) {
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    result.error(field.path() + " must be true or false.");
                }
            }
            case INTEGER -> validateNumber(field, value, true, result);
            case DECIMAL -> validateNumber(field, value, false, result);
            case STRING -> {
                if (!(value instanceof String)) {
                    result.error(field.path() + " must be text.");
                }
            }
            case ENUM -> validateEnum(field, value, result);
            case STRING_LIST -> validateStringList(field.path(), value, result);
        }

        if (field.danger() == DANGEROUS) {
            result.warning(field.path() + " can heavily affect server performance.");
        } else if (field.danger() == CAUTION) {
            result.warning(field.path() + " restarts or changes live runtime behavior.");
        }
    }

    private static void validateNumber(WebConfigField field, Object value, boolean integerOnly, WebConfigValidationResult result) {
        if (!(value instanceof Number number)) {
            result.error(field.path() + " must be a number.");
            return;
        }
        double numeric = number.doubleValue();
        if (integerOnly && Math.floor(numeric) != numeric) {
            result.error(field.path() + " must be an integer.");
        }
        if (field.min() != null && numeric < field.min()) {
            result.error(field.path() + " must be at least " + formatLimit(field.min()) + ".");
        }
        if (field.max() != null && numeric > field.max()) {
            result.error(field.path() + " must be at most " + formatLimit(field.max()) + ".");
        }
    }

    private static String formatLimit(double value) {
        return Math.floor(value) == value ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static void validateEnum(WebConfigField field, Object value, WebConfigValidationResult result) {
        if (!(value instanceof String string)) {
            result.error(field.path() + " must be one of " + field.options() + ".");
            return;
        }
        String normalized = string.toUpperCase(Locale.ROOT);
        if (field.options() == null || field.options().stream().noneMatch(option -> option.equalsIgnoreCase(normalized))) {
            result.error(field.path() + " must be one of " + field.options() + ".");
        }
    }

    private static void validateStringList(String path, Object value, WebConfigValidationResult result) {
        if (!(value instanceof JSONArray array)) {
            result.error(path + " must be a list of text values.");
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            if (!(array.opt(i) instanceof String)) {
                result.error(path + "[" + i + "] must be text.");
            }
        }
    }

    private static Object normalizedValue(WebConfigField field, Object value) {
        return switch (field.type()) {
            case INTEGER -> ((Number) value).intValue();
            case DECIMAL -> ((Number) value).doubleValue();
            case ENUM -> ((String) value).toUpperCase(Locale.ROOT);
            case STRING_LIST -> jsonToConfigValue(value);
            default -> value;
        };
    }

    private static void validateStructuredSection(String key, Object value, WebConfigValidationResult result) {
        if (!(value instanceof JSONObject object)) {
            result.error(key + " must be an object.");
            return;
        }
        if ("progression".equals(key)) {
            validateProgression(object, result);
        } else if ("rewards".equals(key)) {
            validateRewards(object, result);
        }
    }

    private static void validateProgression(JSONObject progression, WebConfigValidationResult result) {
        for (String stageKey : progression.keySet()) {
            if ("settings".equals(stageKey)) {
                continue;
            }
            Object stageValue = progression.opt(stageKey);
            if (!(stageValue instanceof JSONObject stage)) {
                result.error("progression." + stageKey + " must be an object.");
                continue;
            }
            String world = stage.optString("world", "NORMAL").toUpperCase(Locale.ROOT);
            if (!WORLDS.contains(world)) {
                result.error("progression." + stageKey + ".world must be NORMAL, NETHER, or THE_END.");
            }
            if (stage.has("display-name") && !(stage.opt("display-name") instanceof String)) {
                result.error("progression." + stageKey + ".display-name must be text.");
            }
            Object tasksValue = stage.opt("tasks");
            if (tasksValue != null && tasksValue != JSONObject.NULL && !(tasksValue instanceof JSONObject)) {
                result.error("progression." + stageKey + ".tasks must be an object.");
                continue;
            }
            if (tasksValue instanceof JSONObject tasks) {
                validateProgressionTasks("progression." + stageKey + ".tasks", tasks, result);
            }
        }
    }

    private static void validateProgressionTasks(String path, JSONObject tasks, WebConfigValidationResult result) {
        for (String taskKey : tasks.keySet()) {
            if (!taskKey.matches("[A-Z0-9_]+")) {
                result.error(path + "." + taskKey + " uses an invalid task key.");
            }
            if (!(tasks.opt(taskKey) instanceof JSONObject task)) {
                result.error(path + "." + taskKey + " must be an object.");
                continue;
            }
            int amount = task.optInt("amount", -1);
            if (amount < 0) {
                result.error(path + "." + taskKey + ".amount must be 0 or higher.");
            }
            if (task.has("display-name") && !(task.opt("display-name") instanceof String)) {
                result.error(path + "." + taskKey + ".display-name must be text.");
            }
            if (task.has("srbp") && !(task.opt("srbp") instanceof Boolean)) {
                result.error(path + "." + taskKey + ".srbp must be true or false.");
            }
        }
    }

    private static void validateRewards(JSONObject rewards, WebConfigValidationResult result) {
        if (rewards.has("enabled") && !(rewards.opt("enabled") instanceof Boolean)) {
            result.error("rewards.enabled must be true or false.");
        }
        if (rewards.has("on-task-complete")) {
            validateStringList("rewards.on-task-complete", rewards.opt("on-task-complete"), result);
        }
        if (rewards.has("on-stage-complete")) {
            validateStringList("rewards.on-stage-complete", rewards.opt("on-stage-complete"), result);
        }
    }

    static JSONObject sectionToJson(ConfigurationSection section) {
        JSONObject json = new JSONObject();
        if (section == null) {
            return json;
        }
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                json.put(key, sectionToJson(child));
            } else if (value instanceof List<?> list) {
                json.put(key, new JSONArray(list));
            } else {
                json.put(key, value == null ? JSONObject.NULL : value);
            }
        }
        return json;
    }

    static Object jsonToConfigValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject object) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : object.keySet()) {
                map.put(key, jsonToConfigValue(object.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray array) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                list.add(jsonToConfigValue(array.opt(i)));
            }
            return list;
        }
        return value;
    }

    public static JSONObject configFileToJson(FileConfiguration config) {
        JSONObject root = new JSONObject();
        for (String key : config.getKeys(false)) {
            Object value = config.get(key);
            if (value instanceof ConfigurationSection section) {
                root.put(key, sectionToJson(section));
            } else {
                root.put(key, value == null ? JSONObject.NULL : value);
            }
        }
        return root;
    }

    public static YamlConfiguration patchedCopy(FileConfiguration source, JSONObject patch) {
        YamlConfiguration copy = new YamlConfiguration();
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection section) {
                copy.set(key, jsonToConfigValue(sectionToJson(section)));
            } else {
                copy.set(key, value);
            }
        }
        applyPatch(copy, patch);
        return copy;
    }

    private static Map<String, WebConfigField> buildFields() {
        Map<String, WebConfigField> fields = new LinkedHashMap<>();
        add(fields, field("web-config.enabled", "General", "Web configurator", "Starts the local browser-based config editor.", BOOLEAN, STARTUP_ONLY, CAUTION, true));
        add(fields, field("web-config.bind", "General", "Web bind address", "Use 127.0.0.1 unless you intentionally expose the editor.", STRING, STARTUP_ONLY, DANGEROUS, "127.0.0.1"));
        add(fields, number("web-config.port", "General", "Web port", "Local HTTP port for the editor.", INTEGER, STARTUP_ONLY, CAUTION, 8765, 1024, 65535));
        add(fields, enumField("settings.gamemode", "General", "Game mode", "NORMAL, CASUAL, or HARDCORE runtime behavior.", RUN_SENSITIVE, CAUTION, "NORMAL", "NORMAL", "CASUAL", "HARDCORE"));
        add(fields, enumField("settings.language", "General", "Language", "Language file from plugins/Speedrun/lang.", INSTANT, NONE, "en", "en", "uk"));
        add(fields, field("settings.start-on-first-join", "General", "Start on first join", "Start the timer when the first player joins.", BOOLEAN, STARTUP_ONLY, CAUTION, true));
        add(fields, field("settings.reset_time_on_join", "General", "Reset day on join", "Set clock worlds to day when first-join starts a run.", BOOLEAN, INSTANT, NONE, true));
        add(fields, field("settings.allow-reassigning-locations", "General", "Allow location reassignment", "Allow /run new to reassign tracked locations.", BOOLEAN, INSTANT, NONE, true));
        add(fields, number("settings.village-search-timeout", "General", "Village timeout seconds", "Time before the village task times out.", INTEGER, INSTANT, NONE, 600, 0, 7200));
        add(fields, enumField("settings.task-tracking-mode", "General", "Task tracking mode", "Inventory-only or cumulative pickup/craft tracking.", RUN_SENSITIVE, CAUTION, "INVENTORY", "INVENTORY", "CUMULATIVE"));
        add(fields, field("settings.scale-resources-by-playercount.enabled", "General", "Scale resources by players", "Scale item task requirements by online player count.", BOOLEAN, RUN_SENSITIVE, NONE, true));
        add(fields, number("settings.scale-resources-by-playercount.multiplier", "General", "Player scaling multiplier", "Additional requirement multiplier per extra player.", DECIMAL, RUN_SENSITIVE, NONE, 0.5, 0, 10));
        add(fields, field("settings.log-attempts", "General", "Attempt logs", "Write plain and structured run logs.", BOOLEAN, INSTANT, NONE, true));
        add(fields, enumField("settings.coordinate-display.mode", "General", "Coordinate display", "How Overworld/Nether linked coordinates appear.", INSTANT, NONE, "CONDITIONAL", "UNIFIED", "SEPARATE", "CONDITIONAL"));
        add(fields, field("settings.chunk-biome-logging.enabled", "General", "Chunk biome logging", "Log visited Overworld chunk biomes.", BOOLEAN, INSTANT, NONE, true));

        add(fields, enumField("progression.settings.task-display-mode", "Progression", "Task display mode", "Active stage, all current-world stages, or all game stages.", INSTANT, NONE, "ALL_GAME_STAGES", "ACTIVE_STAGE", "ALL_STAGES", "ALL_GAME_STAGES"));
        add(fields, field("progression.settings.completed-task-hide.enabled", "Progression", "Hide completed tasks", "Hide completed tasks after a timeout.", BOOLEAN, INSTANT, NONE, true));
        add(fields, number("progression.settings.completed-task-hide.timeout-seconds", "Progression", "Completed task hide timeout", "Seconds before completed tasks disappear.", INTEGER, INSTANT, NONE, 10, 0, 600));

        add(fields, field("casual.enabled", "Casual", "Casual features enabled", "Allows Casual features when game mode is CASUAL.", BOOLEAN, RUN_SENSITIVE, CAUTION, true));
        add(fields, field("casual.player-tab-coordinates", "Casual", "Tab coordinates", "Shows coordinates in the player list header.", BOOLEAN, RESTART_TASK, NONE, true));
        add(fields, field("casual.compass.show-nether-reference", "Casual", "Show Nether reference", "Shows the legacy test Nether reference destination.", BOOLEAN, INSTANT, NONE, false));
        add(fields, field("casual.compass.death-location.enabled", "Casual", "Death compass destination", "Adds each player's last death to their own compass menu.", BOOLEAN, INSTANT, NONE, true));
        add(fields, field("casual.structure_waypoints.enabled", "Casual", "Structure waypoints", "Creates world waypoint markers for found structures.", BOOLEAN, RESTART_TASK, CAUTION, true));
        add(fields, enumField("casual.structure_waypoints.type", "Casual", "Waypoint marker type", "END_GATEWAY is compact; BEACON remains available.", RESTART_TASK, CAUTION, "END_GATEWAY", "END_GATEWAY", "BEACON"));
        add(fields, number("casual.structure_waypoints.offset-blocks", "Casual", "Waypoint offset blocks", "Horizontal offset from structure location.", INTEGER, RESTART_TASK, CAUTION, 10, 0, 128));
        add(fields, number("casual.structure_waypoints.update-interval-seconds", "Casual", "Waypoint refresh seconds", "How often waypoint markers are refreshed.", INTEGER, RESTART_TASK, CAUTION, 10, 1, 300));
        add(fields, field("casual.nether_gold_highlight.enabled", "Casual", "Nether gold highlight", "Highlights nearby gold blocks around Nether players.", BOOLEAN, RESTART_TASK, DANGEROUS, true));
        add(fields, number("casual.nether_gold_highlight.radius", "Casual", "Nether gold radius", "Radius for Nether gold highlighting.", INTEGER, RESTART_TASK, DANGEROUS, 24, 1, 96));

        add(fields, number("settings.proximity-scanner.lava-pool.radius", "Scanner", "Lava pool radius", "Nearby lava pool scan radius.", INTEGER, INSTANT, CAUTION, 16, 1, 64));
        add(fields, number("settings.proximity-scanner.lava-pool.required-source-blocks", "Scanner", "Lava source blocks", "Minimum source blocks for a lava pool.", INTEGER, INSTANT, NONE, 12, 1, 256));
        add(fields, number("settings.proximity-scanner.village.radius", "Scanner", "Village bell radius", "Bell scan radius for village confirmation.", INTEGER, INSTANT, CAUTION, 32, 1, 96));
        add(fields, number("settings.proximity-scanner.nether-portal.check-radius", "Scanner", "Nether portal check radius", "Radius around fire/light events for portal detection.", INTEGER, INSTANT, NONE, 4, 1, 32));
        add(fields, number("settings.proximity-scanner.nether-portal.search-radius", "Scanner", "Portal search radius", "Precise destination-side portal search radius.", INTEGER, INSTANT, CAUTION, 90, 1, 256));
        add(fields, number("settings.proximity-scanner.portal-search-timeout", "Scanner", "Portal search timeout", "Seconds before exact portal search falls back.", INTEGER, INSTANT, NONE, 15, 1, 120));
        add(fields, field("casual.start-pre-scan.enabled", "Scanner", "Casual start pre-scan", "Controlled discovery when a Casual run starts.", BOOLEAN, RESTART_TASK, CAUTION, false));
        add(fields, enumField("casual.start-pre-scan.mode", "Scanner", "Start pre-scan mode", "SAFE, BALANCED, AGGRESSIVE, or LOCATE profile.", RESTART_TASK, DANGEROUS, "BALANCED", "SAFE", "BALANCED", "AGGRESSIVE", "LOCATE"));
        add(fields, number("casual.start-pre-scan.radius", "Scanner", "Pre-scan radius blocks", "Configured block radius for start pre-scan.", INTEGER, RESTART_TASK, DANGEROUS, 1000, 1, 10000));
        add(fields, number("casual.start-pre-scan.loaded-chunk-radius", "Scanner", "Loaded chunk scan radius", "Radius in chunks around players for loaded-chunk scans.", INTEGER, RESTART_TASK, DANGEROUS, 10, 0, 64));
        add(fields, field("casual.start-pre-scan.include-lava-pool", "Scanner", "Pre-scan lava pools", "Include lava pool detection in start pre-scan.", BOOLEAN, RESTART_TASK, CAUTION, true));
        add(fields, number("casual.start-pre-scan.lava-pool-vertical-scan.below-player", "Scanner", "Lava scan below player", "Blocks below player origin for lava scans.", INTEGER, RESTART_TASK, CAUTION, 12, 0, 128));
        add(fields, number("casual.start-pre-scan.lava-pool-vertical-scan.above-player", "Scanner", "Lava scan above player", "Blocks above player origin for lava scans.", INTEGER, RESTART_TASK, CAUTION, 32, 0, 128));
        add(fields, number("casual.start-pre-scan.safety.max-chunks-per-run", "Scanner", "Safety max chunks per run", "Hard cap for background chunk load rate.", INTEGER, RESTART_TASK, DANGEROUS, 8, 1, 64));
        add(fields, number("casual.start-pre-scan.safety.max-queued-chunks", "Scanner", "Safety max queued chunks", "Hard cap for background chunk queue size.", INTEGER, RESTART_TASK, DANGEROUS, 5000, 0, 20000));
        add(fields, number("casual.start-pre-scan.locate.radius-chunks", "Scanner", "Locate radius chunks", "LOCATE mode API search radius.", INTEGER, RESTART_TASK, DANGEROUS, 96, 1, 512));
        add(fields, number("casual.start-pre-scan.locate.period-ticks", "Scanner", "Locate period ticks", "Ticks between LOCATE calls.", INTEGER, RESTART_TASK, DANGEROUS, 20, 1, 1200));
        add(fields, number("casual.start-pre-scan.locate.calls-per-run", "Scanner", "Locate calls per run", "Synchronous locate calls per scheduler run.", INTEGER, RESTART_TASK, DANGEROUS, 1, 1, 8));
        add(fields, field("casual.start-pre-scan.locate.find-unexplored", "Scanner", "Locate unexplored", "Allow locate API to search unexplored structures.", BOOLEAN, RESTART_TASK, DANGEROUS, false));
        add(fields, field("casual.start-pre-scan.locate.targets.village", "Scanner", "Locate villages", "Include villages in LOCATE mode.", BOOLEAN, RESTART_TASK, CAUTION, true));
        add(fields, field("casual.start-pre-scan.locate.targets.stronghold", "Scanner", "Locate stronghold", "Include stronghold in LOCATE mode.", BOOLEAN, RESTART_TASK, CAUTION, true));
        add(fields, field("casual.start-pre-scan.locate.targets.nether-structures", "Scanner", "Locate Nether structures", "Include fortress and bastion in LOCATE mode.", BOOLEAN, RESTART_TASK, CAUTION, true));
        addStartPreScanProfileFields(fields, "SAFE");
        addStartPreScanProfileFields(fields, "BALANCED");
        addStartPreScanProfileFields(fields, "AGGRESSIVE");
        addStartPreScanProfileFields(fields, "LOCATE");

        add(fields, field("tracking.player-blocks.enabled", "Diagnostics", "Player block tracking", "Log player block coordinates on movement.", BOOLEAN, INSTANT, DANGEROUS, false));
        add(fields, field("diagnostics.trace.enabled", "Diagnostics", "Trace diagnostics", "Write detailed JSONL runtime traces.", BOOLEAN, INSTANT, CAUTION, false));
        add(fields, field("diagnostics.trace.mirror-to-console", "Diagnostics", "Mirror trace to console", "Also write trace events to normal server logs.", BOOLEAN, INSTANT, DANGEROUS, false));
        add(fields, list("diagnostics.trace.categories", "Diagnostics", "Trace categories", "ALL or selected trace categories.", INSTANT, NONE, List.of("ALL")));
        add(fields, field("rewards.enabled", "Rewards", "Rewards enabled", "Enable configured command, sound, and particle rewards.", BOOLEAN, INSTANT, NONE, true));
        return fields;
    }

    private static void addStartPreScanProfileFields(Map<String, WebConfigField> fields, String profile) {
        String path = "casual.start-pre-scan.profiles." + profile + ".";
        add(fields, number(path + "chunks-per-run", "Scanner", profile + " chunks per run", "Background chunks scanned per scheduler run for " + profile + ".", INTEGER, RESTART_TASK, DANGEROUS, "SAFE".equals(profile) || "LOCATE".equals(profile) ? 0 : "BALANCED".equals(profile) ? 2 : 8, 0, 64));
        add(fields, number(path + "period-ticks", "Scanner", profile + " period ticks", "Scheduler period for " + profile + ".", INTEGER, RESTART_TASK, DANGEROUS, "AGGRESSIVE".equals(profile) ? 10 : "BALANCED".equals(profile) ? 20 : 40, 1, 1200));
        add(fields, number(path + "max-queued-chunks", "Scanner", profile + " max queued chunks", "Background queue size for " + profile + ".", INTEGER, RESTART_TASK, DANGEROUS, "SAFE".equals(profile) || "LOCATE".equals(profile) ? 0 : "BALANCED".equals(profile) ? 1500 : 5000, 0, 20000));
        add(fields, field(path + "load-missing-chunks", "Scanner", profile + " load missing chunks", "Allow Paper async chunk loads for " + profile + ".", BOOLEAN, RESTART_TASK, DANGEROUS, "BALANCED".equals(profile) || "AGGRESSIVE".equals(profile)));
        add(fields, field(path + "scan-spawn", "Scanner", profile + " scan spawn", "Queue spawn chunks for " + profile + ".", BOOLEAN, RESTART_TASK, CAUTION, "BALANCED".equals(profile) || "AGGRESSIVE".equals(profile)));
        add(fields, field(path + "scan-players", "Scanner", profile + " scan players", "Queue player chunks for " + profile + ".", BOOLEAN, RESTART_TASK, CAUTION, "BALANCED".equals(profile) || "AGGRESSIVE".equals(profile)));
        add(fields, field(path + "include-nether", "Scanner", profile + " include Nether", "Include Nether chunks for " + profile + ".", BOOLEAN, RESTART_TASK, DANGEROUS, "AGGRESSIVE".equals(profile)));
    }

    private static WebConfigField field(String path, String section, String label, String description, WebConfigField.FieldType type,
                                        WebConfigField.ApplyImpact impact, WebConfigField.DangerLevel danger, Object defaultValue) {
        return new WebConfigField(path, section, label, description, type, impact, danger, defaultValue, List.of(), null, null);
    }

    private static WebConfigField number(String path, String section, String label, String description, WebConfigField.FieldType type,
                                         WebConfigField.ApplyImpact impact, WebConfigField.DangerLevel danger, Object defaultValue,
                                         double min, double max) {
        return new WebConfigField(path, section, label, description, type, impact, danger, defaultValue, List.of(), min, max);
    }

    private static WebConfigField enumField(String path, String section, String label, String description,
                                            WebConfigField.ApplyImpact impact, WebConfigField.DangerLevel danger,
                                            String defaultValue, String... options) {
        return new WebConfigField(path, section, label, description, ENUM, impact, danger, defaultValue, List.of(options), null, null);
    }

    private static WebConfigField list(String path, String section, String label, String description,
                                       WebConfigField.ApplyImpact impact, WebConfigField.DangerLevel danger, List<String> defaultValue) {
        return new WebConfigField(path, section, label, description, STRING_LIST, impact, danger, new JSONArray(defaultValue), List.of(), null, null);
    }

    private static void add(Map<String, WebConfigField> fields, WebConfigField field) {
        fields.put(field.path(), field);
    }
}
