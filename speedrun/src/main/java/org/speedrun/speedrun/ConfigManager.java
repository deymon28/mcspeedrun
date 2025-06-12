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

    /**
     * NEW: Gets a raw string from the language file, used for localizing names.
     * @param key The key from the lang file (e.g., "tasks.OAK_LOG").
     * @param defaultValue A fallback value if the key is not found.
     * @return The translated string.
     */
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

    /**
     * FIX: Correctly executes rewards for both single players and collective achievements.
     */
//    public void executeRewardCommands(String key, @Nullable Player player) {
//        if (!config.getBoolean("rewards.enabled", false)) return;
//        List<String> commands = config.getStringList("rewards." + key);
//
//        for (String cmd : commands) {
//            if (cmd.contains("%player%")) {
//                if (player != null) {
//                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
//                } else {
//                    // If player is null (collective task), run for everyone.
//                    for (Player p : Bukkit.getOnlinePlayers()) {
//                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
//                    }
//                }
//            } else {
//                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
//            }
//        }
//    }
    public void executeRewardCommands(String key, @Nullable Player player) {
        if (!plugin.getConfig().getBoolean("rewards.enabled", false)) {
            plugin.getLogger().fine("[Reward] Rewards are disabled in config. Key: " + key);
            return;
        }

        List<String> commands = plugin.getConfig().getStringList("rewards." + key);
        if (commands.isEmpty()) {
            plugin.getLogger().fine("[Reward] No commands found for key: " + key);
            return;
        }

        List<Player> targets = (player != null)
                ? List.of(player)
                : new ArrayList<>(Bukkit.getOnlinePlayers());

        if (targets.isEmpty()) {
            plugin.getLogger().fine("[Reward] No online players to execute commands for key: " + key);
            return;
        }

        for (Player target : targets) {
            for (String rawCmd : commands) {
                if (rawCmd.startsWith("api:")) {
                    String apiCommand = rawCmd.substring(4);
                    String[] parts = apiCommand.split(" ");
                    String apiType = parts[0].toLowerCase();

                    plugin.getLogger().fine("[Reward] Executing API command for " + target.getName() + ": " + rawCmd);

                    try {
                        switch (apiType) {
                            case "sound":
                                // use Registry for Sound
                                // Formate: api:sound <NAMESPACE:SOUND_ID> [loud] [tone]
                                // Example: api:sound minecraft:entity.player.levelup 1.0 1.5
                                String soundId = parts[1].toLowerCase();
                                NamespacedKey soundKey = NamespacedKey.fromString(soundId);

                                assert soundKey != null;
                                Sound sound = Registry.SOUNDS.get(soundKey);
                                if (sound == null) {
                                    plugin.getLogger().warning("[Reward] Sound not found in registry: " + soundId + " for command: " + rawCmd);
                                    continue;
                                }

                                float volume = (parts.length > 2) ? Float.parseFloat(parts[2]) : 1.0f;
                                float pitch = (parts.length > 3) ? Float.parseFloat(parts[3]) : 1.0f;
                                target.playSound(target.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
                                break;
                            case "particle":
                                // use Registry rof Particle
                                // Formate: api:particle <NAMESPACE:PARTICLE_ID> [count] [offsetX] [offsetY] [offsetZ] [speed]
                                // Example: api:particle minecraft:totem_of_undying 30 0.5 0.5 0.5 0
                                String particleId = parts[1].toLowerCase();
                                NamespacedKey particleKey = NamespacedKey.fromString(particleId);

                                assert particleKey != null;
                                Particle particle = Registry.PARTICLE_TYPE.get(particleKey); // Gey Particle from Registry
                                if (particle == null) {
                                    plugin.getLogger().warning("[Reward] Particle not found in registry: " + particleId + " for command: " + rawCmd);
                                    continue;
                                }

                                int count = (parts.length > 2) ? Integer.parseInt(parts[2]) : 1;
                                double offsetX = (parts.length > 3) ? Double.parseDouble(parts[3]) : 0.5;
                                double offsetY = (parts.length > 4) ? Double.parseDouble(parts[4]) : 0.5;
                                double offsetZ = (parts.length > 5) ? Double.parseDouble(parts[5]) : 0.5;
                                double speed = (parts.length > 6) ? Double.parseDouble(parts[6]) : 0;

                                Location particleLoc = target.getLocation().add(0, 1.5, 0);
                                target.spawnParticle(particle, particleLoc, count, offsetX, offsetY, offsetZ, speed);
                                break;
                            default:
                                plugin.getLogger().warning("[Reward] Unknown API type: '" + apiType + "' in command: " + rawCmd);
                                break;
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("[Reward] Invalid format or missing arguments for API command: " + rawCmd + " - " + e.getMessage());
                    } catch (IndexOutOfBoundsException e) {
                        plugin.getLogger().warning("[Reward] Missing arguments for API command: " + rawCmd + " - " + e.getMessage());
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "[Reward] Error executing API command: " + rawCmd, e);
                    }
                } else {
                    String consoleCommand = rawCmd.replace("%player%", target.getName());
                    plugin.getLogger().fine("[Reward] Dispatching console command for " + target.getName() + ": " + consoleCommand);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCommand);
                }
            }
        }
    }

    // --- Getters for settings ---
    public FileConfiguration getRawConfig() { return config; }
    public boolean isStartOnFirstJoin() { return config.getBoolean("settings.start-on-first-join", true); }
    public long getVillageTimeout() { return config.getLong("settings.village-search-timeout", 600); }
    public TrackingMode getTrackingMode() { return TrackingMode.valueOf(config.getString("settings.task-tracking-mode", "INVENTORY").toUpperCase()); }
    public boolean isPlayerScalingEnabled() { return config.getBoolean("settings.scale-resources-by-playercount.enabled", true); }
    public double getPlayerScalingMultiplier() { return config.getDouble("settings.scale-resources-by-playercount.multiplier", 0.5); }
    public boolean isLogAttemptsEnabled() { return config.getBoolean("settings.log-attempts", true); }

    // --- NEW: Getters for configurable proximity scanner ---
    public int getLavaPoolRadius() { return config.getInt("settings.proximity-scanner.lava-pool.radius", 16); }
    public int getLavaPoolRequiredSources() { return config.getInt("settings.proximity-scanner.lava-pool.required-source-blocks", 12); }
    public int getVillageBellRadius() { return config.getInt("settings.proximity-scanner.village.radius", 32); }
}