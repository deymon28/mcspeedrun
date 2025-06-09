package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level; // Import for logging levels
import java.util.stream.Collectors;

// =========================================================================================
// Game Logic Manager
// =========================================================================================
public class GameManager {
    private final Speedrun plugin;
    private long totalSeconds = 0;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private BukkitTask mainTimerTask;
    private long villageTimeElapsed = 0;

    public GameManager(Speedrun plugin) {
        this.plugin = plugin;
        // Logic modified to remove the empty if statement.
        // The run should only start immediately if startOnFirstJoin is explicitly false.
        if (!plugin.getConfigManager().isStartOnFirstJoin()) {
            startRun();
        }
        // If plugin.getConfigManager().isStartOnFirstJoin() is true,
        // the GameListener is responsible for calling startRun() later.
    }

    public void startRun() {
        if (isRunning) return;
        isRunning = true;
        isPaused = false;
        totalSeconds = 0;
        villageTimeElapsed = 0;

        plugin.getTaskManager().reloadTasks(); // This also resets progress
        plugin.getStructureManager().reset();

        startTimer();
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

        if (dragonKilled) {
            isPaused = true;
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.dragon-slain"));
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.final-time", "%time%", getFormattedTime()));
        }

        if(plugin.getConfigManager().isLogAttemptsEnabled()) {
            logAttempt(dragonKilled);
        }

        isRunning = false;
    }

    private void logAttempt(boolean completed) {
        File logFile = new File(plugin.getDataFolder(), "logs/speedrun-log.txt");
        File parentDir = logFile.getParentFile();

        // Check if directory creation was successful.
        if (!parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                plugin.getLogger().warning("Failed to create log directory: " + parentDir.getAbsolutePath());
                return; // Exit if directory creation failed.
            }
        }

        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {

            pw.println("--- Speedrun Attempt ---");
            pw.println("Timestamp: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println("Outcome: " + (completed ? "Completed (Dragon Slain)" : "Ended/Reset"));
            pw.println("Final Time: " + getFormattedTime());
            pw.println("Players (" + Bukkit.getOnlinePlayers().size() + "): " + Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
            pw.println("Completed Tasks:");
            plugin.getTaskManager().getAllTasks().stream()
                    .filter(Task::isCompleted)
                    .forEach(task -> pw.println("  - " + task.displayName + " (" + task.taskType + ":" + task.key + ")"));
            pw.println("Found Structures:");
            plugin.getStructureManager().getFoundStructures().forEach((name, loc) -> {
                pw.println("  - " + name + ": " + LocationUtil.format(loc));
            });
            pw.println("------------------------\n");

        } catch (IOException e) {
            // Replaced printStackTrace() with proper logging.
            plugin.getLogger().log(Level.SEVERE, "Could not write to speedrun log file!", e);
        }
    }


    public void togglePause() {
        if (!isRunning) return;
        isPaused = !isPaused;
        Bukkit.broadcast(plugin.getConfigManager().getFormatted(isPaused ? "commands.timer-paused" : "commands.timer-resumed"));
    }

    private void startTimer() {
        if (mainTimerTask != null) mainTimerTask.cancel();

        mainTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                if (!isPaused) {
                    totalSeconds++;

                    if(plugin.getStructureManager().isVillageSearchActive()) {
                        villageTimeElapsed++;
                    }
                }

                plugin.getTaskManager().updateItemTasks(); // Always update tasks
                plugin.getStructureManager().checkVillageTimeout(); // Check for timeout

                Bukkit.getOnlinePlayers().forEach(player -> plugin.getScoreboardManager().updateScoreboard(player));
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public String getFormattedTime() {
        return TimeUtil.format(totalSeconds);
    }

    public long getVillageTimeRemaining() {
        return plugin.getConfigManager().getVillageTimeout() - villageTimeElapsed;
    }

    public boolean isRunning() { return isRunning; }
    public boolean isPaused() { return isPaused; }
}
