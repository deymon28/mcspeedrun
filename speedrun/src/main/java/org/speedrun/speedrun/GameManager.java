package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameManager {
    private final Speedrun plugin;
    private long totalSeconds = 0;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private BukkitTask mainTimerTask;
    private BukkitTask proximityScannerTask; // New task for scanning
    private long villageTimeElapsed = 0;
    private boolean dragonKilledEnd = false; // Changed to private

    public GameManager(Speedrun plugin) {
        this.plugin = plugin;
        if (!plugin.getConfigManager().isStartOnFirstJoin()) {
            startRun();
        }
    }

    public void startRun() {
        if (isRunning) return;
        isRunning = true;
        isPaused = false;
        totalSeconds = 0;
        villageTimeElapsed = 0;
        dragonKilledEnd = false; // FIX: Ensure end state is reset

        plugin.getTaskManager().reloadTasks();
        plugin.getStructureManager().reset();

        startTimer();
        startProximityScanner(); // Start the new scanner
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.run-started"));
    }

    public void resetRun() {
        stopRun(false);
        startRun();
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("commands.run-reset"));
    }

    public void stopRun(boolean dragonKilled) {
        if (!isRunning) return;

        if (mainTimerTask != null) {
            mainTimerTask.cancel();
            mainTimerTask = null;
        }
        if (proximityScannerTask != null) {
            proximityScannerTask.cancel();
            proximityScannerTask = null;
        }

        if (dragonKilled) {
            isPaused = true; // Pause the timer visually
            dragonKilledEnd = true; // Set the final end state
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.dragon-slain"));
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.final-time", "%time%", getFormattedTime()));
            Bukkit.getOnlinePlayers().forEach(player -> plugin.getScoreboardManager().updateScoreboard(player));
        }

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            logAttempt(dragonKilled);
        }
        isRunning = false; // Set to false after logging so getFormattedTime is correct
    }

    private void logAttempt(boolean completed) {
        File logFile = new File(plugin.getDataFolder(), "logs/speedrun-log.txt");
        File parentDir = logFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            plugin.getLogger().warning("Failed to create log directory: " + parentDir.getAbsolutePath());
            return;
        }
        try (FileWriter fw = new FileWriter(logFile, true); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("--- Speedrun Attempt ---");
            pw.println("Timestamp: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println("Outcome: " + (completed ? "Completed (Dragon Slain)" : "Ended/Reset"));
            pw.println("Final Time: " + getFormattedTime());
            pw.println("Players (" + Bukkit.getOnlinePlayers().size() + "): " + Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
            pw.println("Completed Tasks:");
            plugin.getTaskManager().getAllTasks().stream()
                    .filter(Task::isCompleted)
                    .forEach(task -> pw.println(" - " + task.displayName + " (" + task.taskType + ":" + task.key + ")"));
            pw.println("Found Structures:");
            plugin.getStructureManager().getFoundStructures().forEach((name, loc) -> {
                pw.println(" - " + name + ": " + LocationUtil.format(loc));
            });
            pw.println("------------------------\n");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not write to speedrun log file!", e);
        }
    }

    public void togglePause() {
        if (!isRunning || dragonKilledEnd) return; // Can't pause/unpause after run ends
        isPaused = !isPaused;
        Bukkit.broadcast(plugin.getConfigManager().getFormatted(isPaused ? "commands.timer-paused" : "commands.timer-resumed"));
    }

    private void startTimer() {
        if (mainTimerTask != null) mainTimerTask.cancel();
        mainTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning && !dragonKilledEnd) { // Keep updating scoreboard even if run stopped but not ended
                    cancel();
                    return;
                }
                if (!isPaused) {
                    totalSeconds++;
                    if (plugin.getStructureManager().isVillageSearchActive()) {
                        villageTimeElapsed++;
                    }
                }
                plugin.getTaskManager().updateItemTasks();
                plugin.getStructureManager().checkVillageTimeout();
                Bukkit.getOnlinePlayers().forEach(player -> plugin.getScoreboardManager().updateScoreboard(player));
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * NEW: Starts a repeating task to scan for structures near players.
     * This is more performant than using PlayerMoveEvent.
     */
    private void startProximityScanner() {
        if (proximityScannerTask != null) proximityScannerTask.cancel();
        proximityScannerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (isPaused || !isRunning) return;

                boolean villageSearch = plugin.getStructureManager().isVillageSearchActive();
                boolean lavaSearch = plugin.getStructureManager().isLavaPoolSearchActive();

                if (!villageSearch && !lavaSearch) return; // No need to scan

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (villageSearch) findNearbyBell(player);
                    if (lavaSearch) findNearbyLavaPool(player);
                }
            }
        }.runTaskTimer(plugin, 100L, 60L); // Scan every 3 seconds after an initial 5s delay
    }

    private void findNearbyBell(Player player) {
        final int radius = 32;
        Location playerLoc = player.getLocation();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = playerLoc.clone().add(x, y, z).getBlock();
                    if (block.getType() == Material.BELL) {
                        plugin.getStructureManager().structureFound(player, "Village", "Village", block.getLocation());
                        return; // Found, no need to check further
                    }
                }
            }
        }
    }

    private void findNearbyLavaPool(Player player) {
        final int radius = 16; // Smaller radius for lava
        final int requiredSourceBlocks = 12; // e.g., require at least 10 lava sources
        int lavaCount = 0;
        Location playerLoc = player.getLocation();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) { // Check only a few blocks vertically
                for (int z = -radius; z <= radius; z++) {
                    Block block = playerLoc.clone().add(x, y, z).getBlock();
                    if (block.getType() == Material.LAVA) {
                        BlockData data = block.getBlockData();
                        if (data instanceof Levelled level) {
                            if (level.getLevel() == 0) { // Только source-блоки имеют уровень 0
                                lavaCount++;
                                if (lavaCount >= requiredSourceBlocks) {
                                    plugin.getStructureManager().structureFound(player, "Lava Pool", "Lava Pool", block.getLocation());
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    public String getFormattedTime() { return TimeUtil.format(totalSeconds); }
    public long getVillageTimeRemaining() { return plugin.getConfigManager().getVillageTimeout() - villageTimeElapsed; }
    public boolean isRunning() { return isRunning; }
    public boolean isPaused() { return isPaused; }
    public boolean isDragonKilledEnd() { return dragonKilledEnd; } // Public getter for scoreboard
}