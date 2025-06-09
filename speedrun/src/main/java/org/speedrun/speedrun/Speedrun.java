package org.speedrun.speedrun;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;


// =========================================================================================
// Main Plugin Class
// =========================================================================================
public final class Speedrun extends JavaPlugin {

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
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            new SpeedrunCommand(this, commands.registrar());
        });
    }

    // Manager Getters
    public ConfigManager getConfigManager() { return configManager; }
    public GameManager getGameManager() { return gameManager; }
    public TaskManager getTaskManager() { return taskManager; }
    public StructureManager getStructureManager() { return structureManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
}








