package org.speedrun.speedrun.managers;

import org.speedrun.speedrun.Speedrun;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.speedrun.speedrun.casualGameMode.CasualHighlightManager;
import org.speedrun.speedrun.casualGameMode.CompassListener;

public class CasualGameModeManager {

    private final Speedrun plugin;
    private final GameManager gameManager;
    private CompassListener compassListener;
    private CasualHighlightManager casualHighlightManager;

    private boolean isEnabled = false;


    public CasualGameModeManager(Speedrun plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    public void enable() {
        if (isEnabled) {
            plugin.getLogger().info("Casual Game Mode is already enabled.");
            return;
        }

        plugin.getLogger().info("Enabling Casual Game Mode features...");

        compassListener = new CompassListener(plugin);
        Bukkit.getPluginManager().registerEvents(compassListener, plugin);
        plugin.getLogger().info("CompassListener registered.");

        gameManager.setCompassListener(compassListener);

        casualHighlightManager = new CasualHighlightManager(plugin);
        casualHighlightManager.startHighlightingUpdateTask();

        plugin.getLogger().info("CasualHighlightManager started for gold block highlighting.");


        isEnabled = true;
        plugin.getLogger().info("Casual Game Mode enabled.");
    }

    public void disable() {
        if (!isEnabled) {
            plugin.getLogger().info("Casual Game Mode is already disabled or not active.");
            return;
        }

        plugin.getLogger().info("Disabling Casual Game Mode features...");

        if (compassListener != null) {
            compassListener.stopCompassUpdateTask();
            HandlerList.unregisterAll(compassListener);
            compassListener = null;
        }

        gameManager.setCompassListener(null);

        if (casualHighlightManager != null) {
            casualHighlightManager.stopHighlightingTask();
            HandlerList.unregisterAll(casualHighlightManager);
            casualHighlightManager = null;
        }
        isEnabled = false;
        plugin.getLogger().info("Casual Game Mode disabled.");
    }

    public boolean isCasualModeActive() {
        return isEnabled;
    }
    public CasualHighlightManager getCasualHighlightManager() {
        return casualHighlightManager;
    }

    public CompassListener getCompassListener() {
        return compassListener;
    }

    public void reset() {
        plugin.getLogger().info("Resetting Casual Game Mode components...");
        if (compassListener != null) {
            compassListener.reset();
        } else {
            plugin.getLogger().warning("CompassListener is null during CasualGameModeManager reset.");
        }
        if (casualHighlightManager != null) {
            casualHighlightManager.reset();
        } else {
            plugin.getLogger().warning("CasualHighlightManager is null during CasualGameModeManager reset.");
        }

        plugin.getLogger().info("Casual Game Mode components reset.");
    }
}
