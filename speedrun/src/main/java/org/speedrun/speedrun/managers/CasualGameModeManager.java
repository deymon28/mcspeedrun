package org.speedrun.speedrun.managers;

import org.speedrun.speedrun.Speedrun;
import org.bukkit.Bukkit;
import org.speedrun.speedrun.casualGameMode.CompassListener;
import org.speedrun.speedrun.casualGameMode.GiveCompassCommand;

public class CasualGameModeManager {

    private final Speedrun plugin;
    private final GameManager gameManager;
    private CompassListener compassListener;
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

        // Initialize and register the CompassListener
        compassListener = new CompassListener(plugin);
        Bukkit.getPluginManager().registerEvents(compassListener, plugin);
        plugin.getLogger().info("CompassListener registered.");

        // Register the /givecompass command (assuming it's part of casual mode)
        // Make sure your GiveCompassCommand constructor can take the plugin instance.
        plugin.getCommand("givecompass").setExecutor(new GiveCompassCommand(plugin));
        plugin.getLogger().info("GiveCompassCommand registered.");

        // Link the CompassListener to the GameManager so other parts of your plugin can access it
        gameManager.setCompassListener(compassListener);

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
            compassListener.stopCompassUpdateTask(); // Stop the repeating compass update task
            // Bukkit automatically unregisters all plugin listeners on plugin disable,
            // but for dynamic enabling/disabling, you might need to manage handlers explicitly
            // if this manager is toggled mid-game. For simplicity here, we rely on plugin disable.
            // If you later add more listeners to CasualGameModeManager, you'd unregister them here.
        }

        // Clear the reference in GameManager to prevent lingering issues
        gameManager.setCompassListener(null);

        // Unregister the command if you wish (less common as commands are usually globally managed)
        // However, if the command should only exist when casual mode is active:
        // plugin.getCommand("givecompass").setExecutor(null); // This will effectively unregister it

        isEnabled = false;
        plugin.getLogger().info("Casual Game Mode disabled.");
    }

    public boolean isCasualModeActive() {
        return isEnabled;
    }
}
