package org.speedrun.speedrun.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.speedrun.speedrun.Speedrun;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Executes globally configured reward actions such as console commands, sounds,
 * and particles. Keeping this outside ConfigManager prevents config access from
 * becoming responsible for gameplay side effects.
 */
public final class RewardUtil {
    private RewardUtil() {
    }

    /**
     * Runs all reward commands under {@code rewards.<key>}.
     *
     * @param plugin plugin instance used for config, logging, and server access
     * @param key reward section key, for example {@code on-task-complete}
     * @param player specific recipient, or null to apply rewards to all online players
     */
    public static void executeRewardCommands(Speedrun plugin, String key, @Nullable Player player) {
        if (!plugin.getConfigManager().getRawConfig().getBoolean("rewards.enabled", false)) {
            return;
        }

        List<String> commands = plugin.getConfigManager().getRawConfig().getStringList("rewards." + key);
        if (commands.isEmpty()) {
            return;
        }

        List<Player> targets = player != null ? List.of(player) : new ArrayList<>(Bukkit.getOnlinePlayers());
        if (targets.isEmpty()) {
            return;
        }

        for (Player target : targets) {
            for (String rawCommand : commands) {
                executeRewardCommand(plugin, target, rawCommand);
            }
        }
    }

    private static void executeRewardCommand(Speedrun plugin, Player target, String rawCommand) {
        if (rawCommand.startsWith("api:")) {
            executeApiReward(plugin, target, rawCommand);
            return;
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCommand.replace("%player%", target.getName()));
    }

    private static void executeApiReward(Speedrun plugin, Player target, String rawCommand) {
        try {
            String[] parts = rawCommand.substring(4).split(" ");
            if (parts.length == 0) {
                return;
            }

            switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "sound" -> playConfiguredSound(target, parts);
                case "particle" -> spawnConfiguredParticle(target, parts);
                default -> plugin.getLogger().warning("Unknown API reward command: " + rawCommand);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Error executing API reward command: " + rawCommand, ex);
        }
    }

    private static void playConfiguredSound(Player target, String[] parts) {
        if (parts.length < 2) {
            return;
        }

        Sound sound = Registry.SOUNDS.get(Objects.requireNonNull(NamespacedKey.fromString(parts[1].toLowerCase(Locale.ROOT))));
        if (sound == null) {
            return;
        }

        float volume = parts.length > 2 ? Float.parseFloat(parts[2]) : 1.0f;
        float pitch = parts.length > 3 ? Float.parseFloat(parts[3]) : 1.0f;
        target.playSound(target.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    }

    private static void spawnConfiguredParticle(Player target, String[] parts) {
        if (parts.length < 2) {
            return;
        }

        Particle particle = Registry.PARTICLE_TYPE.get(Objects.requireNonNull(NamespacedKey.fromString(parts[1].toLowerCase(Locale.ROOT))));
        if (particle == null) {
            return;
        }

        int count = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
        double offsetX = parts.length > 3 ? Double.parseDouble(parts[3]) : 0.5;
        double offsetY = parts.length > 4 ? Double.parseDouble(parts[4]) : 0.5;
        double offsetZ = parts.length > 5 ? Double.parseDouble(parts[5]) : 0.5;
        double speed = parts.length > 6 ? Double.parseDouble(parts[6]) : 0;
        target.spawnParticle(particle, target.getLocation().add(0, 1.5, 0), count, offsetX, offsetY, offsetZ, speed);
    }

    /**
     * Plays the feedback sound for a structure task completion.
     *
     * @param player specific player to notify, or null to notify all online players
     */
    public static void playStructureTaskCompleteSound(@Nullable Player player) {
        if (player != null) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            return;
        }

        Bukkit.getOnlinePlayers().forEach(online ->
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f));
    }

    /**
     * Plays the waypoint creation/update sound at the marker location.
     *
     * @param location marker location
     */
    public static void playWaypointSound(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        location.getWorld().playSound(location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
    }
}
