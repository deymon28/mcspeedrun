package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
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
    private final SpeedrunLogger logger;
    private final Map<String, Integer> counters = new HashMap<>();

    public GameManager(Speedrun plugin) {
        this.plugin = plugin;
        this.logger = new SpeedrunLogger(plugin);
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
        counters.clear();

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            logger.start();
        }
        plugin.getTaskManager().reloadTasks();
        plugin.getStructureManager().reset();

        startTimer();
        startProximityScanner();
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.run-started"));
        logger.info("Speedrun started. Players: " + Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
    }

    public void resetRun() {
        logger.info("Speedrun is being reset by an admin.");
        stopRun(false);
        startRun();
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("commands.run-reset"));
    }

    public void stopRun(boolean dragonKilled) {
        if (!isRunning && !dragonKilled) return;
        if (mainTimerTask != null) mainTimerTask.cancel();
        if (proximityScannerTask != null) proximityScannerTask.cancel();

        String outcome = dragonKilled ? "Completed (Dragon Slain)" : "Ended/Reset";
        String finalTime = getFormattedTime();

        if (dragonKilled) {
            isPaused = true;
            dragonKilledEnd = true;
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.dragon-slain"));
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.final-time", "%time%", finalTime));
            Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
        }

        if (plugin.getConfigManager().isLogAttemptsEnabled()) {
            logger.stop(outcome, finalTime);
        }
        isRunning = false;
    }

    public void togglePause() {
        if (!isRunning || dragonKilledEnd) return;
        isPaused = !isPaused;
        Bukkit.broadcast(plugin.getConfigManager().getFormatted(isPaused ? "commands.timer-paused" : "commands.timer-resumed"));
        logger.info("Timer " + (isPaused ? "paused." : "resumed."));
    }

    private void startTimer() {
        if (mainTimerTask != null) mainTimerTask.cancel();
        mainTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning && !dragonKilledEnd) { cancel(); return; }
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

    private void findNearbyBell(Player player) { scanForBlock(player, plugin.getConfigManager().getVillageBellRadius(), Material.BELL, "VILLAGE"); }
    private void findNearbyLavaPool(Player player) {
        final int radius = plugin.getConfigManager().getLavaPoolRadius();
        final int requiredSources = plugin.getConfigManager().getLavaPoolRequiredSources();
        int lavaCount = 0;
        Location playerLoc = player.getLocation();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = playerLoc.clone().add(x, y, z).getBlock();
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

    private void scanForBlock(Player player, int radius, Material material, String structureKey) {
        Location playerLoc = player.getLocation();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = playerLoc.clone().add(x,y,z).getBlock();
                    if (block.getType() == material) {
                        plugin.getStructureManager().structureFound(player, structureKey, block.getLocation());
                        return;
                    }
                }
            }
        }
    }

    public SpeedrunLogger getLogger() {
        return logger;
    }
    // log counters
    public Map<String, Integer> getCounters() {
        return counters;
    }
    public void incrementCounter(String key) {
        counters.put(key, counters.getOrDefault(key, 0) + 1);
    }

    public String getFormattedTime() { return TimeUtil.format(totalSeconds); }
    public long getVillageTimeRemaining() { return plugin.getConfigManager().getVillageTimeout() - villageTimeElapsed; }
    public boolean isRunning() { return isRunning; }
    public boolean isPaused() { return isPaused; }
    public boolean isDragonKilledEnd() { return dragonKilledEnd; }
}