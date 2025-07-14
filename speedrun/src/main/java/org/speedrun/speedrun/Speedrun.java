package org.speedrun.speedrun;

import org.bukkit.plugin.java.JavaPlugin;
import org.speedrun.speedrun.casualGameMode.GiveCompassCommand;
import org.speedrun.speedrun.managers.*;

import java.util.Objects;

// =========================================================================================
// Main Plugin Class
// =========================================================================================

/**
 * The core class of the Speedrun plugin.
 * It initializes and manages all components, including managers, event listeners, and commands.
 * |
 * Головний клас плагіна Speedrun.
 * Він ініціалізує та керує всіма компонентами, включаючи менеджери, слухачі подій та команди.
 */
public final class Speedrun extends JavaPlugin {

    // =========================================================================================
    // Managers
    // =========================================================================================

    private ConfigManager configManager;
    private GameManager gameManager;
    private TaskManager taskManager;
    private StructureManager structureManager;
    private ScoreboardManager scoreboardManager;
    private CasualGameModeManager casualGameModeManager;
    // =========================================================================================
    // Plugin Lifecycle
    // =========================================================================================

    /**
     * Called when the plugin is enabled by the server.
     * Initializes all managers in the correct dependency order and registers events and commands.
     * |
     * Викликається, коли сервер вмикає плагін.
     * Ініціалізує всі менеджери у правильному порядку залежностей та реєструє події й команди.
     */
    @Override
    public void onEnable() {
        // Initialize managers in the correct order.
        // Ініціалізація менеджерів у правильному порядку.
        this.configManager = new ConfigManager(this);           // Must be first, as others depend on it. / Має бути першим, оскільки інші залежать від нього.
        this.taskManager = new TaskManager(this);               // Loads task data from the config. / Завантажує дані завдань з конфігурації.
        this.structureManager = new StructureManager(this);     // Handles structure detection logic. / Обробляє логіку виявлення структур.
        this.gameManager = new GameManager(this);               // Contains the core game loop and state. / Містить основний ігровий цикл та стан.
        this.scoreboardManager = new ScoreboardManager(this);   // Manages the player-facing UI. / Керує інтерфейсом, що бачить гравець.

        this.casualGameModeManager = new CasualGameModeManager(this, gameManager);
        if (configManager.isCasualGameModeEnabled()) {
            casualGameModeManager.enable();
        } else {
            getLogger().info("Casual Game Mode is disabled in config.yml. Some features will not be active.");
        }
        // Register event listeners and command handlers.
        // Реєстрація слухачів подій та обробників команд.
        getServer().getPluginManager().registerEvents(new GameListener(this, gameManager), this);

        RunCommand runCommand = new RunCommand(this);
        Objects.requireNonNull(getCommand("run")).setExecutor(runCommand);
        Objects.requireNonNull(getCommand("run")).setTabCompleter(runCommand);
        Objects.requireNonNull(this.getCommand("givecompass")).setExecutor(new GiveCompassCommand(this));
        getLogger().info("Speedrun plugin has been enabled.");
    }

    /**
     * Called when the plugin is disabled by the server.
     * Ensures any active speedrun is stopped gracefully.
     * |
     * Викликається, коли сервер вимикає плагін.
     * Гарантує, що будь-який активний спідран буде коректно зупинено.
     */
    @Override
    public void onDisable() {
        // Gracefully stop any ongoing speedrun to save logs correctly.
        // Коректно зупиняємо спідран, щоб правильно зберегти логи.
        if (gameManager != null) {
            gameManager.stopRun(false);
        }
        if (casualGameModeManager != null) {
            casualGameModeManager.disable();
        }
        getLogger().info("Speedrun plugin has been disabled.");
    }

    // =========================================================================================
    // Public Getters
    // =========================================================================================

    /**
     * @return The configuration manager instance. / Екземпляр менеджера конфігурації.
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * @return The game manager instance. / Екземпляр менеджера гри.
     */
    public GameManager getGameManager() {
        return gameManager;
    }

    public CasualGameModeManager getCasualGameModeManager() {
        return casualGameModeManager;
    }
    /**
     * @return The task manager instance. / Екземпляр менеджера завдань.
     */
    public TaskManager getTaskManager() {
        return taskManager;
    }

    /**
     * @return The structure manager instance. / Екземпляр менеджера структур.
     */
    public StructureManager getStructureManager() {
        return structureManager;
    }

    /**
     * @return The scoreboard manager instance. / Екземпляр менеджера скорборду.
     */
    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }
}