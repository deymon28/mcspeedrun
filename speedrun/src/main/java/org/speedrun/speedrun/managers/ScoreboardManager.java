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
                boolean approximate = "END_PORTAL".equals(key)
                        && loc == null
                        && sm.isPredictedEndPortalApproximate()
                        && sm.getPredictedEndPortalLocation() != null;
                approximate = approximate || sm.isApproximateStructure(key);

                loc = resolveDisplayLocation(player, key, loc);
                if (!shouldDisplayLocation(player, key, loc)) {
                    continue;
                }

                if (loc != null) {
                    String lineKey = approximate ? "scoreboard.location-approximate" : "scoreboard.location-found";
                    lines.add(cm.getFormattedString(lineKey,
                            "%name%", displayName,
                            "%coords%", formatCoordinates(player, key, loc)));
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

            addTaskLines(player, lines, cm);
        } catch (Exception e) {
            plugin.getLogger().severe("Error building scoreboard lines");
            lines = Arrays.asList("§cScoreboard Error", "§7Check console logs");
        }

        // Ensure we don't exceed 15 lines
        return lines.size() > 15 ? lines.subList(0, 15) : lines;
    }

    private void addTaskLines(Player player, List<String> lines, ConfigManager cm) {
        if (cm.getTaskDisplayMode() == ConfigManager.TaskDisplayMode.ALL_GAME_STAGES) {
            boolean addedAny = false;
            for (World.Environment env : List.of(World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END)) {
                List<Task> tasks = plugin.getTaskManager().getTasksForWorld(env);
                if (tasks.isEmpty()) {
                    continue;
                }
                if (!addedAny) {
                    lines.add(" ");
                    addedAny = true;
                }
                addTaskWorldSection(lines, cm, env, tasks);
            }
            return;
        }

        World.Environment env = player.getWorld().getEnvironment();
        List<Task> tasks = plugin.getTaskManager().getTasksForWorld(env);
        if (!tasks.isEmpty()) {
            lines.add(" ");
            addTaskWorldSection(lines, cm, env, tasks);
        }
    }

    private void addTaskWorldSection(List<String> lines, ConfigManager cm, World.Environment env, List<Task> tasks) {
        lines.add(cm.getFormattedString(taskHeaderKey(env)));

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

    private String taskHeaderKey(World.Environment env) {
        return "scoreboard." + switch (env) {
            case NORMAL -> "normal";
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> env.name().toLowerCase(Locale.ROOT);
        } + "-tasks-header";
    }

    private Location resolveDisplayLocation(Player player, String key, Location loc) {
        StructureManager sm = plugin.getStructureManager();
        World.Environment playerWorld = player.getWorld().getEnvironment();

        if ("NETHER_PORTAL".equals(key)) {
            Location sameWorldPortal = sm.getPortalLocationForWorld(playerWorld);
            if (sameWorldPortal != null) {
                return sameWorldPortal;
            }
            Location knownPortal = sm.getOverworldPortalLocation() != null
                    ? sm.getOverworldPortalLocation()
                    : sm.getNetherPortalLocation();
            return convertToPlayerWorld(player, knownPortal);
        }

        if ("END_PORTAL".equals(key)) {
            Location endPortal = loc != null ? loc : sm.getPredictedEndPortalLocation();
            if (playerWorld == World.Environment.NORMAL || playerWorld == World.Environment.NETHER) {
                return convertToPlayerWorld(player, endPortal);
            }
            return endPortal;
        }

        ConfigManager.CoordinateDisplayMode mode = plugin.getConfigManager().getCoordinateDisplayMode();
        if (mode == ConfigManager.CoordinateDisplayMode.CONDITIONAL) {
            return convertToPlayerWorld(player, loc);
        }
        return loc;
    }

    private boolean shouldDisplayLocation(Player player, String key, Location loc) {
        ConfigManager.CoordinateDisplayMode mode = plugin.getConfigManager().getCoordinateDisplayMode();
        if (mode != ConfigManager.CoordinateDisplayMode.SEPARATE) {
            return true;
        }

        World.Environment playerWorld = player.getWorld().getEnvironment();
        if ("NETHER_PORTAL".equals(key) || "END_PORTAL".equals(key)) {
            return playerWorld == World.Environment.NORMAL || playerWorld == World.Environment.NETHER;
        }

        if (loc == null || loc.getWorld() == null) {
            World.Environment structureWorld = defaultStructureWorld(key);
            return structureWorld == null || structureWorld == playerWorld;
        }

        return loc.getWorld().getEnvironment() == playerWorld;
    }

    private World.Environment defaultStructureWorld(String key) {
        return switch (key) {
            case "FORTRESS", "BASTION" -> World.Environment.NETHER;
            case "LAVA_POOL", "VILLAGE" -> World.Environment.NORMAL;
            default -> null;
        };
    }

    private String formatCoordinates(Player player, String key, Location loc) {
        ConfigManager.CoordinateDisplayMode mode = plugin.getConfigManager().getCoordinateDisplayMode();
        if (mode == ConfigManager.CoordinateDisplayMode.CONDITIONAL || "NETHER_PORTAL".equals(key) || "END_PORTAL".equals(key)) {
            Location converted = convertToPlayerWorld(player, loc);
            return LocationUtil.format(converted != null ? converted : loc);
        }
        return LocationUtil.format(loc);
    }

    private Location convertToPlayerWorld(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return loc;
        }

        World.Environment from = loc.getWorld().getEnvironment();
        World.Environment to = player.getWorld().getEnvironment();
        if (from == to) {
            return loc;
        }
        if (from != World.Environment.NORMAL && from != World.Environment.NETHER) {
            return loc;
        }
        if (to != World.Environment.NORMAL && to != World.Environment.NETHER) {
            return loc;
        }

        World targetWorld = findWorldByEnvironment(to);
        double scale = from == World.Environment.NORMAL ? 1.0 / 8.0 : 8.0;
        return new Location(targetWorld != null ? targetWorld : loc.getWorld(),
                loc.getX() * scale,
                loc.getY(),
                loc.getZ() * scale,
                loc.getYaw(),
                loc.getPitch());
    }

    private World findWorldByEnvironment(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return world;
            }
        }
        return null;
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
            String remainingText = cleanText.substring(16);

            if (prefix.endsWith("§")) {
                prefix = prefix.substring(0, prefix.length() - 1);
                remainingText = "§" + remainingText;
            }

            String formatting = getActiveFormatting(prefix);

            // Put remaining text in suffix, preserving color formatting
            if (!remainingText.startsWith("§") && !formatting.isEmpty()) {
                suffix = formatting + remainingText;
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


    private @NotNull String getActiveFormatting(@NotNull String input) {
        String color = "";
        StringBuilder formats = new StringBuilder();
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '§') {
                char code = Character.toLowerCase(chars[i + 1]);
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') {
                    color = code == 'r' ? "" : "§" + code;
                    formats.setLength(0);
                } else if ("klmno".indexOf(code) >= 0 && formats.indexOf("§" + code) < 0) {
                    formats.append('§').append(code);
                }
            }
        }
        return color + formats;
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
