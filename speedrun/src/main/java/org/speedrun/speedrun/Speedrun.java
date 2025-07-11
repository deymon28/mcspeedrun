package org.speedrun.speedrun;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

// =========================================================================================
// Main Plugin Class
// =========================================================================================
// Suppress warnings related to experimental API usage from PaperMC's Lifecycle commands.
// This is done because the user explicitly uses the Lifecycle API, which is marked as experimental.
public final class Speedrun extends JavaPlugin {

// Making manager classes public as they are exposed through public getter methods
// and thus need to be accessible outside their package.
    private ConfigManager configManager;
    private GameManager gameManager;
    private TaskManager taskManager;
    private StructureManager structureManager;
    private ScoreboardManager scoreboardManager;

    @Override
    public void onEnable() {
        // Initialize managers
        this.configManager = new ConfigManager(this);
        this.taskManager = new TaskManager(this);
        this.structureManager = new StructureManager(this);
        this.gameManager = new GameManager(this); // GameManager is last to have access to others
        this.scoreboardManager = new ScoreboardManager(this);

        // Register Listeners
        getServer().getPluginManager().registerEvents(new GameListener(this, gameManager), this);

        // Register Commands
        Objects.requireNonNull(getCommand("run")).setExecutor(new RunCommand(this));
        Objects.requireNonNull(getCommand("run")).setTabCompleter(new RunCommand(this)); // RunCommand теперь также TabCompleter

        getLogger().info("SpeedrunRefactored has been enabled.");
    }

    @Override
    public void onDisable() {
        gameManager.stopRun(false); // Stop the run without declaring a winner
        getLogger().info("SpeedrunRefactored has been disabled.");
    }

    // Manager Getters - These classes are now public to match their exposure via public getters.

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public StructureManager getStructureManager() {
        return structureManager;
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

}