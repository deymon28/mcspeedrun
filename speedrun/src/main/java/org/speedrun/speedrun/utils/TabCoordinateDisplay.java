package org.speedrun.speedrun.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.speedrun.speedrun.Speedrun;

public class TabCoordinateDisplay {
    private final Speedrun plugin;
    private BukkitRunnable task;

    public TabCoordinateDisplay(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (!plugin.getConfigManager().getRawConfig()
                .getBoolean("casual.player-tab-coordinates", false)) {
            return;
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Location loc = p.getLocation();
                    String text = String.format("§eX: §f%d  §eY: §f%d  §eZ: §f%d",
                            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                    p.sendPlayerListHeaderAndFooter(Component.text(text), Component.empty());
                }
            }
        };
        task.runTaskTimer(plugin, 20L, 20L); // every second
    }

    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
