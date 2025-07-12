package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.SpeedrunLogger;
import org.speedrun.speedrun.utils.TimeUtil;
import org.speedrun.speedrun.casualGameMode.CasualModeStructureManager;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages the core game state of the speedrun.
 * This includes the main timer, game status (running, paused), and proximity scanners for structures.
 * |
 * Керує основним ігровим станом спідрану.
 * Це включає головний таймер, статус гри (триває, на паузі) та сканери для пошуку структур поблизу.
 */
public class GameManager {

    private final Speedrun plugin;
    private final CasualModeStructureManager casualModeStructureManager;
    private final SpeedrunLogger logger;

    // Game state fields
    // Поля стану гри
    private long totalSeconds = 0;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private boolean dragonKilledEnd = false; // True if the run ended by killing the dragon. / True, якщо гра завершилась вбивством дракона.

    // Task schedulers
    // Планувальники завдань
    private BukkitTask mainTimerTask;
    private BukkitTask proximityScannerTask;

    // Specific task timers
    // Таймери для конкретних завдань
    private long villageTimeElapsed = 0;

    // Event counters for logging
    // Лічильники подій для логування
    private final Map<String, Integer> counters = new HashMap<>();

    public GameManager(Speedrun plugin) {
        this.plugin = plugin;
        this.logger = new SpeedrunLogger(plugin);
        this.casualModeStructureManager = new CasualModeStructureManager(plugin);

        // If not configured to wait for the first player, start the run immediately.
        // Якщо не налаштовано очікування першого гравця, починаємо гру негайно.
        if (!plugin.getConfigManager().isStartOnFirstJoin()) {
            startRun();
        }
    }

    /**
     * Starts a new speedrun session.
     * Resets all timers and states, reloads tasks, and starts the main timer and scanners.
     * |
     * Починає нову сесію спідрану.
     * Скидає всі таймери та стани, перезавантажує завдання та запускає головний таймер і сканери.
     */
    public void startRun() {
        if (isRunning) return;
        isRunning = true;
        isPaused = false;
        totalSeconds = 0;
        villageTimeElapsed = 0;
        dragonKilledEnd = false;
        counters.clear();

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            logger.start();
        }

        plugin.getTaskManager().reloadTasks();
        plugin.getStructureManager().reset();

        startTimer();
        startProximityScanner();

        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.run-started"));
        logger.info("Speedrun started. Players: " +
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
    }

