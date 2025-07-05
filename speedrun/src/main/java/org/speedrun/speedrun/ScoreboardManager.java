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
    private int whitespaceCounter;

    public ScoreboardManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void updateScoreboard(@NotNull Player player) {
        Scoreboard board = playerBoards.computeIfAbsent(player.getUniqueId(), uuid -> Bukkit.getScoreboardManager().getNewScoreboard());
        whitespaceCounter = 0;

        Objective objective = board.getObjective("speedrun");
        if (objective != null) objective.unregister();
        objective = board.registerNewObjective("speedrun", Criteria.DUMMY, plugin.getConfigManager().getFormatted("scoreboard.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        AtomicInteger score = new AtomicInteger(15);
        ConfigManager cm = plugin.getConfigManager();
        StructureManager sm = plugin.getStructureManager(); // Получаем StructureManager

        objective.getScore(cm.getFormattedText("scoreboard.time") + " §e" + plugin.getGameManager().getFormattedTime()).setScore(score.getAndDecrement());

        String statusLine = plugin.getGameManager().isDragonKilledEnd() ? cm.getFormattedText("scoreboard.s-end")
                : plugin.getGameManager().isPaused() ? cm.getFormattedText("scoreboard.paused")
                : getWhitespace();
        objective.getScore(statusLine).setScore(score.getAndDecrement());

        objective.getScore(cm.getFormattedText("scoreboard.locations-header")).setScore(score.getAndDecrement());

        // Проходим по списку структур как и раньше
        for (Map.Entry<String, Location> entry : sm.getFoundStructures().entrySet()) {
            String key = entry.getKey();
            if (key.equals("LAVA_POOL") && sm.isPortalPartiallyFound()) continue;

            String displayName = sm.getLocalizedStructureName(key);
            String line;
            Location displayLoc;

            // --- ИЗМЕНЕНО: Специальная логика для портала ---
            if (key.equals("NETHER_PORTAL")) {
                // Получаем координаты для текущего мира игрока
                displayLoc = sm.getPortalLocationForWorld(player.getWorld().getEnvironment());
            } else {
                displayLoc = entry.getValue();
            }
            // --- КОНЕЦ ИЗМЕНЕНИЙ ДЛЯ ПОРТАЛА ---

            // Логика для портала Края остается прежней
            if (key.equals("END_PORTAL") && displayLoc == null) {
                Location predictedLoc = sm.getPredictedEndPortalLocation();
                if (predictedLoc != null) {
                    int netherX = predictedLoc.getBlockX() / 8;
                    int netherZ = predictedLoc.getBlockZ() / 8;
                    line = "§e" + displayName + ": §6" + predictedLoc.getBlockX() + ", " + predictedLoc.getBlockZ() + " §7(§c" + netherX + ", " + netherZ + "§7)";
                } else {
                    line = cm.getFormattedText("scoreboard.location-pending", "%name%", displayName);
                }
                objective.getScore(line).setScore(score.getAndDecrement());
                continue;
            }

            // Общая логика отображения
            if (displayLoc != null) {
                line = cm.getFormattedText("scoreboard.location-found", "%name%", displayName, "%coords%", LocationUtil.format(displayLoc));
            } else {
                if (key.equals("VILLAGE") && sm.isVillageSearchActive()) {
                    String timer = cm.getFormattedText("scoreboard.village-timer", "%time%", TimeUtil.formatMinutesSeconds(plugin.getGameManager().getVillageTimeRemaining()));
                    line = cm.getFormattedText("scoreboard.location-pending", "%name%", displayName) + " " + timer;
                } else {
                    line = cm.getFormattedText("scoreboard.location-pending", "%name%", displayName);
                }
            }
            objective.getScore(line).setScore(score.getAndDecrement());
        }

        World.Environment playerWorld = player.getWorld().getEnvironment();
        List<Task> tasks = plugin.getTaskManager().getTasksForWorld(playerWorld);
        if (!tasks.isEmpty()) {
            objective.getScore(getWhitespace()).setScore(score.getAndDecrement());
            String headerKey = "scoreboard." + playerWorld.name().toLowerCase() + "-tasks-header";
            objective.getScore(cm.getFormattedText(headerKey)).setScore(score.getAndDecrement());

            for (Task task : tasks) {
                String line = task.isCompleted()
                        ? cm.getFormattedText("scoreboard.task-complete", "%name%", task.displayName)
                        : cm.getFormattedText("scoreboard.task-line", "%name%", task.displayName, "%progress%", String.valueOf(task.progress), "%required%", String.valueOf(task.requiredAmount));
                objective.getScore(line).setScore(score.getAndDecrement());
            }
        }
        player.setScoreboard(board);
    }

    private String getWhitespace() {
        return "§r".repeat(++whitespaceCounter) + " ";
    }
}