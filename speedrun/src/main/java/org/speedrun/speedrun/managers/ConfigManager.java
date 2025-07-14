package org.speedrun.speedrun.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.speedrun.speedrun.Speedrun;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

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

        // Deserialize the string with ampersand color codes into a Component.
        // Десеріалізуємо рядок з кодами кольорів (&) у Component.
        return LegacyComponentSerializer.legacyAmpersand().deserialize(rawMessage);
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
        // First, deserialize from '&' format, then serialize to '§' format.
        // Спочатку десеріалізуємо з формату '&', потім серіалізуємо у формат '§'.
        return LegacyComponentSerializer.legacySection().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(message));
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
        if (!config.getBoolean("rewards.enabled", false)) return;

        List<String> commands = config.getStringList("rewards." + key);
        if (commands.isEmpty()) return;

        // Determine the target(s) for the commands.
        // Визначаємо ціль(і) для команд.
        List<Player> targets = (player != null) ? List.of(player) : new ArrayList<>(Bukkit.getOnlinePlayers());
        if (targets.isEmpty()) return;

        for (Player target : targets) {
            for (String rawCmd : commands) {
                if (rawCmd.startsWith("api:")) {
                    // Handle special API commands like sound or particle effects.
                    // Обробка спеціальних API-команд, як-от звуки чи партикли.
                    try {
                        String[] parts = rawCmd.substring(4).split(" ");
                        String apiType = parts[0].toLowerCase();

                        switch (apiType) {
                            case "sound" -> {
                                Sound sound = Registry.SOUNDS.get(Objects.requireNonNull(NamespacedKey.fromString(parts[1].toLowerCase())));
                                if (sound == null) continue;
                                float volume = (parts.length > 2) ? Float.parseFloat(parts[2]) : 1.0f;
                                float pitch = (parts.length > 3) ? Float.parseFloat(parts[3]) : 1.0f;
                                target.playSound(target.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
                            }
                            case "particle" -> {
                                Particle particle = Registry.PARTICLE_TYPE.get(Objects.requireNonNull(NamespacedKey.fromString(parts[1].toLowerCase())));
                                if (particle == null) continue;
                                int count = (parts.length > 2) ? Integer.parseInt(parts[2]) : 1;
                                double offsetX = (parts.length > 3) ? Double.parseDouble(parts[3]) : 0.5;
                                double offsetY = (parts.length > 4) ? Double.parseDouble(parts[4]) : 0.5;
                                double offsetZ = (parts.length > 5) ? Double.parseDouble(parts[5]) : 0.5;
                                double speed = (parts.length > 6) ? Double.parseDouble(parts[6]) : 0;
                                target.spawnParticle(particle, target.getLocation().add(0, 1.5, 0), count, offsetX, offsetY, offsetZ, speed);
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Error executing API reward command: " + rawCmd, e);
                    }
                } else {
                    // Execute a standard console command.
                    // Виконання стандартної консольної команди.
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCmd.replace("%player%", target.getName()));
                }
            }
        }
    }

    // =========================================================================================
    // Configuration Getters
    // =========================================================================================

    /** @return The raw FileConfiguration object for config.yml. / "Сирий" об'єкт FileConfiguration для config.yml. */
    public FileConfiguration getRawConfig() { return config; }

    /** @return Whether the timer should start automatically when the first player joins. / Чи має таймер запускатися автоматично при вході першого гравця. */
    public boolean isStartOnFirstJoin() {
        return config.getBoolean("settings.start-on-first-join", true);
    }

    /** @return The time limit in seconds for the village search task. / Ліміт часу в секундах для завдання з пошуку села. */
    public long getVillageTimeout() {
        return config.getLong("settings.village-search-timeout", 600);
    }

    /** @return The currently configured resource tracking mode. / Поточний налаштований режим відстеження ресурсів. */
    public TrackingMode getTrackingMode() {
        return TrackingMode.valueOf(config.getString("settings.task-tracking-mode", "INVENTORY").toUpperCase());
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
        return config.getBoolean("casual.structure_waypoints.enabled", true);
    }

    public boolean isCasualGameModeEnabled() {return config.getBoolean("casual.enabled", true);}
}