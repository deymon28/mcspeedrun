package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.SpeedrunLogger;
import org.speedrun.speedrun.casualGameMode.CompassListener;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.utils.TimeUtil;
import org.speedrun.speedrun.casualGameMode.CasualModeStructureManager;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages the core game state of the speedrun.
 * This includes the main timer, game status (running, paused), and proximity scanners for structures.
 */
public class GameManager {

    private final Speedrun plugin;
    private final CasualModeStructureManager casualModeStructureManager;
    private final SpeedrunLogger logger;
    private CompassListener compassListener;

    // Game state fields
    /**
     * Run time is measured on the wall clock instead of by counting timer ticks, so a lagging
     * server does not slow the timer down. {@code accumulatedNanos} holds finished segments;
     * {@code segmentStartNanos} is the start of the current unpaused segment, or -1 while the
     * clock is stopped (paused, finished, or no run).
     */
    private long accumulatedNanos = 0;
    private long segmentStartNanos = -1;
    private boolean isRunning = false;
    /** False after a run was stopped or completed, so a rejoin does not silently start a new run. */
    private boolean autoStartAllowed = true;
    /** True between restoring a saved run and the first resume, to announce the restore. */
    private boolean restoredRunWaiting;
    private boolean isPaused = false;
    private boolean dragonKilledEnd = false; // True if the run ended by killing the dragon.
    private boolean autoPaused = false; // True while settings.pause-when-empty paused the timer.

    // Task schedulers
    private BukkitTask mainTimerTask;
    private BukkitTask proximityScannerTask;

    // Specific task timers
    public long villageTimeElapsed = 0;

    // Event counters for logging
    private final Map<String, Integer> counters = new HashMap<>();

    public GameManager(Speedrun plugin) {
        this.plugin = plugin;
        this.logger = plugin.getSpeedrunLogger();
        this.casualModeStructureManager = new CasualModeStructureManager(plugin);
        // The plugin starts (or restores) the first run once every manager exists.
    }

    /**
     * Starts a new speedrun session.
     * Resets all timers and states, reloads tasks, and starts the main timer and scanners.
     */
    public void startRun() {
        if (isRunning) return;
        plugin.getTraceLogger().trace("lifecycle", "run_start_requested",
                "online_players", Bukkit.getOnlinePlayers().size(),
                "start_pre_scan_enabled", plugin.getConfigManager().isStartPreScanEnabled(),
                "hardcore", plugin.getConfigManager().isHardcoreModeEnabled());
        isRunning = true;
        isPaused = false;
        accumulatedNanos = 0;
        segmentStartNanos = -1;
        resumeClock();
        villageTimeElapsed = 0;
        dragonKilledEnd = false;
        autoPaused = false;
        counters.clear();
        if (plugin.getRecordsManager() != null) {
            plugin.getRecordsManager().startRun();
        }

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            logger.start();
        }

        plugin.getTaskManager().reloadTasks();
        plugin.getStructureManager().reset();
        casualModeStructureManager.clearWaypoints();
        if (plugin.getConfigManager().isStartPreScanEnabled()
                && !plugin.getConfigManager().isHardcoreModeEnabled()) {
            plugin.getStructureManager().preScanRequiredStructures();
        }

