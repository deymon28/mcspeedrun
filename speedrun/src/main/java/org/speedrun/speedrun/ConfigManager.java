package org.speedrun.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

public class ConfigManager {
    private final Speedrun plugin;
    private FileConfiguration config;
    private FileConfiguration lang;
    private File langFile;

    public enum TrackingMode {INVENTORY, CUMULATIVE}

    public ConfigManager(Speedrun plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        String langCode = config.getString("settings.language", "en");
        langFile = new File(plugin.getDataFolder(), "lang/" + langCode + ".yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + langCode + ".yml", false);
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    public String getMessage(String path) {
        return lang.getString(path, "Missing translation: " + path);
    }

    public Component getFormatted(String key, String... replacements) {
        String message = getMessage(key);
        Component formattedComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(getMessage("prefix") + message);
        String processedMessage = LegacyComponentSerializer.legacyAmpersand().serialize(formattedComponent);
        for (int i = 0; i < replacements.length; i += 2) {
            processedMessage = processedMessage.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(processedMessage);
    }

    public Component getFormatted(String key) {
        return getFormatted(key, new String[]{});
    }

    public String getFormattedText(String key, String... replacements) {
        String message = getMessage(key);
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(message);
        String processedMessage = LegacyComponentSerializer.legacyAmpersand().serialize(component);
        for (int i = 0; i < replacements.length; i += 2) {
            processedMessage = processedMessage.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(processedMessage));
    }

    /**
     * Executes reward commands from the config.
     * - If the command uses vanilla selectors (@p, @a), it's run from the console.
     * - If the command has %player% and a player is provided, it's replaced and run.
     * - If the command has %player% but no player is provided (e.g., team task completion),
     * the command is run for ALL online players.
     * @param key The config key for the commands (e.g., "on-task-complete").
     * @param player The specific player who triggered the reward, or null if context is unknown.
     */
    public void executeRewardCommands(String key, @Nullable Player player) {
        if (!config.getBoolean("rewards.enabled", false)) return;
        List<String> commands = config.getStringList("rewards." + key);

        for (String cmd : commands) {
            // Case 1: Command has %player% placeholder
            if (cmd.contains("%player%")) {
                if (player != null) {
                    // Specific player is known, replace and dispatch.
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                } else {
                    // No specific player, so execute for every player online.
                    // This handles collective achievements.
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
                    }
                }
            } else {
                // Case 2: Command does not have %player% (e.g., uses @a, @p, or is general)
                // Dispatch from console as is.
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
    }

    public FileConfiguration getRawConfig() { return config; }
    public boolean isStartOnFirstJoin() { return config.getBoolean("settings.start-on-first-join", true); }
    public long getVillageTimeout() { return config.getLong("settings.village-search-timeout", 600); }
    public TrackingMode getTrackingMode() { return TrackingMode.valueOf(config.getString("settings.task-tracking-mode", "INVENTORY").toUpperCase()); }
    public boolean isPlayerScalingEnabled() { return config.getBoolean("settings.scale-resources-by-playercount.enabled", true); }
    public double getPlayerScalingMultiplier() { return config.getDouble("settings.scale-resources-by-playercount.multiplier", 0.5); }
    public boolean isLogAttemptsEnabled() { return config.getBoolean("settings.log-attempts", true); }
}