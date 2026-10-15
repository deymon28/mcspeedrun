package org.speedrun.speedrun.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.speedrun.speedrun.webconfig.WebConfigField;
import org.speedrun.speedrun.webconfig.WebConfigSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Upgrades {@code config.yml} when the plugin version or configuration schema changes.
 *
 * <p>The upgraded file is rebuilt from the bundled default configuration, so new settings and
 * current documentation comments always appear. Every value of the old file that still exists
 * and has a compatible type and range is transferred; incompatible values fall back to the
 * bundled default; settings the plugin no longer knows are kept unchanged. The original bytes
 * are written to {@code config-backups/} before the file is replaced atomically, and a report
 * lists every reset, new, renamed, or unknown setting.</p>
 *
 * <p>This class does not depend on the Bukkit server, only on its configuration API, so it is
 * fully unit-testable.</p>
 */
public final class ConfigMigrationService {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String BACKUP_FOLDER = "config-backups";
    static final String SCHEMA_KEY = "config-version";
    static final String PLUGIN_VERSION_KEY = "plugin-version";

    /** Old path to current path. Applied before values are transferred. */
    private static final Map<String, String> LEGACY_PATHS = legacyPaths();
    /** Sections whose content is user-defined and transferred as a whole. */
    private static final Set<String> USER_DEFINED_SECTIONS = Set.of("progression", "rewards");
    private static final Set<String> WORLDS = Set.of("NORMAL", "NETHER", "THE_END");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AtomicWriter writer;
    private final Clock clock;

    public ConfigMigrationService() {
        this(ConfigFileStore::writeAtomically, Clock.systemUTC());
    }

    ConfigMigrationService(AtomicWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    @FunctionalInterface
    interface AtomicWriter {
        void write(Path path, FileConfiguration config) throws IOException;
    }

    public record Result(YamlConfiguration config, ConfigMigrationReport report) {
    }

    private static Map<String, String> legacyPaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("settings.start-pre-scan.enabled", "casual.start-pre-scan.enabled");
        paths.put("settings.start-pre-scan.radius", "casual.start-pre-scan.radius");
        paths.put("casual.start-pre-scan.locate.radius", "casual.start-pre-scan.locate.radius-chunks");
        return paths;
    }

