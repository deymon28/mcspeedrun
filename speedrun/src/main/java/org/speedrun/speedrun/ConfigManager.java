package org.speedrun.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

public class ConfigManager {
    private final Speedrun plugin;
    private FileConfiguration config;
    private FileConfiguration lang;

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
        File langFile = new File(plugin.getDataFolder(), "lang/" + langCode + ".yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + langCode + ".yml", false);
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    public String getMessage(String path) {
        return lang.getString(path, "Missing translation: " + path);
    }

    public String getLangString(String key, String defaultValue) {
        return lang.getString(key, defaultValue);
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

    public String getFormattedText(String key, String... replacements) {
        String message = getMessage(key);
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(message);
        String processedMessage = LegacyComponentSerializer.legacyAmpersand().serialize(component);
        for (int i = 0; i < replacements.length; i += 2) {
            processedMessage = processedMessage.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(processedMessage));
    }

    public void executeRewardCommands(String key, @Nullable Player player) {
        if (!config.getBoolean("rewards.enabled", false)) return;
        List<String> commands = config.getStringList("rewards." + key);
        if (commands.isEmpty()) return;

        List<Player> targets = (player != null) ? List.of(player) : new ArrayList<>(Bukkit.getOnlinePlayers());
        if (targets.isEmpty()) return;

        for (Player target : targets) {
            for (String rawCmd : commands) {
                if (rawCmd.startsWith("api:")) {
                    try {
                        String apiCommand = rawCmd.substring(4);
                        String[] parts = apiCommand.split(" ");
                        String apiType = parts[0].toLowerCase();
                        switch (apiType) {
                            case "sound":
                                Sound sound = Registry.SOUNDS.get(Objects.requireNonNull(NamespacedKey.fromString(parts[1].toLowerCase())));
                                if (sound == null) continue;
                                float volume = (parts.length > 2) ? Float.parseFloat(parts[2]) : 1.0f;
                                float pitch = (parts.length > 3) ? Float.parseFloat(parts[3]) : 1.0f;
                                target.playSound(target.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
                                break;
                            case "particle":
                                Particle particle = Registry.PARTICLE_TYPE.get(Objects.requireNonNull(NamespacedKey.fromString(parts[1].toLowerCase())));
                                if (particle == null) continue;
                                int count = (parts.length > 2) ? Integer.parseInt(parts[2]) : 1;
                                double offsetX = (parts.length > 3) ? Double.parseDouble(parts[3]) : 0.5;
                                double offsetY = (parts.length > 4) ? Double.parseDouble(parts[4]) : 0.5;
                                double offsetZ = (parts.length > 5) ? Double.parseDouble(parts[5]) : 0.5;
                                double speed = (parts.length > 6) ? Double.parseDouble(parts[6]) : 0;
                                target.spawnParticle(particle, target.getLocation().add(0, 1.5, 0), count, offsetX, offsetY, offsetZ, speed);
                                break;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Error executing API reward command: " + rawCmd, e);
                    }
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCmd.replace("%player%", target.getName()));
                }
            }
        }
    }

    public FileConfiguration getRawConfig() { return config; }
    public boolean isStartOnFirstJoin() { return config.getBoolean("settings.start-on-first-join", true); }
    public long getVillageTimeout() { return config.getLong("settings.village-search-timeout", 600); }
    public TrackingMode getTrackingMode() { return TrackingMode.valueOf(config.getString("settings.task-tracking-mode", "INVENTORY").toUpperCase()); }
    // Этот метод теперь отражает под-ключ 'enabled'
    public boolean isPlayerScalingEnabled() { return config.getBoolean("settings.scale-resources-by-playercount.enabled", true); }
    public double getPlayerScalingMultiplier() { return config.getDouble("settings.scale-resources-by-playercount.multiplier", 0.5); }
    public boolean isLogAttemptsEnabled() { return config.getBoolean("settings.log-attempts", true); }
    public int getLavaPoolRadius() { return config.getInt("settings.proximity-scanner.lava-pool.radius", 16); }
    public int getLavaPoolRequiredSources() { return config.getInt("settings.proximity-scanner.lava-pool.required-source-blocks", 12); }
    public int getVillageBellRadius() { return config.getInt("settings.proximity-scanner.village.radius", 32); }
    public boolean isReassigningLocationsEnabled() { return config.getBoolean("settings.allow-reassigning-locations", true); }
    public int getNetherPortalCheckRadius() { return config.getInt("settings.proximity-scanner.nether-portal.check-radius", 4); }
    public int getTeleportPortalSearchRadius() { return config.getInt("settings.proximity-scanner.nether-portal.search-radius", 90); }
}