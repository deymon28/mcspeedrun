package org.speedrun.speedrun.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.speedrun.speedrun.Speedrun;

/**
 * Casual-mode tab-list header with the player's own block coordinates.
 */
public class TabCoordinateDisplay {
    private static final long UPDATE_PERIOD_TICKS = 10L;

    private final Speedrun plugin;
    private BukkitTask task;

    public TabCoordinateDisplay(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (task != null) {
            return;
        }
        if (!plugin.getConfigManager().isCasualGameModeEnabled()
                || !plugin.getConfigManager().getRawConfig().getBoolean("casual.player-tab-coordinates", false)) {
            return;
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Location location = player.getLocation();
                String header = plugin.getConfigManager().getLangString("tab.coordinates",
                                "&eX: &f%x%  &eY: &f%y%  &eZ: &f%z%")
                        .replace("%x%", String.valueOf(location.getBlockX()))
                        .replace("%y%", String.valueOf(location.getBlockY()))
                        .replace("%z%", String.valueOf(location.getBlockZ()));
                MessageUtil.playerListHeaderFooter(player, header, "");
            }
        }, UPDATE_PERIOD_TICKS, UPDATE_PERIOD_TICKS);
    }

    /** Stops updates and clears the header so no stale coordinates remain visible. */
    public void disable() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            MessageUtil.playerListHeaderFooter(player, "", "");
        }
    }
}
