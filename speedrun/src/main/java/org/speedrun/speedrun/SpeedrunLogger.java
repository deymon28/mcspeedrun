package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.File;
import org.speedrun.speedrun.utils.AsyncLineWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

/**
 * Handles logging of speedrun events to files.
 * Creates both a human-readable (.log) file and a machine-readable (.json) file for each run.
 */
public class SpeedrunLogger {
    private final Speedrun plugin;
    private AsyncLineWriter writer;
    private AsyncLineWriter structuredWriter;
    private boolean active = false;

    public SpeedrunLogger(Speedrun plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts a new logging session.
     * Creates new log files with a timestamp and sets the logger to active.
     */
    public void start() {
        // Stop any previous, unclosed session to prevent data corruption.
        if (active) stop("Interrupted", "N/A");

        try {
            File logDir = new File(plugin.getDataFolder(), "logs");
            if (!logDir.exists() && !logDir.mkdirs()) {
                    plugin.getLogger().severe("Could not create logs directory!");
                    return;
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File logFile = new File(logDir, "speedrun-" + timestamp + ".log");
            File jsonFile = new File(logDir, "speedrun-" + timestamp + ".json");

            this.writer = new AsyncLineWriter(logFile.toPath(), "Speedrun-RunLog", plugin.getLogger()::warning);
            this.structuredWriter = new AsyncLineWriter(jsonFile.toPath(), "Speedrun-RunLogJson", plugin.getLogger()::warning);
            this.active = true;


            info("================ SPEEDRUN LOG SESSION STARTED ================");
            logRunMetadata();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize speedrun logger!", e);
            this.active = false;
            closeWriters(); // the text log may already be open when the JSON log fails
        }
    }

    /**
     * Stops the current logging session.
     * Writes final summary information and closes the file writers.
     *
     * @param outcome A string describing how the run ended (e.g., "Completed", "Reset").
     * @param finalTime The final time of the speedrun.
     */
    public void stop(String outcome, String finalTime) {
        if (!active) return;

        info("================= SPEEDRUN LOG SESSION ENDED =================");
        log(Level.INFO, "Final Outcome: " + outcome);
        log(Level.INFO, "Final Time: " + finalTime);
        logPlayerInventories();

        JSONObject summary = generateSummary(outcome, finalTime);
        logStructuredEvent(summary);

        active = false;
        closeWriters();
    }

    /**
     * Closes the PrintWriter resources safely.
     */
    private void closeWriters() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
        if (structuredWriter != null) {
            structuredWriter.close();
            structuredWriter = null;
        }
    }

    /**
     * Logs the inventories of all online players at the end of the run.
     */
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

    private static String minecraftVersion() {
        return Bukkit.getBukkitVersion().split("-", 2)[0];
    }

    private void logRunMetadata() {
        JSONObject json = new JSONObject();
        json.put("event", "run_metadata");
        json.put("minecraft_version", minecraftVersion());
        json.put("bukkit_version", Bukkit.getVersion());
        if (!Bukkit.getWorlds().isEmpty()) {
            World world = Bukkit.getWorlds().get(0);
            json.put("world", world.getName());
            json.put("seed", world.getSeed());
        }
        logStructuredEvent(json);
        info("Minecraft version: " + minecraftVersion());
        if (!Bukkit.getWorlds().isEmpty()) {
            World world = Bukkit.getWorlds().get(0);
            info("World seed (" + world.getName() + "): " + world.getSeed());
        }
    }

    /**
     * Generates a final JSON summary object with all collected statistics.
     */
    private JSONObject generateSummary(String outcome, String finalTime){
        JSONObject summary = new JSONObject();
        summary.put("event", "stat_summary");
        summary.put("outcome", outcome);
        summary.put("final_time", finalTime);

        Map<String, Integer> counters = plugin.getGameManager().getCounters();

        for(Map.Entry<String, Integer> entry : counters.entrySet()){
            summary.put(entry.getKey(), entry.getValue());
        }

        if (plugin.getCasualGameModeManager() != null) {
            boolean isCasualModeEnabled = plugin.getCasualGameModeManager().isCasualModeActive();
            summary.put("casual_mode_enabled", isCasualModeEnabled);
            plugin.getLogger().info("Logging summary: Casual mode enabled = " + isCasualModeEnabled);
        } else {
            summary.put("casual_mode_enabled", false); // Default to false if manager not initialized
            plugin.getLogger().warning("CasualGameModeManager is null when generating summary. 'casual_mode_enabled' set to false.");
        }

        return summary;
    }

    /**
     * Logs an informational message to the text log file.
     */
    public void info(String message) {
        log(Level.INFO, message);
    }

    public void warning(String message) {
        log(Level.WARNING, message);
    }

    /**
     * Writes a message to the human-readable .log file.
     */
    private void log(Level level, String message) {
        if (!active || writer == null) return;
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        writer.write(String.format("[%s] [%s]: %s", timestamp, level.getName(), message));
    }

    /**
     * Writes a JSON object to the machine-readable .json file.
     */
    public void logStructuredEvent(JSONObject json) {
        if(!active || structuredWriter == null) return;
        json.put("timestamp", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        structuredWriter.write(json.toString());
    }

    // =========================================================================================
    // Specific Event Logging Methods
    // =========================================================================================

    public void logStructureFound(@Nullable Player player, String structureKey, Location location){
        String name = "Server";

        if(player != null) {
            name = player.getName();
        }

        JSONObject json = new JSONObject();
        json.put("event", "structure_found");
        json.put("player", name);
        json.put("structure", structureKey);
        json.put("x", location.getX());
        json.put("y", location.getY());
        json.put("z", location.getZ());
        json.put("world", location.getWorld().getName());

        logStructuredEvent(json);
    }

    public void logPlayerDeath(Player player, String deathCause, Location loc) {
        String name = player.getName();
        String world = player.getWorld().getName();

        JSONObject json = new JSONObject();
        json.put("event", "player_death");
        json.put("player", name);
        json.put("death_by", deathCause);
        json.put("x", loc.getX());
        json.put("y", loc.getY());
        json.put("z", loc.getZ());
        json.put("world", world);

        logStructuredEvent(json);
    }

    public void logMobKill(Player killer, String mob, Location loc) {
        String world = loc.getWorld().getName();

        JSONObject json = new JSONObject();
        json.put("event", "mob_kill");
        json.put("player", killer.getName());
        json.put("mob", mob);
        json.put("x", loc.getX());
        json.put("y", loc.getY());
        json.put("z", loc.getZ());
        json.put("world", world);

        logStructuredEvent(json);
    }

    public void logMilestone(String playerName, String milestone){
        long timestamp = System.currentTimeMillis();

        JSONObject json = new JSONObject();
        json.put("event", "advancement");
        json.put("player", playerName);
        json.put("name", milestone);
        json.put("m_seconds", timestamp);

        logStructuredEvent(json);
    }

    public void logPlayerJoinOrQuit(String playerName, String type) {
        long timestamp = System.currentTimeMillis();

        JSONObject json = new JSONObject();
        json.put("event", "player_join_quit");
        json.put("player", playerName);
        json.put("type", type);
        json.put("m_seconds", timestamp);

        logStructuredEvent(json);
    }

    public void logPlayerPortalFromTo(String playerName, World.Environment from, World.Environment to) {
        long timestamp = System.currentTimeMillis();

        JSONObject json = new JSONObject();
        json.put("event", "player_portal");
        json.put("player", playerName);
        json.put("from_world", from);
        json.put("to_world", to);
        json.put("m_seconds", timestamp);

        logStructuredEvent(json);
    }

    public void logChunkBiome(Player player, int chunkX, int chunkZ, String biome) {
        JSONObject json = new JSONObject();
        json.put("event", "chunk_biome");
        json.put("player", player.getName());
        json.put("chunk_x", chunkX);
        json.put("chunk_z", chunkZ);
        json.put("biome", biome);

        logStructuredEvent(json);
    }

    public void logPlayerBlockPosition(Player player, Location loc) {
        JSONObject json = new JSONObject();
        json.put("event", "player_block_position");
        json.put("player", player.getName());
        json.put("world", loc.getWorld().getName());
        json.put("x", loc.getBlockX());
        json.put("y", loc.getBlockY());
        json.put("z", loc.getBlockZ());
        json.put("chunk_x", loc.getBlockX() >> 4);
        json.put("chunk_z", loc.getBlockZ() >> 4);

        logStructuredEvent(json);
    }

    public void logCompletedTask(String name, Integer progressValue) {
        long timestamp = System.currentTimeMillis();

        JSONObject json = new JSONObject();
        json.put("event", "task_completed");
        json.put("name", name);
        json.put("progress_value", progressValue);
        json.put("m_seconds", timestamp);

        logStructuredEvent(json);
    }

}