    /**
     * Forcibly resets the current run.
     * Called by an admin command.
     * |
     * Примусово скидає поточний спідран.
     * Викликається командою адміністратора.
     */
    public void resetRun() {
        logger.info("Speedrun is being reset by an admin.");
        stopRun(false); // Stop without triggering a win condition. / Зупинка без спрацювання умови перемоги.
        startRun();
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("commands.run-reset"));
    }

    /**
     * Stops the current speedrun.
     * |
     * Зупиняє поточний спідран.
     *
     * @param dragonKilled True if the run ended because the Ender Dragon was killed. / True, якщо гра завершилась через вбивство Дракона Краю.
     */
    public void stopRun(boolean dragonKilled) {
        if (!isRunning && !dragonKilled) return;

        // Cancel all scheduled tasks.
        // Скасування всіх запланованих завдань.
        if (mainTimerTask != null) mainTimerTask.cancel();
        if (proximityScannerTask != null) proximityScannerTask.cancel();

        String outcome = dragonKilled ? "Completed (Dragon Slain)" : "Ended/Reset";
        String finalTime = getFormattedTime();

        if (dragonKilled) {
            isPaused = true; // Pause to freeze the timer on the final time. / Ставимо на паузу, щоб зафіксувати фінальний час.
            dragonKilledEnd = true;
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.dragon-slain"));
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.final-time", "%time%", finalTime));
            // Update scoreboards one last time to show the final state.
            // Оновлюємо скорборди востаннє, щоб показати фінальний стан.
            Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
        }

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            logger.stop(outcome, finalTime);
        }

        isRunning = false;
    }

    /**
     * Toggles the pause state of the game timer.
     * While paused, the main timer does not increment.
     * |
     * Перемикає стан паузи ігрового таймера.
     * Під час паузи головний таймер не збільшується.
     */
    public void togglePause() {
        if (!isRunning || dragonKilledEnd) return;
        isPaused = !isPaused;
        Bukkit.broadcast(plugin.getConfigManager().getFormatted(
                isPaused ? "commands.timer-paused" : "commands.timer-resumed"));
        logger.info("Timer " + (isPaused ? "paused." : "resumed."));
    }

    /**
     * Starts the main timer task, which runs every second (20 ticks).
     * It increments the total time and updates tasks and scoreboards.
     * |
     * Запускає завдання головного таймера, яке виконується щосекунди (20 тіків).
     * Воно збільшує загальний час та оновлює завдання і скорборди.
     */
    private void startTimer() {
        if (mainTimerTask != null) mainTimerTask.cancel();
        mainTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning && !dragonKilledEnd) {
                    cancel();
                    return;
                }
                if (!isPaused) {
                    totalSeconds++;
                    if (plugin.getStructureManager().isVillageSearchActive()) {
                        villageTimeElapsed++;
                    }
                }
                // These updates need to run even when paused to reflect progress.
                // Ці оновлення мають працювати навіть на паузі, щоб відображати прогрес.
                plugin.getTaskManager().updateItemTasks();
                plugin.getStructureManager().checkVillageTimeout();
                Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * Starts the proximity scanner task.
     * This task periodically checks the surroundings of each player for specific structures like villages or lava pools.
     * |
     * Запускає завдання сканера близькості.
     * Це завдання періодично перевіряє оточення кожного гравця на наявність структур, як-от села чи озера лави.
     */
    private void startProximityScanner() {
        if (proximityScannerTask != null) proximityScannerTask.cancel();
        proximityScannerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (isPaused || !isRunning) return;

                boolean needsVillage = plugin.getStructureManager().isVillageSearchActive();
                boolean needsLava = plugin.getStructureManager().isLavaPoolSearchActive();
                if (!needsVillage && !needsLava) return;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (needsVillage) findNearbyBell(player);
                    if (needsLava) findNearbyLavaPool(player);
                }
            }
        }.runTaskTimer(plugin, 100L, 60L); // Runs every 3 seconds (60 ticks), starts after 5 seconds. / Працює кожні 3 сек, починається через 5 сек.
    }

    /**
     * Scans for a village bell near the player to detect a village.
     * Сканує наявність дзвона біля гравця для виявлення села.
     */
    private void findNearbyBell(Player player) {
        scanForBlock(player, plugin.getConfigManager().getVillageBellRadius(), Material.BELL, "VILLAGE");
    }

    /**
     * Scans for a significant number of lava source blocks to detect a lava pool.
     * Сканує наявність значної кількості джерел лави для виявлення лавового озера.
     */
    private void findNearbyLavaPool(Player player) {
        final int radius = plugin.getConfigManager().getLavaPoolRadius();
        final int requiredSources = plugin.getConfigManager().getLavaPoolRequiredSources();
        int lavaCount = 0;
        Location playerLoc = player.getLocation();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) { // Scan a limited vertical range. / Скануємо обмежений вертикальний діапазон.
                for (int z = -radius; z <= radius; z++) {
                    Block block = playerLoc.clone().add(x, y, z).getBlock();
                    // Check for lava source blocks (level 0).
                    // Перевірка на блоки-джерела лави (рівень 0).
                    if (block.getType() == Material.LAVA && block.getBlockData() instanceof Levelled level && level.getLevel() == 0) {
                        if (++lavaCount >= requiredSources) {
                            plugin.getStructureManager().structureFound(player, "LAVA_POOL", block.getLocation());
                            return; // Found, no need to continue scanning. / Знайдено, не потрібно продовжувати.
                        }
                    }
                }
            }
        }
    }

    /**
     * A generic method to scan for a specific block material in a radius around a player.
     * Загальний метод для сканування певного матеріалу блоку в радіусі навколо гравця.
     */
    private void scanForBlock(Player player, int radius, Material material, String structureKey) {
        Location playerLoc = player.getLocation();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = playerLoc.clone().add(x, y, z).getBlock();
                    if (block.getType() == material) {
                        plugin.getStructureManager().structureFound(player, structureKey, block.getLocation());
                        return;
                    }
                }
            }
        }
    }

    // =========================================================================================
    // Getters and State Checks
    // =========================================================================================

    /** @return The speedrun logger instance. / Екземпляр логера спідрану. */
    public SpeedrunLogger getLogger() { return logger; }

    /** @return A map of event counters for logging purposes. / Мапа лічильників подій для логування. */
    public Map<String, Integer> getCounters() { return counters; }

    /** Increments a named counter by one. / Збільшує іменований лічильник на одиницю. */
    public void incrementCounter(String key) {
        counters.put(key, counters.getOrDefault(key, 0) + 1);
    }

    /** @return The manager for casual mode features. / Менеджер функцій казуального режиму. */
    public CasualModeStructureManager getCasualModeStructureManager() {
        return casualModeStructureManager;
    }

    /** @return The total elapsed time of the run, formatted as HH:MM:SS. / Загальний час гри, відформатований як ГГ:ХХ:СС. */
    public String getFormattedTime() { return TimeUtil.format(totalSeconds); }

    /** @return The remaining time in seconds to find a village. / Залишок часу в секундах на пошук села. */
    public long getVillageTimeRemaining() {
        return plugin.getConfigManager().getVillageTimeout() - villageTimeElapsed;
    }

    /** @return True if the speedrun is currently active. / True, якщо спідран наразі активний. */
    public boolean isRunning() { return isRunning; }

    /** @return True if the speedrun timer is paused. / True, якщо таймер спідрану на паузі. */
    public boolean isPaused() { return isPaused; }

    /** @return True if the run ended by killing the Ender Dragon. / True, якщо гра завершилась вбивством Дракона Краю. */
    public boolean isDragonKilledEnd() { return dragonKilledEnd; }
}