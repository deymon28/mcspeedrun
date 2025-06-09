package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// =========================================================================================
// Scoreboard Manager
// Manages the creation and updating of player scoreboards in the speedrun plugin.
// =========================================================================================
class ScoreboardManager {
    private final Speedrun plugin;
    // Stores a scoreboard for each player, keyed by their UUID.
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();

    /**
     * Constructor for ScoreboardManager.
     * @param plugin The main Speedrun plugin instance.
     */
    public ScoreboardManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    /**
     * Updates the scoreboard for a given player.
     * If the player does not have a scoreboard, a new one is created.
     * The scoreboard displays game time, pause status, found structure locations,
     * and tasks relevant to the player's current world.
     *
     * @param player The player whose scoreboard needs to be updated.
     */
    public void updateScoreboard(@NotNull Player player) {
        // Get or create a new scoreboard for the player.
        Scoreboard board = playerBoards.computeIfAbsent(player.getUniqueId(), uuid -> Bukkit.getScoreboardManager().getNewScoreboard());

        // Unregister any existing objective named "speedrun" to ensure a clean slate.
        Objective objective = board.getObjective("speedrun");
        if(objective != null) {
            objective.unregister();
        }

        // Register a new objective for the scoreboard.
        objective = board.registerNewObjective("speedrun", Criteria.DUMMY, plugin.getConfigManager().getFormatted("scoreboard.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR); // Display it on the sidebar.

        // AtomicInteger to manage score decrementing for scoreboard lines.
        AtomicInteger score = new AtomicInteger(15);

        // --- Time and Status ---
        // Format and display the current game time.
        String timeString = plugin.getConfigManager().getFormattedText("scoreboard.time") + " §e" + plugin.getGameManager().getFormattedTime();
        objective.getScore(timeString).setScore(score.getAndDecrement());

        // Display "Paused" if the game is currently paused.
        if (plugin.getGameManager().isPaused()) {
            objective.getScore(plugin.getConfigManager().getFormattedText("scoreboard.paused")).setScore(score.getAndDecrement());
        }

        // Add a whitespace line for visual separation.
        objective.getScore(getWhitespace(score.get())).setScore(score.getAndDecrement());

        // --- Locations ---
        // Display header for locations.
        objective.getScore(plugin.getConfigManager().getFormattedText("scoreboard.locations-header")).setScore(score.getAndDecrement());

        // Iterate through found structures and display their status.
        for (Map.Entry<String, Location> entry : plugin.getStructureManager().getFoundStructures().entrySet()) {
            String line;
            if (entry.getValue() != null) {
                // If the structure is found, display its name and coordinates.
                line = plugin.getConfigManager().getFormattedText("scoreboard.location-found",
                        "%name%", entry.getKey(), "%coords%", LocationUtil.format(entry.getValue())); // Corrected varargs usage
            } else {
                // If the structure is not yet found.
                if (entry.getKey().equals("Village") && plugin.getStructureManager().isVillageSearchActive()) {
                    // Special handling for Village search with a timer.
                    String timer = plugin.getConfigManager().getFormattedText("scoreboard.village-timer", "%time%", TimeUtil.format(plugin.getGameManager().getVillageTimeRemaining()));
                    line = plugin.getConfigManager().getFormattedText("scoreboard.location-pending", "%name%", entry.getKey()) + " " + timer;
                } else {
                    // General pending location status.
                    line = plugin.getConfigManager().getFormattedText("scoreboard.location-pending", "%name%", entry.getKey());
                }
            }
            objective.getScore(line).setScore(score.getAndDecrement());
        }

        // Add another whitespace line for visual separation.
        objective.getScore(getWhitespace(score.get())).setScore(score.getAndDecrement());

        // --- Tasks based on player's world ---
        World.Environment playerWorld = player.getWorld().getEnvironment();
        List<Task> tasks = plugin.getTaskManager().getTasksForWorld(playerWorld);

        if (!tasks.isEmpty()) {
            // Display header for tasks in the player's current world.
            String headerKey = "scoreboard." + playerWorld.name().toLowerCase() + "-tasks-header";
            objective.getScore(plugin.getConfigManager().getFormattedText(headerKey)).setScore(score.getAndDecrement());

            // Iterate through tasks and display their completion status or progress.
            for (Task task : tasks) {
                String line;
                if (task.isCompleted()) {
                    // Task is completed.
                    line = plugin.getConfigManager().getFormattedText("scoreboard.task-complete", "%name%", task.displayName); // Corrected varargs usage
                } else {
                    // Task is in progress, display current progress and required amount.
                    line = plugin.getConfigManager().getFormattedText("scoreboard.task-line",
                            "%name%", task.displayName,
                            "%progress%", String.valueOf(task.progress),
                            "%required%", String.valueOf(task.requiredAmount)); // Corrected varargs usage
                }
                objective.getScore(line).setScore(score.getAndDecrement());
            }
        }

        // Set the updated scoreboard to the player.
        player.setScoreboard(board);
    }

    /**
     * Generates a unique whitespace string for scoreboard line separation.
     * Uses different color codes to ensure uniqueness for each line.
     * @param i An integer to vary the color code.
     * @return A unique whitespace string.
     */
    private String getWhitespace(int i) {
        return "§" + "abcdef".charAt(i % 6) + "§r";
    }
}