        startTimer();
        if (!plugin.getConfigManager().isHardcoreModeEnabled()) {
            startProximityScanner();
        }

        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.run-started"));
        logger.info("Speedrun started. Players: " +
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
        plugin.getTraceLogger().trace("lifecycle", "run_started",
                "players", Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(",")));
        // A run started on an empty server (at boot, or from the console) pauses right away.
        Bukkit.getScheduler().runTask(plugin, this::handleServerEmptyCheck);
    }

    /**
     * Forcibly resets the current run.
     * Called by an admin command.
     */
    public void resetRun() {
        logger.info("Speedrun is being reset by an admin.");
        plugin.getTraceLogger().trace("lifecycle", "run_reset_requested");
        stopRun(false); // Stop without triggering a win condition.
        if (plugin.getPlatform() != null) {
            // Drop chunk loads and worker results that belong to the previous run.
            plugin.getPlatform().invalidatePendingWork();
        }
        if (plugin.getGameListener() != null) {
            plugin.getGameListener().resetRuntimeCaches();
        }
        if (plugin.getCasualGameModeManager() != null) {
            plugin.getCasualGameModeManager().reset();
        }
        Bukkit.getScheduler().runTask(plugin, this::startRun);
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("commands.run-reset"));
    }

    /**
     * Restores a run saved before a server restart (see {@link RunStateManager}). The run comes
     * back paused; it continues when the first player joins, or stays paused if an administrator
     * had paused it. World side effects (lodestones, light beams) are rebuilt from the state.
     */
    public void resumeRun(ConfigurationSection state) {
        if (isRunning) return;
        ConfigurationSection timer = state.getConfigurationSection("run.timer");
        long elapsedMillis = timer == null ? 0 : Math.max(0, timer.getLong("elapsed-millis"));
        boolean manuallyPaused = timer != null && timer.getBoolean("manually-paused");

        isRunning = true;
        isPaused = true;
        autoPaused = !manuallyPaused;
        restoredRunWaiting = true;
        accumulatedNanos = elapsedMillis * 1_000_000L;
        segmentStartNanos = -1;
        villageTimeElapsed = timer == null ? 0 : Math.max(0, timer.getLong("village-elapsed"));
        dragonKilledEnd = false;
        autoStartAllowed = true;
        counters.clear();

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            logger.start();
            logger.info("Run restored after a server restart at " + getFormattedTime() + ".");
        }
        plugin.getRecordsManager().restoreState(state.getConfigurationSection("records"));
        plugin.getTaskManager().reloadTasks();
        plugin.getTaskManager().restoreState(state.getConfigurationSection("tasks"));
        plugin.getStructureManager().reset();
        casualModeStructureManager.clearWaypoints();
        plugin.getStructureManager().restoreState(state.getConfigurationSection("structures"));
        if (plugin.getConfigManager().isStartPreScanEnabled()
                && !plugin.getConfigManager().isHardcoreModeEnabled()) {
            plugin.getStructureManager().preScanRequiredStructures();
        }

        startTimer();
        if (!plugin.getConfigManager().isHardcoreModeEnabled()) {
            startProximityScanner();
        }
        plugin.getTraceLogger().trace("lifecycle", "run_restored",
                "time", getFormattedTime(),
                "manually_paused", manuallyPaused);
    }

    /** Writes the timer part of the saved run state. */
    void writeState(ConfigurationSection section) {
        long elapsed = accumulatedNanos;
        if (segmentStartNanos >= 0) {
            elapsed += System.nanoTime() - segmentStartNanos;
        }
        section.set("elapsed-millis", elapsed / 1_000_000L);
        section.set("manually-paused", isPaused && !autoPaused);
        section.set("village-elapsed", villageTimeElapsed);
    }

    /**
     * Ends the active run because the server stops while settings.resume-after-restart keeps it.
     * World side effects are removed like on a stop, but the saved state is kept for the next start.
     */
    public void suspendRun() {
        endRun(false, true);
    }

    /**
     * Stops the current speedrun.
     *
     * @param dragonKilled True if the run ended because the Ender Dragon was killed.
     */
    public void stopRun(boolean dragonKilled) {
        endRun(dragonKilled, false);
    }

    private void endRun(boolean dragonKilled, boolean suspend) {
        // A dragon killed while no run is active (before the start, after /run stop, or a
        // respawned dragon after a finished run) must not end or announce anything.
        if (!isRunning) return;
        restoredRunWaiting = false;
        if (!suspend && plugin.getRunStateManager() != null) {
            plugin.getRunStateManager().delete();
        }
        plugin.getTraceLogger().trace("lifecycle", "run_stop_requested",
                "dragon_killed", dragonKilled,
                "time", getFormattedTime(),
                "running", isRunning);

        // Cancel all scheduled tasks.
        if (mainTimerTask != null) {
            mainTimerTask.cancel();
            mainTimerTask = null;
        }
        if (proximityScannerTask != null) {
            proximityScannerTask.cancel();
            proximityScannerTask = null;
        }
        plugin.getStructureManager().cancelPreScan();
        if (plugin.getPlatform() != null) {
            // Queued chunk loads and scans belong to the run that just ended.
            plugin.getPlatform().invalidatePendingWork();
        }
        casualModeStructureManager.clearWaypoints();
        plugin.getStructureManager().clearHiddenLodestones();
        if (plugin.getGameListener() != null) {
            plugin.getGameListener().resetRuntimeCaches();
        }

        // Freeze the clock on the final time.
        pauseClock();
        autoStartAllowed = false;
        String outcome = dragonKilled ? "Completed (Dragon Slain)"
                : suspend ? "Suspended (server stopped; the run resumes after the restart)" : "Ended/Reset";
        String finalTime = getFormattedTime();

        if (dragonKilled) {
            isPaused = true;
            dragonKilledEnd = true;
            MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.dragon-slain"));
            MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.final-time", "%time%", finalTime));
            if (plugin.getRecordsManager() != null && isRunning) {
                plugin.getRecordsManager().onRunCompleted(getTotalSeconds());
            }
            // Update scoreboards one last time to show the final state.
            Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
        }

        // Always close the session: log-attempts may have been switched off during the run.
        logger.stop(outcome, finalTime);

        isRunning = false;
        plugin.getTraceLogger().trace("lifecycle", "run_stopped",
                "dragon_killed", dragonKilled,
                "outcome", outcome,
                "final_time", finalTime);
    }

    /**
     * Toggles the pause state of the game timer.
     * While paused, the main timer does not increment.
     */
    public void togglePause() {
        if (!isRunning || dragonKilledEnd) return;
        setPaused(!isPaused);
        autoPaused = false;
        restoredRunWaiting = false;
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted(
                isPaused ? "commands.timer-paused" : "commands.timer-resumed"));
        logger.info("Timer " + (isPaused ? "paused." : "resumed."));
    }

    /**
     * Starts the main timer task, which runs every second (20 ticks).
     * It increments the total time and updates tasks and scoreboards.
     */
    private void startTimer() {
        if (mainTimerTask != null) mainTimerTask.cancel();
        mainTimerTask = new BukkitRunnable() {
            private int secondsSinceSave;

            @Override
            public void run() {
                if (!isRunning && !dragonKilledEnd) {
                    cancel();
                    return;
                }
                if (isRunning && ++secondsSinceSave >= RunStateManager.AUTOSAVE_SECONDS) {
                    secondsSinceSave = 0;
                    if (plugin.getRunStateManager() != null) {
                        plugin.getRunStateManager().saveAsync();
                    }
                }
                if (!isPaused) {
                    if (plugin.getStructureManager().isVillageSearchActive()
                            && plugin.getStructureManager().getFoundStructures().get("VILLAGE") == null) {
                        villageTimeElapsed++;
                    }
                }
                if (!plugin.getConfigManager().isHardcoreModeEnabled()) {
                    // These updates need to run even when paused to reflect progress.
                    plugin.getTaskManager().updateItemTasks();
                    plugin.getStructureManager().checkVillageTimeout();
                }
                Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * Starts the proximity scanner task.
     * This task periodically checks the surroundings of each player for specific structures like villages or lava pools.
     */
    private void startProximityScanner() {
        if (proximityScannerTask != null) proximityScannerTask.cancel();
        plugin.getTraceLogger().trace("scanner", "proximity_scanner_started",
                "village_radius", plugin.getConfigManager().getVillageBellRadius(),
                "lava_radius", plugin.getConfigManager().getLavaPoolRadius(),
                "lava_required_sources", plugin.getConfigManager().getLavaPoolRequiredSources());
        proximityScannerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (isPaused || !isRunning) return;

                boolean needsVillage = plugin.getStructureManager().isVillageSearchActive();
                boolean needsLava = plugin.getStructureManager().isLavaPoolSearchActive();
                if (!needsVillage && !needsLava) return;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (plugin.getConfigManager().isStartPreScanEnabled()) {
                        plugin.getStructureManager().scanLoadedPlayerChunk(player);
                    }
                    if (needsVillage) findNearbyBell(player);
                    if (needsLava) findNearbyLavaPool(player);
                }
            }
        }.runTaskTimer(plugin, 100L, 60L); // Runs every 3 seconds (60 ticks), starts after 5 seconds.
    }

    /**
     * Re-evaluates scanner tasks after a live config change.
     */
    public void refreshRuntimeConfig() {
        if (proximityScannerTask != null) {
            proximityScannerTask.cancel();
            proximityScannerTask = null;
        }
        plugin.getStructureManager().cancelPreScan();

        if (!isRunning || plugin.getConfigManager().isHardcoreModeEnabled()) {
            return;
        }

        startProximityScanner();
        if (plugin.getConfigManager().isStartPreScanEnabled()) {
            plugin.getStructureManager().preScanRequiredStructures();
        }
    }

    private void findNearbyBell(Player player) {
        // Already found?  Stop scanning.
        if (plugin.getStructureManager().getFoundStructures().get("VILLAGE") != null
                && !plugin.getStructureManager().isApproximateStructure("VILLAGE")) {
            return;
        }
        Location bell = findBellNear(player.getLocation(), plugin.getConfigManager().getVillageBellRadius());
        if (bell != null) {
            plugin.getStructureManager().structureFound(player, "VILLAGE", bell);
        }
    }

    /**
     * Finds a bell inside the cube of the given radius around a location. Bells are block
     * entities, so only the block-entity lists of the loaded chunks in range are inspected
     * instead of every block in the cube.
     */
    static Location findBellNear(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        for (int chunkX = (centerX - radius) >> 4; chunkX <= (centerX + radius) >> 4; chunkX++) {
            for (int chunkZ = (centerZ - radius) >> 4; chunkZ <= (centerZ + radius) >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                for (BlockState state : world.getChunkAt(chunkX, chunkZ).getTileEntities()) {
                    if (state.getType() == Material.BELL
                            && Math.abs(state.getX() - centerX) <= radius
                            && Math.abs(state.getY() - centerY) <= radius
                            && Math.abs(state.getZ() - centerZ) <= radius) {
                        return state.getLocation();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Scans for a significant number of lava source blocks to detect a lava pool.
     * Only loaded chunks are read, so the scanner never loads terrain.
     */
    private void findNearbyLavaPool(Player player) {
        final int radius = plugin.getConfigManager().getLavaPoolRadius();
        final int requiredSources = plugin.getConfigManager().getLavaPoolRequiredSources();
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        if (world == null) {
            return;
        }
        int centerX = playerLoc.getBlockX();
        int centerY = playerLoc.getBlockY();
        int centerZ = playerLoc.getBlockZ();
        int minY = Math.max(world.getMinHeight(), centerY - 4);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + 4);
        int lavaCount = 0;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.LAVA && block.getBlockData() instanceof Levelled level && level.getLevel() == 0) {
                        if (++lavaCount >= requiredSources) {
                            plugin.getStructureManager().structureFound(player, "LAVA_POOL", block.getLocation());
                            return;
                        }
                    }
                }
            }
        }
    }

    // =========================================================================================
    // Getters and State Checks
    // =========================================================================================

    /**
     * Applies settings.pause-when-empty after a player left. Must run after the quitting
     * player is no longer counted as online (one tick after the quit event).
     */
    public void handleServerEmptyCheck() {
        if (!plugin.getConfigManager().isPauseWhenEmptyEnabled() || !isRunning || isPaused || dragonKilledEnd) {
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            setPaused(true);
            autoPaused = true;
            logger.info("Timer paused automatically: no players online.");
        }
    }

    /** Resumes a run that was paused only because the server was empty. */
    public void handlePlayerReturn() {
        if (autoPaused && isRunning && isPaused) {
            setPaused(false);
            autoPaused = false;
            MessageUtil.broadcast(restoredRunWaiting
                    ? plugin.getConfigManager().getFormatted("messages.run-restored", "%time%", getFormattedTime())
                    : plugin.getConfigManager().getFormatted("commands.timer-resumed"));
            restoredRunWaiting = false;
            logger.info("Timer resumed automatically: a player joined.");
        }
    }

    public boolean isAutoPaused() {
        return autoPaused;
    }

    /** @return Elapsed run time in seconds, excluding pauses. */
    public long getTotalSeconds() {
        long elapsed = accumulatedNanos;
        if (segmentStartNanos >= 0) {
            elapsed += System.nanoTime() - segmentStartNanos;
        }
        return elapsed / 1_000_000_000L;
    }

    private void setPaused(boolean paused) {
        isPaused = paused;
        if (paused) {
            pauseClock();
        } else {
            resumeClock();
        }
    }

    private void resumeClock() {
        if (segmentStartNanos < 0) {
            segmentStartNanos = System.nanoTime();
        }
    }

    private void pauseClock() {
        if (segmentStartNanos >= 0) {
            accumulatedNanos += System.nanoTime() - segmentStartNanos;
            segmentStartNanos = -1;
        }
    }

    /**
     * Whether a player join may start a run (settings.start-on-first-join). After a run was
     * stopped or completed, an administrator starts the next one with /run start or /run reset.
     */
    public boolean isAutoStartAllowed() {
        return autoStartAllowed && !isRunning;
    }

    /** @return The speedrun logger instance. */
    public SpeedrunLogger getLogger() { return logger; }

    /** @return A map of event counters for logging purposes. */
    public Map<String, Integer> getCounters() { return counters; }

    /** Increments a named counter by one. */
    public void incrementCounter(String key) {
        counters.put(key, counters.getOrDefault(key, 0) + 1);
    }

    /** Sets the active compass listener used by casual-mode systems. */
    public void setCompassListener(CompassListener compassListener) {
        this.compassListener = compassListener;
    }

    /** @return The active casual-mode compass listener, or null when casual mode is disabled. */
    public CompassListener getCompassListener() {
        return compassListener;
    }

    /** @return The manager for casual mode features. */
    public CasualModeStructureManager getCasualModeStructureManager() {
        return casualModeStructureManager;
    }

    /** @return The total elapsed time of the run, formatted as HH:MM:SS. */
    public String getFormattedTime() { return TimeUtil.format(getTotalSeconds()); }

    /** @return The remaining time in seconds to find a village. */
    public long getVillageTimeRemaining() {
        return plugin.getConfigManager().getVillageTimeout() - villageTimeElapsed;
    }

    public long getVillageTimeElapsed() {
        return villageTimeElapsed;
    }

    /** @return True if the speedrun is currently active. */
    public boolean isRunning() { return isRunning; }

    /** @return True if the speedrun timer is paused. */
    public boolean isPaused() { return isPaused; }

    /** @return True if the run ended by killing the Ender Dragon. */
    public boolean isDragonKilledEnd() { return dragonKilledEnd; }
}
