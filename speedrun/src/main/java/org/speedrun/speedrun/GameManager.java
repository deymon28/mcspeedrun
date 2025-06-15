package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
    private BukkitTask proximityScannerTask;
    private long villageTimeElapsed = 0;
    private boolean dragonKilledEnd = false;
    private LogFileHandler logFileHandler;

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
        dragonKilledEnd = false;

        plugin.getTaskManager().reloadTasks();
        plugin.getStructureManager().reset();

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            try {
                File logFile = new File(plugin.getDataFolder(), "logs/speedrun-log.txt");

//                File parentDir = logFile.getParentFile();
//                if (!parentDir.exists() && !parentDir.mkdirs()) {
//                    plugin.getLogger().severe("Failed to create log directory: " + parentDir.getAbsolutePath());
//                    return;
//                }
//                logFileHandler = new LogFileHandler(logFile.getAbsolutePath());
//                plugin.getLogger().addHandler(logFileHandler);

                logFile.getParentFile().mkdirs(); // Ensure directory exists
                logFileHandler = new LogFileHandler(logFile.getAbsolutePath());
                plugin.getLogger().addHandler(logFileHandler);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to start console logging for speedrun.", e);
            }
        }

        startTimer();
        startProximityScanner();
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.run-started"));
    }

    public void resetRun() {
        stopRun(false);
        startRun();
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("commands.run-reset"));
    }

    public void stopRun(boolean dragonKilled) {
        if (!isRunning && !dragonKilled) return;
        if (mainTimerTask != null) mainTimerTask.cancel();
        if (proximityScannerTask != null) proximityScannerTask.cancel();

        if (logFileHandler != null) {
            logFileHandler.flush();
            logFileHandler.close();
            plugin.getLogger().removeHandler(logFileHandler);
            logFileHandler = null;
        }

        if (dragonKilled) {
            isPaused = true;
            dragonKilledEnd = true;
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.dragon-slain"));
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.final-time", "%time%", getFormattedTime()));

            Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
        }
        if (plugin.getConfigManager().isLogAttemptsEnabled()) logAttempt(dragonKilled);
        isRunning = false;
    }

    private void logAttempt(boolean completed) {
        File logFile = new File(plugin.getDataFolder(), "logs/speedrun-log.txt");
        File parentDir = logFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            plugin.getLogger().warning("Failed to create log directory.");
            return;
        }
        try (FileWriter fw = new FileWriter(logFile, true); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("--- Speedrun Attempt ---");
            pw.println("Timestamp: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println("Outcome: " + (completed ? "Completed" : "Ended/Reset"));
            pw.println("Final Time: " + getFormattedTime());
            pw.println("Players (" + Bukkit.getOnlinePlayers().size() + "): " + Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
            pw.println("Completed Tasks:");
            plugin.getTaskManager().getAllTasks().stream().filter(Task::isCompleted).forEach(task -> pw.println(" - " + task.displayName));
            pw.println("Found Structures:");
            plugin.getStructureManager().getFoundStructures().forEach((name, loc) -> pw.println(" - " + name + ": " + LocationUtil.format(loc)));
            pw.println("------------------------\n");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not write to log file!", e);
        }
    }

    public void togglePause() {
        if (!isRunning || dragonKilledEnd) return;
        isPaused = !isPaused;
        Bukkit.broadcast(plugin.getConfigManager().getFormatted(isPaused ? "commands.timer-paused" : "commands.timer-resumed"));
    }

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
                    if (plugin.getStructureManager().isVillageSearchActive()) villageTimeElapsed++;
                }
                plugin.getTaskManager().updateItemTasks();
                plugin.getStructureManager().checkVillageTimeout();
                Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

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
        }.runTaskTimer(plugin, 100L, 60L);
    }

    private void findNearbyBell(Player player) {
        // Use configurable radius
        final int radius = plugin.getConfigManager().getVillageBellRadius();
        scanForBlock(player, radius, Material.BELL, "VILLAGE");
    }

    private void findNearbyLavaPool(Player player) {
        // Use configurable radius and source count
        final int radius = plugin.getConfigManager().getLavaPoolRadius();
        final int requiredSources = plugin.getConfigManager().getLavaPoolRequiredSources();
        int lavaCount = 0;
        Location playerLoc = player.getLocation();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = playerLoc.clone().add(x, y, z).getBlock();
                    if (block.getType() == Material.LAVA) {
                        if (block.getBlockData() instanceof Levelled level && level.getLevel() == 0) {
                            lavaCount++;
                            if (lavaCount >= requiredSources) {
                                plugin.getStructureManager().structureFound(player, "LAVA_POOL", block.getLocation());
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

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

    public String getFormattedTime() { return TimeUtil.format(totalSeconds); }
    public long getVillageTimeRemaining() { return plugin.getConfigManager().getVillageTimeout() - villageTimeElapsed; }
    public boolean isRunning() { return isRunning; }
    public boolean isPaused() { return isPaused; }
    public boolean isDragonKilledEnd() { return dragonKilledEnd; }
}