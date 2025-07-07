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
import java.util.Map;
import java.util.logging.Level;
import org.json.JSONObject;

public class SpeedrunLogger {
    private final Speedrun plugin;
    private PrintWriter writer;
    private PrintWriter structuredWriter;
    private boolean active = false;

    public SpeedrunLogger(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (active) stop("Interrupted", "N/A"); // Закрываем предыдущую сессию, если она была активна
        try {
            File logDir = new File(plugin.getDataFolder(), "logs");
            if (!logDir.exists() && !logDir.mkdirs()) {
                    plugin.getLogger().severe("Could not create logs directory!");
                    return;
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File logFile = new File(logDir, "speedrun-" + timestamp + ".log");
            File jsonFile = new File(logDir, "speedrun-" + timestamp + ".json");

            this.writer = new PrintWriter(new FileWriter(logFile, true));
            this.structuredWriter = new PrintWriter(new FileWriter(jsonFile, true));
            this.active = true;


            info("================ SPEEDRUN LOG SESSION STARTED ================");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize speedrun logger!", e);
            this.active = false;
        }
    }

    public void stop(String outcome, String finalTime) {
        if (active) {
            info("================= SPEEDRUN LOG SESSION ENDED =================");
            log(Level.INFO, "Final Outcome: " + outcome);
            log(Level.INFO, "Final Time: " + finalTime);
            logPlayerInventories();

            JSONObject summary = generateSummary(outcome, finalTime);
            logStructuredEvent(summary);

            active = false;
        }
        if( writer != null){
            writer.flush();
            writer.close();
            writer = null;
        }
        if(structuredWriter != null){
            structuredWriter.flush();
            structuredWriter.close();
            structuredWriter = null;
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

    private JSONObject generateSummary(String outcome, String finalTime){
        JSONObject summary = new JSONObject();
        summary.put("event", "stat_summary");
        summary.put("outcome", outcome);
        summary.put("final_time", finalTime);

        Map<String, Integer> counters = plugin.getGameManager().getCounters();

        for(Map.Entry<String, Integer> entry : counters.entrySet()){
            summary.put(entry.getKey(), entry.getValue());
        }

        return summary;
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

    public void logStructuredEvent(JSONObject json) {
        if(!active || writer == null) return;
        json.put("timestamp", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        structuredWriter.println(json.toString());
        structuredWriter.flush();
    }
}