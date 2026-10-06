package org.speedrun.speedrun.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.config.ConfigFileStore;
import org.speedrun.speedrun.config.ConfigMigrationReport;
import org.speedrun.speedrun.config.ConfigMigrationService;
import org.speedrun.speedrun.config.LanguageConfigLoader;
import org.speedrun.speedrun.config.LanguageFiles;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.utils.RewardUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Single access point for {@code config.yml}, message files, game mode, and message formatting.
 *
 * <p>On every load the configuration passes through {@link ConfigMigrationService}, which keeps
 * the file in step with the running plugin version. Message files are resolved from the
 * customizable disk copy, then the bundled translation, then bundled English.</p>
 */
public class ConfigManager {

    private final Speedrun plugin;
    private final ConfigMigrationService migrationService = new ConfigMigrationService();
    private FileConfiguration config;
    private FileConfiguration lang;
    private YamlConfiguration bundledDefaults;
    private ConfigMigrationReport migrationReport;
    private static final String DEFAULT_LANGUAGE = LanguageFiles.DEFAULT_LANGUAGE;

    /**
     * Defines how player-collected resources are tracked for tasks.
     */
    public enum TrackingMode {
        /** Count only items currently in a player's inventory. */
        INVENTORY,
        /** Count all items ever collected by players during the run. */
        CUMULATIVE
    }

    public enum GameMode {
        NORMAL,
        CASUAL,
        HARDCORE
    }

    public enum WaypointType {
        BEACON,
        END_GATEWAY
    }

    public enum CoordinateDisplayMode {
        UNIFIED,
        SEPARATE,
        CONDITIONAL
    }

    public enum TaskDisplayMode {
        ACTIVE_STAGE,
        ALL_STAGES,
        ALL_GAME_STAGES
    }

    public enum StartPreScanMode {
        SAFE,
        BALANCED,
        AGGRESSIVE,
        LOCATE
    }

