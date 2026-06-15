package org.speedrun.speedrun.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.utils.RewardUtil;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Manages plugin configurations.
 * Handles loading and provides access to `config.yml` and language files.
 * |
 * Керує конфігураціями плагіна.
 * Обробляє завантаження та надає доступ до `config.yml` та мовних файлів.
 */
public class ConfigManager {

    private final Speedrun plugin;
    private FileConfiguration config;
    private FileConfiguration lang;

    /**
     * Defines how player-collected resources are tracked for tasks.
     * Визначає, як відстежуються зібрані гравцями ресурси для завдань.
     */
    public enum TrackingMode {
        /** Count only items currently in a player's inventory. / Рахувати лише предмети, що є в інвентарі гравця. */
        INVENTORY,
        /** Count all items ever collected by players during the run. / Рахувати всі предмети, зібрані гравцями протягом гри. */
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
        AGGRESSIVE
    }

    public ConfigManager(Speedrun plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Reloads all configuration files from disk.
     * Saves the default config if it doesn't exist and loads the appropriate language file.
     * |
     * Перезавантажує всі файли конфігурації з диска.
     * Зберігає стандартний конфіг, якщо він не існує, та завантажує відповідний мовний файл.
     */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        String langCode = config.getString("settings.language", "en");
        File langFile = new File(plugin.getDataFolder(), "lang/" + langCode + ".yml");

        if (!langFile.exists()) {
            // If the specified lang file doesn't exist, save it from the JAR's resources.
            // Якщо вказаний мовний файл не існує, зберігаємо його з ресурсів JAR-файлу.
            plugin.saveResource("lang/" + langCode + ".yml", false);
        }

        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    /**
     * Gets a raw message string from the language file.
     * Отримує "сирий" рядок повідомлення з мовного файлу.
     *
     * @param path The path to the message in the lang file. / Шлях до повідомлення у мовному файлі.
     * @return The message string. / Рядок повідомлення.
     */
    public String getMessage(String path) {
        return lang.getString(path, "Missing translation: " + path);
    }

    /**
     * Gets a raw message string from the language file with a default fallback.
     * Отримує "сирий" рядок повідомлення з мовного файлу з резервним значенням.
     *
     * @param key The path to the message. / Шлях до повідомлення.
     * @param defaultValue The value to return if the path is not found. / Значення, що повертається, якщо шлях не знайдено.
     * @return The message string. / Рядок повідомлення.
     */
    public String getLangString(String key, String defaultValue) {
        return lang.getString(key, defaultValue);
    }

    public List<String> getFormattedTextList(String key) {
        return lang.getStringList(key).stream()
                .map(line -> LegacyComponentSerializer.legacySection().serialize(
                        MessageUtil.parse(line)))
                .toList();
    }

    /**
     * Gets a fully formatted message Component, including prefix, colors, and placeholder replacements.
     * Отримує повністю відформатований `Component` повідомлення, включаючи префікс, кольори та заміну плейсхолдерів.
     *
     * @param key The path to the message in the lang file. / Шлях до повідомлення у мовному файлі.
     * @param replacements A list of key-value pairs for placeholders (e.g., "%player%", "Steve"). / Список пар ключ-значення для плейсхолдерів (напр., "%player%", "Steve").
     * @return The formatted message as a Component. / Відформатоване повідомлення як Component.
     */
    public Component getFormatted(String key, String... replacements) {
        String message = getMessage(key);
        // Combine prefix and message, then apply color codes.
        // Поєднуємо префікс та повідомлення, а потім застосовуємо коди кольорів.
        String rawMessage = getMessage("prefix") + message;

        for (int i = 0; i < replacements.length; i += 2) {
            rawMessage = rawMessage.replace(replacements[i], replacements[i + 1]);
        }

        return MessageUtil.parse(rawMessage);
    }

    /**
     * Gets a formatted string with color codes, primarily for use in legacy contexts like scoreboards.
     * Отримує відформатований рядок з кодами кольорів, переважно для використання в застарілих контекстах, як-от скорборди.
     *
     * @param key The path to the message. / Шлях до повідомлення.
     * @param replacements A list of key-value pairs for placeholders. / Список пар ключ-значення для плейсхолдерів.
     * @return The formatted message as a String with section symbols (§). / Відформатоване повідомлення як String із символами секцій (§).
     */
    public String getFormattedText(String key, String... replacements) {
        String message = getMessage(key);

        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacySection().serialize(
                MessageUtil.parse(message));
    }

    /**
     * Executes reward commands defined in the config.
     * Can execute console commands or special "api:" commands for sounds and particles.
     * |
     * Виконує команди-винагороди, визначені в конфігурації.
     * Може виконувати консольні команди або спеціальні команди "api:" для звуків та партиклів.
     *
     * @param key The sub-key under "rewards" in config.yml. / Підключ у секції "rewards" в config.yml.
     * @param player The target player, or null to target all online players. / Цільовий гравець, або null для всіх гравців онлайн.
     */
    public void executeRewardCommands(String key, @Nullable Player player) {
        RewardUtil.executeRewardCommands(plugin, key, player);
    }

    // =========================================================================================
    // Configuration Getters
    // =========================================================================================
    public String getFormattedString(String key, String... replacements) {
        Component component = getFormatted(key, replacements);
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public int getPortalSearchTimeout() {
        return config.getInt("settings.proximity-scanner.portal-search-timeout", 15);
    }

    /** @return The raw FileConfiguration object for config.yml. / "Сирий" об'єкт FileConfiguration для config.yml. */
    public FileConfiguration getRawConfig() { return config; }

    /** @return Whether the timer should start automatically when the first player joins. / Чи має таймер запускатися автоматично при вході першого гравця. */
    public boolean isStartOnFirstJoin() {
        return config.getBoolean("settings.start-on-first-join", true);
    }

    public boolean isResetTimeOnJoinEnabled() {
        return config.getBoolean("settings.reset_time_on_join", false);
    }

    /** @return The time limit in seconds for the village search task. / Ліміт часу в секундах для завдання з пошуку села. */
    public long getVillageTimeout() {
        return config.getLong("settings.village-search-timeout", 600);
    }

    /** @return The currently configured resource tracking mode. / Поточний налаштований режим відстеження ресурсів. */
    public TrackingMode getTrackingMode() {
        return TrackingMode.valueOf(config.getString("settings.task-tracking-mode", "INVENTORY").toUpperCase());
    }

    public TaskDisplayMode getTaskDisplayMode() {
        String rawMode = config.getString("progression.settings.task-display-mode", "ACTIVE_STAGE");
        try {
            return TaskDisplayMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown progression.settings.task-display-mode '" + rawMode + "'. Falling back to ACTIVE_STAGE.");
            return TaskDisplayMode.ACTIVE_STAGE;
        }
    }

    public boolean isCompletedTaskHideEnabled() {
        return config.getBoolean("progression.settings.completed-task-hide.enabled", false);
    }

    public long getCompletedTaskHideTimeoutMillis() {
        int seconds = Math.max(0, config.getInt("progression.settings.completed-task-hide.timeout-seconds", 10));
        return seconds * 1000L;
    }

    /** @return Whether resource requirements for tasks should scale with the player count. / Чи повинні вимоги до ресурсів для завдань масштабуватися з кількістю гравців. */
    public boolean isPlayerScalingEnabled() {
        return config.getBoolean("settings.scale-resources-by-playercount.enabled", true);
    }

    /** @return The multiplier used for player-based resource scaling. / Множник, що використовується для масштабування ресурсів залежно від гравців. */
    public double getPlayerScalingMultiplier() {
        return config.getDouble("settings.scale-resources-by-playercount.multiplier", 0.5);
    }

    /** @return Whether to create log files for each speedrun attempt. / Чи створювати файли логів для кожної спроби спідрану. */
    public boolean isLogAttemptsEnabled() {
        return config.getBoolean("settings.log-attempts", true);
    }

    public boolean isChunkBiomeLoggingEnabled() {
        return config.getBoolean("settings.chunk-biome-logging.enabled", true);
    }

    public boolean isPlayerBlockLoggingEnabled() {
        return config.getBoolean("tracking.player-blocks.enabled", false);
    }

    public boolean isStartPreScanEnabled() {
        return isCasualGameModeEnabled()
                && config.getBoolean("casual.start-pre-scan.enabled",
                config.getBoolean("settings.start-pre-scan.enabled", false));
    }

    public StartPreScanMode getStartPreScanMode() {
        String rawMode = config.getString("casual.start-pre-scan.mode", "BALANCED");
        try {
            return StartPreScanMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown casual.start-pre-scan.mode '" + rawMode + "'. Falling back to BALANCED.");
            return StartPreScanMode.BALANCED;
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

    public int getLoadedChunkScanRadius() {
        return Math.max(0, config.getInt("casual.start-pre-scan.loaded-chunk-radius", 10));
    }

    private String getStartPreScanProfilePath(String key) {
        return "casual.start-pre-scan.profiles." + getStartPreScanMode().name() + "." + key;
    }

    public int getStartPreScanChunksPerRun() {
        int fallback = switch (getStartPreScanMode()) {
            case SAFE -> 0;
            case BALANCED -> 2;
            case AGGRESSIVE -> 8;
        };
        return Math.max(0, config.getInt(getStartPreScanProfilePath("chunks-per-run"), fallback));
    }

    public long getStartPreScanPeriodTicks() {
        int fallback = switch (getStartPreScanMode()) {
            case SAFE -> 40;
            case BALANCED -> 20;
            case AGGRESSIVE -> 10;
        };
        return Math.max(1, config.getInt(getStartPreScanProfilePath("period-ticks"), fallback));
    }

    public int getStartPreScanMaxQueuedChunks() {
        int fallback = switch (getStartPreScanMode()) {
            case SAFE -> 0;
            case BALANCED -> 1500;
            case AGGRESSIVE -> 5000;
        };
        return Math.max(0, config.getInt(getStartPreScanProfilePath("max-queued-chunks"), fallback));
    }

    public boolean shouldStartPreScanLoadMissingChunks() {
        boolean fallback = getStartPreScanMode() != StartPreScanMode.SAFE;
        return config.getBoolean(getStartPreScanProfilePath("load-missing-chunks"), fallback);
    }

    public boolean shouldStartPreScanQueueSpawn() {
        return config.getBoolean(getStartPreScanProfilePath("scan-spawn"), getStartPreScanMode() != StartPreScanMode.SAFE);
    }

    public boolean shouldStartPreScanQueuePlayers() {
        return config.getBoolean(getStartPreScanProfilePath("scan-players"), getStartPreScanMode() != StartPreScanMode.SAFE);
    }

    public boolean shouldStartPreScanIncludeNether() {
        return config.getBoolean(getStartPreScanProfilePath("include-nether"), getStartPreScanMode() == StartPreScanMode.AGGRESSIVE);
    }

    public boolean isDeathLocationCompassEnabled() {
        return isCasualGameModeEnabled()
                && config.getBoolean("casual.compass.death-location.enabled", true);
    }

    public CoordinateDisplayMode getCoordinateDisplayMode() {
        String rawMode = config.getString("settings.coordinate-display.mode", "CONDITIONAL");
        try {
            return CoordinateDisplayMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown settings.coordinate-display.mode '" + rawMode + "'. Falling back to CONDITIONAL.");
            return CoordinateDisplayMode.CONDITIONAL;
        }
    }

    /** @return The radius for detecting a lava pool. / Радіус для виявлення озера лави. */
    public int getLavaPoolRadius() {
        return config.getInt("settings.proximity-scanner.lava-pool.radius", 16);
    }

    /** @return The minimum number of lava source blocks to qualify as a "pool". / Мінімальна кількість блоків-джерел лави, щоб вважатися "озером". */
    public int getLavaPoolRequiredSources() {
        return config.getInt("settings.proximity-scanner.lava-pool.required-source-blocks", 12);
    }

    /** @return The radius for detecting a village by scanning for a bell. / Радіус для виявлення села шляхом сканування дзвона. */
    public int getVillageBellRadius() {
        return config.getInt("settings.proximity-scanner.village.radius", 32);
    }

    /** @return Whether admins can reassign structure locations using commands. / Чи можуть адміністратори перепризначати розташування структур за допомогою команд. */
    public boolean isReassigningLocationsEnabled() {
        return config.getBoolean("settings.allow-reassigning-locations", true);
    }

    /** @return The radius around a lit block to check for a Nether Portal frame. / Радіус навколо запаленого блоку для перевірки наявності рамки порталу в Незер. */
    public int getNetherPortalCheckRadius() {
        return config.getInt("settings.proximity-scanner.nether-portal.check-radius", 4);
    }

    /** @return The search radius to find the precise portal block after a player teleports. / Радіус пошуку для знаходження точного блоку порталу після телепортації гравця. */
    public int getTeleportPortalSearchRadius() {
        return config.getInt("settings.proximity-scanner.nether-portal.search-radius", 90);
    }

    /** @return Whether waypoints (beacons) should be created for found structures in casual mode. / Чи створювати вейпоінти (маяки) для знайдених структур у казуальному режимі. */
    public boolean areWaypointsEnabled() {
        return isCasualGameModeEnabled() && config.getBoolean("casual.structure_waypoints.enabled", true);
    }

    public WaypointType getWaypointType() {
        String rawType = config.getString("casual.structure_waypoints.type", "BEACON");
        try {
            return WaypointType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown casual.structure_waypoints.type '" + rawType + "'. Falling back to BEACON.");
            return WaypointType.BEACON;
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
