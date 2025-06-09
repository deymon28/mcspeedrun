package org.speedrun.speedrun;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

// =========================================================================================
// Main Plugin Class
// =========================================================================================
// Suppress warnings related to experimental API usage from PaperMC's Lifecycle commands.
// This is done because the user explicitly uses the Lifecycle API, which is marked as experimental.
@SuppressWarnings("UnstableApiUsage")
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
        configManager = new ConfigManager(this);
        taskManager = new TaskManager(this);
        structureManager = new StructureManager(this);
        scoreboardManager = new ScoreboardManager(this);
        gameManager = new GameManager(this); // GameManager is last to have access to others

        // Register Listeners
        getServer().getPluginManager().registerEvents(new GameListener(this), this);

        // Register Commands
        registerBrigadierCommands();

        getLogger().info("SpeedrunRefactored has been enabled.");
    }

    @Override
    public void onDisable() {
        gameManager.stopRun(false); // Stop the run without declaring a winner
        getLogger().info("SpeedrunRefactored has been disabled.");
    }

    private void registerBrigadierCommands() {
        // Changed statement lambda to expression lambda for conciseness.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands ->
                new SpeedrunCommand(this, commands.registrar())
        );
    }

    // Manager Getters - These classes are now public to match their exposure via public getters.
    public ConfigManager getConfigManager() { return configManager; }
    public GameManager getGameManager() { return gameManager; }
    public TaskManager getTaskManager() { return taskManager; }
    public StructureManager getStructureManager() { return structureManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
}
