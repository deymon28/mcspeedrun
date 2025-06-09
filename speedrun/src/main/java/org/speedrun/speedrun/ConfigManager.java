package org.speedrun.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

// =========================================================================================
// Configuration Manager
// =========================================================================================
class ConfigManager {
    private final Speedrun plugin;
    private FileConfiguration config;
    private FileConfiguration lang;
    private File langFile;

    public enum TrackingMode { INVENTORY, CUMULATIVE }

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
        message = ChatColor.translateAlternateColorCodes('&', getMessage("prefix") + message);

        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i+1]);
        }
        return LegacyComponentSerializer.legacySection().deserialize(message);
    }

    public Component getFormatted(String key) {
        return getFormatted(key, new String[]{});
    }

    public String getFormattedText(String key, String... replacements) {
        String message = getMessage(key);
        message = ChatColor.translateAlternateColorCodes('&', message);
        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i+1]);
        }
        return message;
    }

    public void executeRewardCommands(String key, @Nullable Player player) {
        if (!config.getBoolean("rewards.enabled", false)) return;
        List<String> commands = config.getStringList("rewards." + key);
        for (String cmd : commands) {
            String processedCmd = cmd;
            if (player != null) {
                processedCmd = processedCmd.replace("%player%", player.getName());
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCmd);
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
