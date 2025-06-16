package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

public class SpeedrunLogger {
    private final Speedrun plugin;
    private PrintWriter writer;
    private boolean active = false;

    public SpeedrunLogger(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (active) stop("Interrupted", "N/A"); // Закрываем предыдущую сессию, если она была активна
        try {
            File logDir = new File(plugin.getDataFolder(), "logs");
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) { // Проверяем результат
                    plugin.getLogger().severe("Could not create logs directory!");
                    return;
                }
            }
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File logFile = new File(logDir, "speedrun-" + timestamp + ".log");
            this.writer = new PrintWriter(new FileWriter(logFile, true));
            this.active = true;
            info("================ SPEEDRUN LOG SESSION STARTED ================");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize speedrun logger!", e);
            this.active = false;
        }
    }

    public void stop(String outcome, String finalTime) {
        if (active && writer != null) {
            info("================= SPEEDRUN LOG SESSION ENDED =================");
            log(Level.INFO, "Final Outcome: " + outcome);
            log(Level.INFO, "Final Time: " + finalTime);
            logPlayerInventories();
            writer.flush();
            writer.close();
            active = false;
            writer = null;
        }
    }

    private void logPlayerInventories() {
        if (!active) return;
        log(Level.INFO, "--- Player Inventories at End of Run ---");
        for (Player p : Bukkit.getOnlinePlayers()) {
            log(Level.INFO, "Player: " + p.getName() + " (EXP Levels: " + p.getLevel() + ")");
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack item = p.getInventory().getItem(i);
                if (item != null && !item.getType().isAir()) {
                    log(Level.INFO, "  Slot " + i + ": " + item.getType().name() + " x" + item.getAmount());
                }
            }
        }
        log(Level.INFO, "------------------------------------------");
    }

    public void info(String message) {
        log(Level.INFO, message);
    }

    public void warning(String message) {
        log(Level.WARNING, message);
    }

    private void log(Level level, String message) {
        if (!active || writer == null) return;
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        writer.println(String.format("[%s] [%s]: %s", timestamp, level.getName(), message));
        writer.flush();
    }
}