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

// =========================================================================================
// Configuration Manager
// =========================================================================================
@SuppressWarnings("FieldCanBeLocal") // Suppress warning for langFile as it's part of manager state.
public class ConfigManager {
    private final Speedrun plugin;
    private FileConfiguration config;
    private FileConfiguration lang;
    private File langFile; // Keeping as a field as it's part of the manager's state for file management.

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
        // Get the raw message from the language file.
        String message = getMessage(key);
        // Prepend the prefix and translate legacy color codes using '&' character.
        Component formattedComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(getMessage("prefix") + message);

        // Apply replacements *after* deserializing to Component.
        // It's generally better to manipulate Components directly rather than string parsing,
        // but given the %placeholder% format, direct string replacement is simpler here.
        // However, replacements should ideally happen on the Component object itself for full Adventure benefits.
        // For now, we'll keep the string replacement for consistency with the original logic.
        String processedMessage = LegacyComponentSerializer.legacyAmpersand().serialize(formattedComponent); // Temporarily serialize to string for replacements
        for (int i = 0; i < replacements.length; i += 2) {
            processedMessage = processedMessage.replace(replacements[i], replacements[i+1]);
        }
        // Then deserialize back to Component, which is what getFormatted is expected to return.
        return LegacyComponentSerializer.legacyAmpersand().deserialize(processedMessage);
    }

    public Component getFormatted(String key) {
        return getFormatted(key, new String[]{});
    }

    public String getFormattedText(String key, String... replacements) {
        String message = getMessage(key);
        // Step 1: Deserialize the message (which uses '&' color codes) into an Adventure Component.
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(message);

        // Step 2: Apply replacements to the string representation of the component.
        // This is a pragmatic choice for simple %placeholder% replacements.
        String processedMessage = LegacyComponentSerializer.legacyAmpersand().serialize(component); // Temporarily serialize to string for replacements
        for (int i = 0; i < replacements.length; i += 2) {
            processedMessage = processedMessage.replace(replacements[i], replacements[i+1]);
        }

        // Step 3: Deserialize the processed string back into a Component,
        // THEN serialize it using LegacyComponentSerializer.legacySection()
        // to ensure it uses '§' (section sign) color codes.
        // This is the crucial change for scoreboard compatibility.
        return LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(processedMessage));
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