    /**
     * Creates, keeps, or upgrades the configuration file.
     *
     * @param path          location of config.yml
     * @param defaults      the bundled default configuration (with comments)
     * @param pluginVersion version of the running plugin
     * @return the configuration to use and a value-free report
     * @throws InvalidConfigurationException if the file is not valid YAML or comes from a newer schema;
     *                                       the file is left untouched in that case
     */
    public Result migrate(Path path, YamlConfiguration defaults, String pluginVersion)
            throws IOException, InvalidConfigurationException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to migrate a symbolic-link configuration file.");
        }

        if (!Files.exists(path)) {
            YamlConfiguration created = ConfigFileStore.copy(defaults);
            created.set(SCHEMA_KEY, CURRENT_SCHEMA_VERSION);
            created.set(PLUGIN_VERSION_KEY, pluginVersion);
            writer.write(path, created);
            return new Result(created, new ConfigMigrationReport("CREATED", CURRENT_SCHEMA_VERSION,
                    CURRENT_SCHEMA_VERSION, pluginVersion, pluginVersion, null, 0, List.of()));
        }

        byte[] originalBytes = Files.readAllBytes(path);
        YamlConfiguration original = ConfigFileStore.parse(new String(originalBytes, StandardCharsets.UTF_8));
        int oldSchema = schemaVersion(original);
        String oldPlugin = safePluginVersion(original.get(PLUGIN_VERSION_KEY));

        if (oldSchema == CURRENT_SCHEMA_VERSION && pluginVersion.equals(oldPlugin)) {
            List<ConfigMigrationReport.Change> problems = new ArrayList<>();
            for (String invalidPath : replaceInvalidValues(original, defaults)) {
                problems.add(change(invalidPath, "INVALID", "Value is outside the supported type or range; the built-in default is used."));
            }
            return new Result(original, new ConfigMigrationReport("CURRENT", oldSchema, CURRENT_SCHEMA_VERSION,
                    oldPlugin, pluginVersion, null, 0, problems));
        }

        YamlConfiguration source = ConfigFileStore.copy(original);
        List<ConfigMigrationReport.Change> changes = new ArrayList<>();
        applyLegacyPaths(source, changes);

        YamlConfiguration candidate = ConfigFileStore.copy(defaults);
        int transferred = transferKnownValues(source, defaults, candidate, changes);
        transferred += transferUserSections(source, defaults, candidate, changes);
        preserveUnknownValues(source, defaults, candidate, changes);

        candidate.set(SCHEMA_KEY, CURRENT_SCHEMA_VERSION);
        candidate.set(PLUGIN_VERSION_KEY, pluginVersion);

        if (!WebConfigSchema.validateConfiguration(candidate).valid()) {
            throw new InvalidConfigurationException("Configuration upgrade could not produce a valid configuration. The original file was kept.");
        }

        String backup = writeBackup(path, originalBytes);
        if (!Arrays.equals(originalBytes, Files.readAllBytes(path))) {
            throw new IOException("config.yml changed while the upgrade was prepared. The original file was kept.");
        }
        writer.write(path, candidate);
        return new Result(candidate, new ConfigMigrationReport("MIGRATED", oldSchema, CURRENT_SCHEMA_VERSION,
                oldPlugin, pluginVersion, backup, transferred, changes));
    }

    /**
     * Replaces values that fail schema validation with the bundled default, in memory only. The
     * file keeps the administrator's text so the mistake can be seen and corrected.
     *
     * @return the replaced paths
     */
    public static List<String> replaceInvalidValues(FileConfiguration config, FileConfiguration defaults) {
        List<String> replaced = new ArrayList<>();
        for (WebConfigField field : WebConfigSchema.fields()) {
            if (config.contains(field.path(), true)
                    && !WebConfigSchema.validateValue(field.path(), config.get(field.path())).valid()) {
                config.set(field.path(), ConfigFileStore.copyValue(defaults.get(field.path())));
                replaced.add(field.path());
            }
        }
        return replaced;
    }

    public static int schemaVersion(FileConfiguration config) throws InvalidConfigurationException {
        Object value = config.get(SCHEMA_KEY);
        if (value == null) {
            return 0;
        }
        if (!WebConfigSchema.isInteger(value) || ((Number) value).longValue() < 0) {
            throw new InvalidConfigurationException("config-version must be a non-negative integer. The original file was kept.");
        }
        if (((Number) value).longValue() > CURRENT_SCHEMA_VERSION) {
            throw new InvalidConfigurationException("config.yml was written by a newer Speedrun version. "
                    + "Install that version or restore a backup from " + BACKUP_FOLDER + "/.");
        }
        return ((Number) value).intValue();
    }

    private static String safePluginVersion(Object value) {
        return value instanceof String text && text.matches("[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}") ? text : "unknown";
    }

    private void applyLegacyPaths(YamlConfiguration source, List<ConfigMigrationReport.Change> changes) {
        LEGACY_PATHS.forEach((oldPath, newPath) -> {
            if (!source.contains(oldPath, true) || source.isConfigurationSection(oldPath)) {
                return;
            }
            if (!source.contains(newPath, true)) {
                source.set(newPath, ConfigFileStore.copyValue(source.get(oldPath)));
                changes.add(change(newPath, "RENAMED", "Value moved from the old setting " + oldPath + "."));
            } else {
                changes.add(change(oldPath, "REMOVED", "Old duplicate of " + newPath + " removed; the newer setting was kept."));
            }
            source.set(oldPath, null);
        });
    }

    /** Copies scalar and list values for every leaf of the default configuration. */
    private int transferKnownValues(YamlConfiguration source, YamlConfiguration defaults, YamlConfiguration candidate,
                                    List<ConfigMigrationReport.Change> changes) {
        int transferred = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || isManaged(key) || isInsideUserSection(key)) {
                continue;
            }
            Object defaultValue = defaults.get(key);
            if (!source.contains(key, true)) {
                changes.add(change(key, "DEFAULTED", "New setting; the default value was added."));
                continue;
            }
            Object value = source.get(key);
            if (!compatible(defaultValue, value)) {
                changes.add(change(key, "RESET", "Unexpected value type; the default value was restored."));
                continue;
            }
            WebConfigField field = WebConfigSchema.field(key);
            if (field != null && !WebConfigSchema.validateValue(key, value).valid()) {
                changes.add(change(key, "RESET", "Value is no longer supported; the default value was restored."));
                continue;
            }
            candidate.set(key, normalize(field, ConfigFileStore.copyValue(value)));
            transferred++;
        }
        return transferred;
    }

    private static Object normalize(WebConfigField field, Object value) {
        if (field != null && field.type() == WebConfigField.FieldType.ENUM && value instanceof String text) {
            return "settings.language".equals(field.path())
                    ? org.speedrun.speedrun.managers.ConfigManager.normalizeLanguageCode(text)
                    : text.trim().toUpperCase(Locale.ROOT);
        }
        return value;
    }

    /** Transfers user-defined stages and reward lists, repairing invalid entries. */
    private int transferUserSections(YamlConfiguration source, YamlConfiguration defaults, YamlConfiguration candidate,
                                     List<ConfigMigrationReport.Change> changes) {
        int transferred = 0;
        ConfigurationSection progression = source.getConfigurationSection("progression");
        if (progression != null) {
            ConfigurationSection target = candidate.getConfigurationSection("progression");
            for (String stageKey : new ArrayList<>(target.getKeys(false))) {
                if (!"settings".equals(stageKey)) {
                    target.set(stageKey, null);
                }
            }
            for (String stageKey : progression.getKeys(false)) {
                if ("settings".equals(stageKey)) {
                    continue;
                }
                String stagePath = "progression." + stageKey;
                ConfigurationSection stage = progression.getConfigurationSection(stageKey);
                if (stage == null) {
                    changes.add(change(stagePath, "REMOVED", "Not a valid stage; it is kept in the backup."));
                    continue;
                }
                ConfigurationSection copy = target.createSection(stageKey);
                ConfigFileStore.copySection(stage, copy);
                repairStage(stagePath, copy, changes);
                transferred++;
            }
        } else if (source.contains("progression", true)) {
            changes.add(change("progression", "RESET", "Not a valid section; the default stages were restored."));
        } else {
            changes.add(change("progression", "DEFAULTED", "No stages were configured; the default stages were added."));
        }

        for (String key : List.of("rewards.on-task-complete", "rewards.on-stage-complete")) {
            if (!source.contains(key, true)) {
                changes.add(change(key, "DEFAULTED", "New setting; the default rewards were added."));
                continue;
            }
            Object value = source.get(key);
            if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
                candidate.set(key, ConfigFileStore.copyValue(list));
                transferred++;
            } else {
                candidate.set(key, new ArrayList<>(defaults.getStringList(key)));
                changes.add(change(key, "RESET", "Expected a list of reward lines; the default rewards were restored."));
            }
        }
        return transferred;
    }

    private void repairStage(String stagePath, ConfigurationSection stage, List<ConfigMigrationReport.Change> changes) {
        Object world = stage.get("world");
        if (world != null && (!(world instanceof String text) || !WORLDS.contains(text.toUpperCase(Locale.ROOT)))) {
            stage.set("world", "NORMAL");
            changes.add(change(stagePath + ".world", "RESET", "Unsupported world; NORMAL is used."));
        }
        if (stage.contains("display-name") && !(stage.get("display-name") instanceof String)) {
            stage.set("display-name", stage.getName());
            changes.add(change(stagePath + ".display-name", "RESET", "Display name must be text."));
        }
        if (stage.contains("tasks") && !stage.isConfigurationSection("tasks")) {
            stage.createSection("tasks");
            changes.add(change(stagePath + ".tasks", "RESET", "Invalid task list replaced with an empty one."));
        }
        ConfigurationSection tasks = stage.getConfigurationSection("tasks");
        if (tasks == null) {
            return;
        }
        for (String taskKey : new ArrayList<>(tasks.getKeys(false))) {
            String taskPath = stagePath + ".tasks." + taskKey;
            ConfigurationSection task = tasks.getConfigurationSection(taskKey);
            if (task == null || !WebConfigSchema.isTaskKey(taskKey)) {
                tasks.set(taskKey, null);
                changes.add(change(taskPath, "REMOVED", "Unknown item or structure; the task is kept in the backup."));
                continue;
            }
            Object amount = task.get("amount");
            if (amount != null && (!WebConfigSchema.isInteger(amount) || ((Number) amount).longValue() < 0
                    || ((Number) amount).longValue() > Integer.MAX_VALUE)) {
                task.set("amount", 1);
                changes.add(change(taskPath + ".amount", "RESET", "Amount must be a whole number of 0 or more; 1 is used."));
            }
            if (task.contains("srbp") && !(task.get("srbp") instanceof Boolean)) {
                task.set("srbp", false);
                changes.add(change(taskPath + ".srbp", "RESET", "Player scaling must be true or false; false is used."));
            }
            if (task.contains("display-name") && !(task.get("display-name") instanceof String)) {
                task.set("display-name", taskKey);
                changes.add(change(taskPath + ".display-name", "RESET", "Display name must be text."));
            }
        }
    }

    /** Settings that the plugin does not know are kept as they are and listed in the report. */
    private void preserveUnknownValues(YamlConfiguration source, YamlConfiguration defaults, YamlConfiguration candidate,
                                       List<ConfigMigrationReport.Change> changes) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key) || isManaged(key) || defaults.contains(key, true)
                    || isInsideUserSection(key) || hasKnownParentValue(defaults, key)) {
                continue;
            }
            candidate.set(key, ConfigFileStore.copyValue(source.get(key)));
            changes.add(change(key, "UNKNOWN_KEPT", "Not used by this version; kept unchanged."));
        }
    }

    /** True when a parent of the key is a scalar in the defaults (the old value was a section there). */
    private static boolean hasKnownParentValue(YamlConfiguration defaults, String key) {
        int dot = key.lastIndexOf('.');
        while (dot > 0) {
            String parent = key.substring(0, dot);
            if (defaults.contains(parent, true) && !defaults.isConfigurationSection(parent)) {
                return true;
            }
            dot = parent.lastIndexOf('.');
        }
        return false;
    }

    private static boolean isManaged(String key) {
        return SCHEMA_KEY.equals(key) || PLUGIN_VERSION_KEY.equals(key);
    }

    /** Stage definitions and reward lists are handled by {@link #transferUserSections}. */
    private static boolean isInsideUserSection(String key) {
        if (key.startsWith("progression.") && !key.startsWith("progression.settings")) {
            return true;
        }
        return key.equals("rewards.on-task-complete") || key.equals("rewards.on-stage-complete")
                || (key.startsWith("rewards.on-task-complete.") || key.startsWith("rewards.on-stage-complete."));
    }

    static boolean compatible(Object defaultValue, Object value) {
        if (value == null || value instanceof ConfigurationSection) {
            return false;
        }
        if (defaultValue instanceof Boolean) {
            return value instanceof Boolean;
        }
        if (defaultValue instanceof Number number) {
            if (!(value instanceof Number)) {
                return false;
            }
            return !WebConfigSchema.isInteger(number) || WebConfigSchema.isInteger(value);
        }
        if (defaultValue instanceof String) {
            return value instanceof String;
        }
        if (defaultValue instanceof List<?>) {
            return value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String);
        }
        return defaultValue == null || defaultValue.getClass().isInstance(value);
    }

    private String writeBackup(Path path, byte[] originalBytes) throws IOException {
        Path folder = path.toAbsolutePath().getParent().resolve(BACKUP_FOLDER);
        if (Files.isSymbolicLink(folder)) {
            throw new IOException("Refusing to write configuration backups through a symbolic link.");
        }
        Files.createDirectories(folder);
        String stamp = STAMP.format(clock.instant());
        Path backup = folder.resolve("config-" + stamp + ".yml");
        for (int attempt = 2; Files.exists(backup); attempt++) {
            backup = folder.resolve("config-" + stamp + "-" + attempt + ".yml");
        }
        Files.write(backup, originalBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return BACKUP_FOLDER + "/" + backup.getFileName();
    }

    private static ConfigMigrationReport.Change change(String path, String action, String reason) {
        return new ConfigMigrationReport.Change(path, action, reason);
    }
}
