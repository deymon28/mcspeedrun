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

public class ScoreboardManager {
    private final Speedrun plugin;
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();
    private int whitespaceCounter; // Used to generate unique blank lines

    public ScoreboardManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void updateScoreboard(@NotNull Player player) {
        Scoreboard board = playerBoards.computeIfAbsent(player.getUniqueId(), uuid -> Bukkit.getScoreboardManager().getNewScoreboard());
        whitespaceCounter = 0; // Reset for each update to ensure consistency

        Objective objective = board.getObjective("speedrun");
        if (objective != null) {
            objective.unregister();
        }
        objective = board.registerNewObjective("speedrun", Criteria.DUMMY, plugin.getConfigManager().getFormatted("scoreboard.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        AtomicInteger score = new AtomicInteger(15);
        ConfigManager cm = plugin.getConfigManager();

        // --- Time ---
        String timeString = cm.getFormattedText("scoreboard.time") + " §e" + plugin.getGameManager().getFormattedTime();
        objective.getScore(timeString).setScore(score.getAndDecrement());

        // --- Status Line (Paused / Ended / Blank) ---
        String statusLine;
        if (plugin.getGameManager().isDragonKilledEnd()) {
            statusLine = cm.getFormattedText("scoreboard.s-end");
        } else if (plugin.getGameManager().isPaused()) {
            statusLine = cm.getFormattedText("scoreboard.paused");
        } else {
            statusLine = getWhitespace(); // Use a unique blank line
        }
        objective.getScore(statusLine).setScore(score.getAndDecrement());

        // --- Locations ---
        Map<String, Location> foundStructures = plugin.getStructureManager().getFoundStructures();
        if (!foundStructures.isEmpty()) {
            // objective.getScore(getWhitespace()).setScore(score.getAndDecrement()); // Separator
            objective.getScore(cm.getFormattedText("scoreboard.locations-header")).setScore(score.getAndDecrement());

            for (Map.Entry<String, Location> entry : foundStructures.entrySet()) {
                // Hide Lava Pool if Nether Portal is lit
                if (entry.getKey().equals("Lava Pool") && plugin.getStructureManager().isNetherPortalLit()) {
                    continue;
                }

                String line;
                Location displayLoc = entry.getValue();

                // DYNAMIC PORTAL: Check player world for Nether Portal location
                if (entry.getKey().equals("Nether Portal")) {
                    if (player.getWorld().getEnvironment() == World.Environment.NETHER) {
                        displayLoc = plugin.getStructureManager().getNetherPortalExitLocation();
                    }
                }

                if (displayLoc != null) {
                    line = cm.getFormattedText("scoreboard.location-found", "%name%", entry.getKey(), "%coords%", LocationUtil.format(displayLoc));
                } else {
                    if (entry.getKey().equals("Village") && plugin.getStructureManager().isVillageSearchActive()) {
                        String timer = cm.getFormattedText("scoreboard.village-timer", "%time%", TimeUtil.formatMinutesSeconds(plugin.getGameManager().getVillageTimeRemaining()));
                        line = cm.getFormattedText("scoreboard.location-pending", "%name%", entry.getKey()) + " " + timer;
                    } else {
                        line = cm.getFormattedText("scoreboard.location-pending", "%name%", entry.getKey());
                    }
                }
                objective.getScore(line).setScore(score.getAndDecrement());
            }
        }

        // --- Tasks ---
        World.Environment playerWorld = player.getWorld().getEnvironment();
        List<Task> tasks = plugin.getTaskManager().getTasksForWorld(playerWorld);

        if (!tasks.isEmpty()) {
            objective.getScore(getWhitespace()).setScore(score.getAndDecrement()); // Separator
            String headerKey = "scoreboard." + playerWorld.name().toLowerCase() + "-tasks-header";
            objective.getScore(cm.getFormattedText(headerKey)).setScore(score.getAndDecrement());

            for (Task task : tasks) {
                String line;
                if (task.isCompleted()) {
                    line = cm.getFormattedText("scoreboard.task-complete", "%name%", task.displayName);
                } else {
                    line = cm.getFormattedText("scoreboard.task-line", "%name%", task.displayName, "%progress%", String.valueOf(task.progress), "%required%", String.valueOf(task.requiredAmount));
                }
                objective.getScore(line).setScore(score.getAndDecrement());
            }
        }

        player.setScoreboard(board);
    }

    /**
     * Generates a unique whitespace string using an incrementing counter.
     * This prevents scoreboard line conflicts.
     * @return A unique whitespace string (e.g., "§r ", "§r §r ", etc.).
     */
    private String getWhitespace() {
        whitespaceCounter++;
        return "§r".repeat(whitespaceCounter) + " ";
    }
}