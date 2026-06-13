package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.jetbrains.annotations.NotNull;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.Task;
import org.speedrun.speedrun.utils.LocationUtil;
import org.speedrun.speedrun.utils.TimeUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ScoreboardManager {
    private final Speedrun plugin;
    private final Map<UUID, Scoreboard> playerBoards = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> lastLines = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastTitles = new ConcurrentHashMap<>();

    public ScoreboardManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void updateScoreboard(@NotNull Player player) {
        try {
            Scoreboard board = playerBoards.computeIfAbsent(player.getUniqueId(), uuid -> {
                Scoreboard newBoard = Bukkit.getScoreboardManager().getNewScoreboard();
                setupScoreboard(newBoard);
                player.setScoreboard(newBoard);
                return newBoard;
            });
            if (player.getScoreboard() != board) {
                player.setScoreboard(board);
            }

            Objective objective = board.getObjective("speedrun");
            if (objective == null) {
                objective = createObjective(board);
            }

            List<String> newLines = buildLines(player);
            List<String> oldLines = lastLines.getOrDefault(player.getUniqueId(), Collections.emptyList());
            lastLines.put(player.getUniqueId(), newLines);

            // Update title if changed
            String newTitle = getTitle();
            if (!newTitle.equals(lastTitles.get(player.getUniqueId()))) {
                objective.setDisplayName(newTitle);
                lastTitles.put(player.getUniqueId(), newTitle);
            }

            // Update lines
            for (int i = 0; i < 15; i++) { // Max 15 lines
                Team team = board.getTeam("line_" + i);
                if (team == null) {
                    team = board.registerNewTeam("line_" + i);
                    String entry = getEntry(i);
                    team.addEntry(entry);
                }

                if (i < newLines.size()) {
                    String lineText = newLines.get(i);
                    // Only update if changed
                    if (i >= oldLines.size() || !lineText.equals(oldLines.get(i))) {
                        setTeamText(team, lineText);
                    }
                    // Update score position
                    Score score = objective.getScore(getEntry(i));
                    if (score.getScore() != (15 - i)) {
                        score.setScore(15 - i);
                    }
                } else {
                    // Clear unused lines
                    resetLine(board, objective, getEntry(i));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error updating scoreboard for " + player.getName());
            plugin.getLogger().severe(e.getMessage());
        }
    }

    @NotNull
    private Objective createObjective(@NotNull Scoreboard board) {
        // Use non-deprecated method if available
        try {
            // Try modern method first
            return board.registerNewObjective(
                    "speedrun",
                    Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text("Speedrun"),
                    RenderType.INTEGER
            );
        } catch (NoSuchMethodError e) {

            return board.registerNewObjective("speedrun", "dummy", getTitle());
            // Fallback to legacy method
            //return board.registerNewObjective("speedrun", "dummy", getTitle());
        }
    }

    @NotNull
    private String getTitle() {
        return plugin.getConfigManager().getFormattedString("scoreboard.title");
    }

    private void setupScoreboard(@NotNull Scoreboard board) {
        Objective objective = createObjective(board);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Pre-register teams
        for (int i = 0; i < 15; i++) {
            Team team = board.registerNewTeam("line_" + i);
            String entry = getEntry(i);
            team.addEntry(entry);
        }
    }

    @NotNull
    private List<String> buildLines(@NotNull Player player) {
        List<String> lines = new ArrayList<>();
        ConfigManager cm = plugin.getConfigManager();
        GameManager gm = plugin.getGameManager();
        StructureManager sm = plugin.getStructureManager();

        try {
            // Line 1: Timer
            lines.add(cm.getFormattedString("scoreboard.time") + " §e" + gm.getFormattedTime());

            // Line 2: Status
            if (gm.isDragonKilledEnd()) {
                lines.add(cm.getFormattedString("scoreboard.s-end"));
            } else if (gm.isPaused()) {
                lines.add(cm.getFormattedString("scoreboard.paused"));
            } else {
                lines.add(" "); // Empty line when running normally
            }

            if (cm.isHardcoreModeEnabled()) {
                return lines;
            }

            // Line 3: Locations Header
            lines.add(cm.getFormattedString("scoreboard.locations-header"));

            // Found Structures
            for (Map.Entry<String, Location> entry : sm.getFoundStructures().entrySet()) {
                String key = entry.getKey();

                // Skip hidden structures
                if (sm.isStructureHidden(key)) continue;

                // Skip Lava Pool if Nether Portal found
                if (key.equals("LAVA_POOL") && sm.isPortalPartiallyFound()) continue;

                // Skip Village if search failed
                if (key.equals("VILLAGE") && sm.isVillageSearchFailed()) continue;

                String displayName = sm.getLocalizedStructureName(key);
                Location loc = entry.getValue();

                // Special handling for Nether Portal
                if (key.equals("NETHER_PORTAL")) {
                    loc = sm.getPortalLocationForWorld(player.getWorld().getEnvironment());
                }

                // Special handling for End Portal
                if (key.equals("END_PORTAL") && loc == null) {
                    Location predictedLoc = sm.getPredictedEndPortalLocation();
                    if (predictedLoc != null) {
                        int netherX = predictedLoc.getBlockX() / 8;
                        int netherZ = predictedLoc.getBlockZ() / 8;
                        String endPortalLine = "§e" + displayName + ": §6" +
                                predictedLoc.getBlockX() + ", " + predictedLoc.getBlockZ() +
                                " §7(§c" + netherX + ", " + netherZ + "§7)";
                        
                        // Разбиваем длинную строку End Portal на две части
                        List<String> splitLines = splitEndPortalLine(endPortalLine);
                        lines.addAll(splitLines);
                        continue;
                    }
                }

                if (loc != null) {
                    lines.add(cm.getFormattedString("scoreboard.location-found",
                            "%name%", displayName,
                            "%coords%", formatCoordinates(player, loc)));
                } else {
                    if (key.equals("VILLAGE") && sm.isVillageSearchActive()) {
                        String timer = cm.getFormattedString("scoreboard.village-timer",
                                "%time%", TimeUtil.formatMinutesSeconds(gm.getVillageTimeRemaining()));
                        lines.add(cm.getFormattedString("scoreboard.location-pending",
                                "%name%", displayName) + " " + timer);
                    } else {
                        lines.add(cm.getFormattedString("scoreboard.location-pending",
                                "%name%", displayName));
                    }
                }
            }

            // Tasks Section
            World.Environment env = player.getWorld().getEnvironment();
            List<Task> tasks = plugin.getTaskManager().getTasksForWorld(env);
            if (!tasks.isEmpty()) {
                // Проверяем, есть ли разбитая строка End Portal
                boolean hasSplitEndPortal = false;
                for (String line : lines) {
                    if (line.contains("End Portal") && line.contains("§7(") && line.length() > 32) {
                        hasSplitEndPortal = true;
                        break;
                    }
                }
                
                // Добавляем разделитель только если нет разбитой строки End Portal
                if (!hasSplitEndPortal) {
                    lines.add(" "); // Separator
                }
                
                lines.add(cm.getFormattedString("scoreboard." + env.name().toLowerCase() + "-tasks-header"));

                for (Task task : tasks) {
                    if (task.isCompleted()) {
                        lines.add(cm.getFormattedString("scoreboard.task-complete", "%name%", task.displayName));
                    } else {
                        lines.add(cm.getFormattedString("scoreboard.task-line",
                                "%name%", task.displayName,
                                "%progress%", String.valueOf(task.progress),
                                "%required%", String.valueOf(task.requiredAmount)));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error building scoreboard lines");
            lines = Arrays.asList("§cScoreboard Error", "§7Check console logs");
        }

        // Ensure we don't exceed 15 lines
        return lines.size() > 15 ? lines.subList(0, 15) : lines;
    }

    private String formatCoordinates(Player player, Location loc) {
        ConfigManager.CoordinateDisplayMode mode = plugin.getConfigManager().getCoordinateDisplayMode();
        if (loc.getWorld() == null) {
            return LocationUtil.format(loc);
        }
        if (mode == ConfigManager.CoordinateDisplayMode.SEPARATE) {
            return loc.getWorld().getEnvironment().name() + " " + LocationUtil.format(loc);
        }
        if (mode == ConfigManager.CoordinateDisplayMode.UNIFIED) {
            return LocationUtil.formatWithLinkedWorld(loc);
        }
        if (loc.getWorld().getEnvironment() != player.getWorld().getEnvironment()) {
            return loc.getWorld().getEnvironment().name() + " " + LocationUtil.format(loc);
        }
        return LocationUtil.formatWithLinkedWorld(loc);
    }

    private void setTeamText(@NotNull Team team, @NotNull String text) {
        // Remove the 32-character limit that was causing truncation
        String cleanText = text;

        String prefix, suffix;
        if (cleanText.length() <= 16) {
            // Short text: everything goes in prefix
            prefix = cleanText;
            suffix = "";
        } else {
            // Long text: split between prefix (16 chars) and suffix (remaining)
            prefix = cleanText.substring(0, 16);

            // Get the last color code from prefix to maintain formatting
            String lastColor = getLastColor(prefix);

            // Put remaining text in suffix, preserving color formatting
            String remainingText = cleanText.substring(16);

            // Apply last color to suffix if it doesn't start with a color code
            if (!remainingText.startsWith("§") && !lastColor.isEmpty()) {
                suffix = lastColor + remainingText;
            } else {
                suffix = remainingText;
            }

            // Bukkit has a limit for suffix length (around 16 chars in older versions)
            // But in modern versions, this can be much longer
            if (suffix.length() > 64) { // Safe limit for most Bukkit versions
                suffix = suffix.substring(0, 64);
            }
        }

        team.setPrefix(prefix);
        team.setSuffix(suffix);
    }


    private @NotNull String getLastColor(@NotNull String input) {
        String lastColor = "";
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '§') {
                lastColor = "§" + chars[i + 1];
            }
        }
        return lastColor;
    }

    /**
     * Разбивает длинную строку End Portal на две части для отображения в scoreboard
     * @param text Исходная строка End Portal
     * @return Список из двух строк
     */
    private @NotNull List<String> splitEndPortalLine(@NotNull String text) {
        List<String> result = new ArrayList<>();
        
        // Если строка не слишком длинная, возвращаем как есть
        if (text.length() <= 32) {
            result.add(text);
            return result;
        }
        
        // Ищем место для разрыва - обычно после координат Overworld
        int breakPoint = text.indexOf(" §7(");
        if (breakPoint == -1) {
            // Если не нашли стандартный разделитель, разбиваем по длине
            breakPoint = Math.min(32, text.length() - 1);
        } else {
            // Разбиваем после координат Overworld, перед координатами Nether
            breakPoint += 1; // Включаем пробел
        }
        
        String firstLine = text.substring(0, breakPoint);
        String secondLine = "§7" + text.substring(breakPoint); // Добавляем цвет для второй строки
        
        result.add(firstLine);
        result.add(secondLine);
        
        return result;
    }




    @NotNull
    private String getEntry(int index) {
        // Unique entries using color codes
        return "§" + "0123456789abcdef".charAt(index % 16) + "§r";
    }

    private void resetLine(@NotNull Scoreboard board, @NotNull Objective objective, @NotNull String entry) {
        board.resetScores(entry);
    }
}