    public ConfigManager(Speedrun plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Loads {@code config.yml} (creating or upgrading it when needed) and the message files.
     * Invalid YAML never overwrites the file: the built-in defaults are used until it is fixed.
     */
    public void reload() {
        bundledDefaults = loadBundledYaml("config.yml");
        String version = plugin.getDescription().getVersion();
        Path configPath = plugin.getConfigFile().toPath();
        try {
            ConfigMigrationService.Result result = migrationService.migrate(configPath, bundledDefaults, version);
            config = result.config();
            migrationReport = result.report();
        } catch (IOException | InvalidConfigurationException failure) {
            plugin.getLogger().severe("config.yml could not be loaded: " + failure.getMessage()
                    + " Built-in defaults are used until the file is fixed (then run /run reload).");
            config = ConfigFileStore.copy(bundledDefaults);
            migrationReport = ConfigMigrationReport.failed(version, failure.getMessage());
        }
        config.setDefaults(bundledDefaults);
        reportMigration();
        upgradeLanguageFiles();
        reloadLanguageFromCurrentConfig();
    }

    private void reportMigration() {
        switch (migrationReport.status()) {
            case "CREATED" -> plugin.getLogger().info("Created config.yml with default settings. Use /run webconfig to configure the plugin in your browser.");
            case "MIGRATED" -> {
                List<String> lines = migrationReport.describe();
                lines.forEach(line -> plugin.getLogger().info(line));
                writeMigrationLog(lines);
            }
            case "CURRENT" -> migrationReport.changes().forEach(change ->
                    plugin.getLogger().warning("config.yml: " + change.path() + " - " + change.reason()));
            default -> {
                // FAILED was already logged.
            }
        }
    }

    private void writeMigrationLog(List<String> lines) {
        try {
            Path folder = plugin.getDataFolder().toPath().resolve(ConfigMigrationService.BACKUP_FOLDER);
            Files.createDirectories(folder);
            Path log = folder.resolve("migration-report.txt");
            StringBuilder text = new StringBuilder("# ").append(LocalDateTime.now()).append(System.lineSeparator());
            lines.forEach(line -> text.append(line).append(System.lineSeparator()));
            text.append(System.lineSeparator());
            Files.writeString(log, text, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not write the configuration migration report: " + failure.getMessage());
        }
    }

    /** Extracts missing message files and adds new messages to customized ones. */
    private void upgradeLanguageFiles() {
        for (String code : LanguageFiles.SUPPORTED) {
            String bundled = readBundledText("lang/" + code + ".yml");
            if (bundled == null) {
                continue;
            }
            try {
                int added = LanguageFiles.upgrade(languageFile(code), bundled);
                if (added > 0) {
                    plugin.getLogger().info("lang/" + code + ".yml: added " + added
                            + " new messages; your customized messages were kept (backup in lang-backups/).");
                }
            } catch (IOException | InvalidConfigurationException failure) {
                plugin.getLogger().warning("lang/" + code + ".yml could not be updated: " + failure.getMessage());
            }
        }
    }

    public void reloadLanguageFromCurrentConfig() {
        String configured = config.getString("settings.language", DEFAULT_LANGUAGE);
        String supportedCode = normalizeSupportedLanguageCode(configured);
        String langCode = supportedCode == null ? DEFAULT_LANGUAGE : supportedCode;
        if (!langCode.equals(configured)) {
            if (supportedCode == null && configured != null && !configured.isBlank()) {
                plugin.getLogger().warning("Unknown settings.language '" + configured + "'. Using '" + langCode + "'.");
            }
            config.set("settings.language", langCode);
        }

        YamlConfiguration english = loadBundledYaml("lang/" + DEFAULT_LANGUAGE + ".yml");
        YamlConfiguration selected = DEFAULT_LANGUAGE.equals(langCode) ? new YamlConfiguration() : loadBundledYaml("lang/" + langCode + ".yml");
        YamlConfiguration custom = new YamlConfiguration();
        Path file = languageFile(langCode);
        if (Files.exists(file)) {
            try {
                custom = ConfigFileStore.read(file);
            } catch (IOException | InvalidConfigurationException failure) {
                plugin.getLogger().warning("lang/" + langCode + ".yml is invalid and was ignored: " + failure.getMessage());
            }
        }
        lang = LanguageConfigLoader.merge(english, selected, custom);
    }

    private Path languageFile(String code) {
        return plugin.getDataFolder().toPath().resolve("lang").resolve(code + ".yml");
    }

    private String readBundledText(String resource) {
        try (InputStream input = plugin.getResource(resource)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return null;
        }
    }

    private YamlConfiguration loadBundledYaml(String resource) {
        String text = readBundledText(resource);
        if (text == null) {
            return new YamlConfiguration();
        }
        try {
            return ConfigFileStore.parse(text);
        } catch (InvalidConfigurationException failure) {
            plugin.getLogger().severe("Bundled resource " + resource + " is invalid: " + failure.getMessage());
            return new YamlConfiguration();
        }
    }

    public static String normalizeLanguageCode(String rawCode) {
        String normalized = normalizeSupportedLanguageCode(rawCode);
        return normalized == null ? DEFAULT_LANGUAGE : normalized;
    }

    public static String normalizeSupportedLanguageCode(String rawCode) {
        return LanguageFiles.normalize(rawCode);
    }

    /** Replaces the active configuration (used by the web editor after validation). */
    public void replaceRuntimeConfig(FileConfiguration config) {
        if (bundledDefaults != null && config.getDefaults() == null) {
            config.setDefaults(bundledDefaults);
        }
        this.config = config;
    }

    /** The bundled default configuration, including comments. */
    public YamlConfiguration getBundledDefaults() {
        return bundledDefaults;
    }

    /** Result of the last configuration load or upgrade. */
    public ConfigMigrationReport getMigrationReport() {
        return migrationReport;
    }

    public boolean isAdminJoinNotificationEnabled() {
        return config.getBoolean("settings.notify-admins-on-join", true);
    }

    public boolean isPauseWhenEmptyEnabled() {
        return config.getBoolean("settings.pause-when-empty", false);
    }

    public boolean areRecordsEnabled() {
        return config.getBoolean("records.enabled", true);
    }

    public boolean areSplitAnnouncementsEnabled() {
        return areRecordsEnabled() && config.getBoolean("records.announce-splits", true);
    }

    public boolean areConsoleRewardCommandsEditableOnWeb() {
        return config.getBoolean("web-config.allow-console-reward-commands", false);
    }

    /**
     * Gets a raw message string from the language file.
     *
     * @param path The path to the message in the lang file.
     * @return The message string.
     */
    public String getMessage(String path) {
        return lang.getString(path, "Missing translation: " + path);
    }

    /**
     * Gets a raw message string from the language file with a default fallback.
     *
     * @param key The path to the message.
     * @param defaultValue The value to return if the path is not found.
     * @return The message string.
     */
    public String getLangString(String key, String defaultValue) {
        return lang.getString(key, defaultValue);
    }

    public List<String> getFormattedTextList(String key) {
        return lang.getStringList(key).stream()
                .map(MessageUtil::toLegacy)
                .toList();
    }

    /**
     * Gets a fully formatted message Component, including prefix, colors, and placeholder replacements.
     *
     * @param key The path to the message in the lang file.
     * @param replacements A list of key-value pairs for placeholders (e.g., "%player%", "Steve").
     * @return The formatted message as a Component.
     */
    public Component getFormatted(String key, String... replacements) {
        String message = getMessage(key);
        // Combine prefix and message, then apply color codes.
        String rawMessage = getMessage("prefix") + message;

        for (int i = 0; i < replacements.length; i += 2) {
            rawMessage = rawMessage.replace(replacements[i], replacements[i + 1]);
        }

        return MessageUtil.parse(rawMessage);
    }

    /**
     * Gets a formatted string with color codes, primarily for use in legacy contexts like scoreboards.
     *
     * @param key The path to the message.
     * @param replacements A list of key-value pairs for placeholders.
     * @return The formatted message as a String with section symbols (§).
     */
    public String getFormattedText(String key, String... replacements) {
        String message = getMessage(key);

        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        return MessageUtil.toLegacy(message);
    }

    /**
     * Executes reward commands defined in the config.
     * Can execute console commands or special "api:" commands for sounds and particles.
     *
     * @param key The sub-key under "rewards" in config.yml.
     * @param player The target player, or null to target all online players.
     */
    public void executeRewardCommands(String key, @Nullable Player player) {
        RewardUtil.executeRewardCommands(plugin, key, player);
    }

    // =========================================================================================
    // Configuration Getters
    // =========================================================================================
    public String getFormattedString(String key, String... replacements) {
        return MessageUtil.toLegacy(getFormatted(key, replacements));
    }

    public int getPortalSearchTimeout() {
        return config.getInt("settings.proximity-scanner.portal-search-timeout", 15);
    }

    /** @return The raw FileConfiguration object for config.yml. */
    public FileConfiguration getRawConfig() { return config; }

    /** @return Whether the timer should start automatically when the first player joins. */
    public boolean isStartOnFirstJoin() {
        return config.getBoolean("settings.start-on-first-join", true);
    }

    public boolean isResetTimeOnJoinEnabled() {
        return config.getBoolean("settings.reset_time_on_join", true);
    }

    /** @return The time limit in seconds for the village search task. */
    public long getVillageTimeout() {
        return config.getLong("settings.village-search-timeout", 600);
    }

    /** @return The currently configured resource tracking mode. */
    public TrackingMode getTrackingMode() {
        return TrackingMode.valueOf(config.getString("settings.task-tracking-mode", "INVENTORY").toUpperCase());
    }

    public TaskDisplayMode getTaskDisplayMode() {
        String rawMode = config.getString("progression.settings.task-display-mode", "ALL_GAME_STAGES");
        TaskDisplayMode mode = parseTaskDisplayMode(rawMode, null);
        if (mode == null) {
            plugin.getLogger().warning("Unknown progression.settings.task-display-mode '" + rawMode + "'. Falling back to ALL_GAME_STAGES.");
            return TaskDisplayMode.ALL_GAME_STAGES;
        }
        return mode;
    }

    static TaskDisplayMode parseTaskDisplayMode(String rawMode, TaskDisplayMode fallback) {
        try {
            return TaskDisplayMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return fallback;
        }
    }

    public boolean isCompletedTaskHideEnabled() {
        return config.getBoolean("progression.settings.completed-task-hide.enabled", true);
    }

    public long getCompletedTaskHideTimeoutMillis() {
        int seconds = Math.max(0, config.getInt("progression.settings.completed-task-hide.timeout-seconds", 10));
        return seconds * 1000L;
    }

    /** @return Whether resource requirements for tasks should scale with the player count. */
    public boolean isPlayerScalingEnabled() {
        return config.getBoolean("settings.scale-resources-by-playercount.enabled", true);
    }

    /** @return The multiplier used for player-based resource scaling. */
    public double getPlayerScalingMultiplier() {
        return config.getDouble("settings.scale-resources-by-playercount.multiplier", 0.5);
    }

    /** @return Whether to create log files for each speedrun attempt. */
    public boolean isLogAttemptsEnabled() {
        return config.getBoolean("settings.log-attempts", true);
    }

    public boolean isChunkBiomeLoggingEnabled() {
        return config.getBoolean("settings.chunk-biome-logging.enabled", true);
    }

    public boolean isPlayerBlockLoggingEnabled() {
        return config.getBoolean("tracking.player-blocks.enabled", false);
    }

    public boolean isTraceEnabled() {
        return config.getBoolean("diagnostics.trace.enabled", false);
    }

    public boolean isTraceMirrorToConsoleEnabled() {
        return config.getBoolean("diagnostics.trace.mirror-to-console", false);
    }

    public List<String> getTraceCategories() {
        List<String> categories = config.getStringList("diagnostics.trace.categories");
        return categories.isEmpty() ? List.of("ALL") : categories;
    }

    public boolean isStartPreScanEnabled() {
        return isCasualGameModeEnabled()
                && config.getBoolean("casual.start-pre-scan.enabled",
                config.getBoolean("settings.start-pre-scan.enabled", false));
    }

    public StartPreScanMode getStartPreScanMode() {
        String rawMode = config.getString("casual.start-pre-scan.mode", "BALANCED");
        StartPreScanMode mode = parseStartPreScanMode(rawMode, null);
        if (mode == null) {
            plugin.getLogger().warning("Unknown casual.start-pre-scan.mode '" + rawMode + "'. Falling back to BALANCED.");
            return StartPreScanMode.BALANCED;
        }
        return mode;
    }

    static StartPreScanMode parseStartPreScanMode(String rawMode, StartPreScanMode fallback) {
        try {
            return StartPreScanMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return fallback;
        }
    }

    public int getStartPreScanRadius() {
        return Math.max(1, config.getInt("casual.start-pre-scan.radius",
                config.getInt("settings.start-pre-scan.radius", 1000)));
    }

    public int getStartPreScanRadiusChunks() {
        int configuredBlocks = getStartPreScanRadius();
        int chunks = Math.max(1, (int) Math.ceil(configuredBlocks / 16.0));
        int maxChunks = Math.max(1, config.getInt("casual.start-pre-scan.max-radius-chunks", 64));
        return Math.min(chunks, maxChunks);
    }

    public boolean isStartPreScanLavaEnabled() {
        return config.getBoolean("casual.start-pre-scan.include-lava-pool", true);
    }

    public int getStartPreScanLavaScanBelowBlocks() {
        return Math.max(0, config.getInt("casual.start-pre-scan.lava-pool-vertical-scan.below-player", 12));
    }

    public int getStartPreScanLavaScanAboveBlocks() {
        return Math.max(0, config.getInt("casual.start-pre-scan.lava-pool-vertical-scan.above-player", 32));
    }

    public int getLoadedChunkScanRadius() {
        return Math.max(0, config.getInt("casual.start-pre-scan.loaded-chunk-radius", 10));
    }

    private String getStartPreScanProfilePath(String key) {
        return "casual.start-pre-scan.profiles." + getStartPreScanMode().name() + "." + key;
    }

    public int getStartPreScanChunksPerRun() {
        int fallback = defaultStartPreScanChunksPerRun(getStartPreScanMode());
        int configured = Math.max(0, config.getInt(getStartPreScanProfilePath("chunks-per-run"), fallback));
        return Math.min(configured, getStartPreScanSafetyMaxChunksPerRun());
    }

    public long getStartPreScanPeriodTicks() {
        int fallback = defaultStartPreScanPeriodTicks(getStartPreScanMode());
        return Math.max(1, config.getInt(getStartPreScanProfilePath("period-ticks"), fallback));
    }

    public int getStartPreScanMaxQueuedChunks() {
        int fallback = defaultStartPreScanMaxQueuedChunks(getStartPreScanMode());
        int configured = Math.max(0, config.getInt(getStartPreScanProfilePath("max-queued-chunks"), fallback));
        return Math.min(configured, getStartPreScanSafetyMaxQueuedChunks());
    }

    static int defaultStartPreScanChunksPerRun(StartPreScanMode mode) {
        return switch (mode) {
            case SAFE -> 0;
            case BALANCED -> 2;
            case AGGRESSIVE -> 8;
            case LOCATE -> 0;
        };
    }

    static int defaultStartPreScanPeriodTicks(StartPreScanMode mode) {
        return switch (mode) {
            case SAFE -> 40;
            case BALANCED -> 20;
            case AGGRESSIVE -> 10;
            case LOCATE -> 40;
        };
    }

    static int defaultStartPreScanMaxQueuedChunks(StartPreScanMode mode) {
        return switch (mode) {
            case SAFE -> 0;
            case BALANCED -> 1500;
            case AGGRESSIVE -> 5000;
            case LOCATE -> 0;
        };
    }

    public boolean shouldStartPreScanLoadMissingChunks() {
        boolean fallback = getStartPreScanMode() != StartPreScanMode.SAFE
                && getStartPreScanMode() != StartPreScanMode.LOCATE;
        return config.getBoolean(getStartPreScanProfilePath("load-missing-chunks"), fallback);
    }

    public boolean shouldStartPreScanQueueSpawn() {
        boolean fallback = getStartPreScanMode() != StartPreScanMode.SAFE
                && getStartPreScanMode() != StartPreScanMode.LOCATE;
        return config.getBoolean(getStartPreScanProfilePath("scan-spawn"), fallback);
    }

    public boolean shouldStartPreScanQueuePlayers() {
        boolean fallback = getStartPreScanMode() != StartPreScanMode.SAFE
                && getStartPreScanMode() != StartPreScanMode.LOCATE;
        return config.getBoolean(getStartPreScanProfilePath("scan-players"), fallback);
    }

    public boolean shouldStartPreScanIncludeNether() {
        return config.getBoolean(getStartPreScanProfilePath("include-nether"),
                getStartPreScanMode() == StartPreScanMode.AGGRESSIVE);
    }

    public int getStartPreScanSafetyMaxChunksPerRun() {
        return Math.max(1, config.getInt("casual.start-pre-scan.safety.max-chunks-per-run", 8));
    }

    public int getStartPreScanSafetyMaxQueuedChunks() {
        return Math.max(0, config.getInt("casual.start-pre-scan.safety.max-queued-chunks", 5000));
    }

    public int getStartPreScanLocateRadius() {
        return Math.max(1, config.getInt("casual.start-pre-scan.locate.radius-chunks",
                config.getInt("casual.start-pre-scan.locate.radius", 96)));
    }

    public long getStartPreScanLocatePeriodTicks() {
        return Math.max(1, config.getInt("casual.start-pre-scan.locate.period-ticks", 20));
    }

    public int getStartPreScanLocateCallsPerRun() {
        return Math.max(1, config.getInt("casual.start-pre-scan.locate.calls-per-run", 1));
    }

    public boolean shouldStartPreScanLocateFindUnexplored() {
        return config.getBoolean("casual.start-pre-scan.locate.find-unexplored", false);
    }

    public boolean shouldStartPreScanLocateVillages() {
        return config.getBoolean("casual.start-pre-scan.locate.targets.village", true);
    }

    public boolean shouldStartPreScanLocateStronghold() {
        return config.getBoolean("casual.start-pre-scan.locate.targets.stronghold", true);
    }

    public boolean shouldStartPreScanLocateNetherStructures() {
        return config.getBoolean("casual.start-pre-scan.locate.targets.nether-structures", true);
    }

    /** Allows BALANCED/AGGRESSIVE pre-scan to load missing chunks on servers without async chunk loading. */
    public boolean isStartPreScanSyncChunkLoadingAllowed() {
        return config.getBoolean("casual.start-pre-scan.allow-sync-chunk-loading", false);
    }

    /** Hides the red sidebar score numbers on servers that support it (Paper). */
    public boolean isScoreNumbersHidden() {
        return config.getBoolean("settings.scoreboard.hide-score-numbers", true);
    }

    public boolean isDeathLocationCompassEnabled() {
        return isCasualGameModeEnabled()
                && config.getBoolean("casual.compass.death-location.enabled", true);
    }

    public CoordinateDisplayMode getCoordinateDisplayMode() {
        String rawMode = config.getString("settings.coordinate-display.mode", "CONDITIONAL");
        CoordinateDisplayMode mode = parseCoordinateDisplayMode(rawMode, null);
        if (mode == null) {
            plugin.getLogger().warning("Unknown settings.coordinate-display.mode '" + rawMode + "'. Falling back to CONDITIONAL.");
            return CoordinateDisplayMode.CONDITIONAL;
        }
        return mode;
    }

    static CoordinateDisplayMode parseCoordinateDisplayMode(String rawMode, CoordinateDisplayMode fallback) {
        try {
            return CoordinateDisplayMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return fallback;
        }
    }

    /** @return The radius for detecting a lava pool. */
    public int getLavaPoolRadius() {
        return config.getInt("settings.proximity-scanner.lava-pool.radius", 16);
    }

    /** @return The minimum number of lava source blocks to qualify as a "pool". */
    public int getLavaPoolRequiredSources() {
        return config.getInt("settings.proximity-scanner.lava-pool.required-source-blocks", 12);
    }

    /** @return The radius for detecting a village by scanning for a bell. */
    public int getVillageBellRadius() {
        return config.getInt("settings.proximity-scanner.village.radius", 32);
    }

    /** @return Whether admins can reassign structure locations using commands. */
    public boolean isReassigningLocationsEnabled() {
        return config.getBoolean("settings.allow-reassigning-locations", true);
    }

    /** @return The radius around a lit block to check for a Nether Portal frame. */
    public int getNetherPortalCheckRadius() {
        return config.getInt("settings.proximity-scanner.nether-portal.check-radius", 4);
    }

    /** @return The search radius to find the precise portal block after a player teleports. */
    public int getTeleportPortalSearchRadius() {
        return config.getInt("settings.proximity-scanner.nether-portal.search-radius", 90);
    }

    /** @return Whether waypoints should be created for found structures in casual mode. */
    public boolean areWaypointsEnabled() {
        return isCasualGameModeEnabled() && config.getBoolean("casual.structure_waypoints.enabled", true);
    }

    public WaypointType getWaypointType() {
        String rawType = config.getString("casual.structure_waypoints.type", "END_GATEWAY");
        try {
            return WaypointType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown casual.structure_waypoints.type '" + rawType + "'. Falling back to END_GATEWAY.");
            return WaypointType.END_GATEWAY;
        }
    }

    public int getWaypointOffsetBlocks() {
        return Math.max(0, config.getInt("casual.structure_waypoints.offset-blocks", 10));
    }

    public long getWaypointUpdateIntervalTicks() {
        int seconds = Math.max(1, config.getInt("casual.structure_waypoints.update-interval-seconds", 10));
        return seconds * 20L;
    }

    public boolean isNetherGoldHighlightEnabled() {
        return isCasualGameModeEnabled() && config.getBoolean("casual.nether_gold_highlight.enabled", true);
    }

    public boolean isNetherReferenceDestinationEnabled() {
        return isCasualGameModeEnabled() && config.getBoolean("casual.compass.show-nether-reference", false);
    }

    public int getNetherGoldHighlightRadius() {
        return Math.max(1, config.getInt("casual.nether_gold_highlight.radius", 24));
    }

    public GameMode getGameMode() {
        String rawMode = config.getString("settings.gamemode", "NORMAL");
        try {
            return GameMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown settings.gamemode '" + rawMode + "'. Falling back to NORMAL.");
            return GameMode.NORMAL;
        }
    }

    public boolean isCasualGameModeEnabled() {
        return getGameMode() == GameMode.CASUAL && config.getBoolean("casual.enabled", true);
    }

    public boolean isHardcoreModeEnabled() {
        return getGameMode() == GameMode.HARDCORE;
    }
}
