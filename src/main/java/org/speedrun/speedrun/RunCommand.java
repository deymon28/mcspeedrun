package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.speedrun.speedrun.managers.ConfigManager;
import org.speedrun.speedrun.managers.GameManager;
import org.speedrun.speedrun.managers.TaskManager;
import org.speedrun.speedrun.utils.LocationUtil;
import org.speedrun.speedrun.utils.MessageUtil;

import java.util.*;

/**
 * Handles the `/run` command and its subcommands.
 * Provides administrative controls for the speedrun and informational commands for players.
 * |
 * Обробляє команду `/run` та її підкоманди.
 * Надає адміністративні елементи керування спідраном та інформаційні команди для гравців.
 */
public class RunCommand implements CommandExecutor, TabCompleter {

    private final Speedrun plugin;
    // Temporary storage for player positions for the stronghold triangulation command.
    // Тимчасове сховище для позицій гравця для команди тріангуляції фортеці.
    private final Map<UUID, Location> playerPos1 = new HashMap<>();
    private final Map<UUID, Location> playerPos2 = new HashMap<>();

    public RunCommand(Speedrun plugin) {
        this.plugin = plugin;
    }


    private static final Map<String, String> STRUCTURE_KEYS = Map.ofEntries(
            Map.entry("lavapool", "LAVA_POOL"),
            Map.entry("lava_pool", "LAVA_POOL"),
            Map.entry("lava pool", "LAVA_POOL"),
            Map.entry("village", "VILLAGE"),
            Map.entry("netherportal", "NETHER_PORTAL"),
            Map.entry("nether_portal", "NETHER_PORTAL"),
            Map.entry("nether portal", "NETHER_PORTAL"),
            Map.entry("fortress", "FORTRESS"),
            Map.entry("bastion", "BASTION"),
            Map.entry("endportal", "END_PORTAL"),
            Map.entry("end_portal", "END_PORTAL"),
            Map.entry("end portal", "END_PORTAL")
    );

    /**
     * Executes the given command, returning its success.
     * |
     * Виконує задану команду, повертаючи її успішність.
     *
     * @param sender Source of the command. / Джерело команди.
     * @param command Command which was executed. / Команда, яка була виконана.
     * @param label Alias of the command which was used. / Псевдонім команди, який було використано.
     * @param args Passed command arguments. / Передані аргументи команди.
     * @return true if a valid command, otherwise false. / true, якщо команда дійсна, інакше false.
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            send(sender, message("commands.player-only"));
            return true;
        }

        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "start":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }
                    if (!plugin.getGameManager().isRunning()) {
                        plugin.getGameManager().startRun();
                        send(player, message("commands.run-started"));
                    } else {
                        send(player, message("commands.run-already-started"));
                    }
                    return true;

                case "pause":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }

                    plugin.getGameManager().togglePause();
                    send(player, message("commands.run-paused"));

                    return true;

                case "stop":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }
                    if (plugin.getGameManager().isRunning()) {
                        plugin.getGameManager().stopRun(false);
                        send(player, message("commands.run-stopped"));
                    } else {
                        send(player, message("commands.run-not-running"));
                    }
                    return true;

                case "reset":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }
                    plugin.getGameManager().resetRun();
                    send(player, message("commands.run-reset"));
                    return true;

                case "reload":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }
                    plugin.getConfigManager().reload();
                    plugin.getTaskManager().reloadTasks();
                    send(player, message("commands.reloaded"));
                    return true;

                case "skipstage":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }
                    plugin.getTaskManager().skipStage();
                    send(player, message("commands.stage-skipped"));
                    return true;

                case "status":
                    return showStatus(player);

                case "tasks":
                    return showTasks(player);

                case "new":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }

                    ParsedStructureInput parsed = parseStructureInput(player, args);
                    if (parsed == null) {
                        send(player, message("commands.usage-new", "%structures%", getValidStructureList()));
                        return true;
                    }

                    String key = parsed.structureKey();
                    Location targetLocation = parsed.customLocation() != null ? parsed.customLocation() : player.getLocation();

                    plugin.getStructureManager().restoreStructure(key);

                    boolean success;
                    if (key.equals("NETHER_PORTAL")) {
                        if (parsed.customLocation() != null) {
                            plugin.getStructureManager().portalLit(player, targetLocation);
                            success = true;
                        } else {
                            success = plugin.getStructureManager().reassignNetherPortal(player);
                        }
                    } else {
                        success = plugin.getStructureManager().updateStructureLocation(key, targetLocation, player);
                    }

                    if (!success) {
                        send(player, message("commands.location-update-failed", "%location%", parsed.displayName()));
                        return true;
                    }

                    send(player, message("commands.location-updated", "%location%", parsed.displayName()));
                    return true;


                case "locate":
                    if (!player.hasPermission("speedrun.admin")) {
                        send(player, message("commands.no-permission"));
                        return true;
                    }
                    return handleLocateCommand(player, args);

                case "remove":
                    if (args.length < 2) {
                        send(player, message("commands.usage-remove"));
                        return true;
                    }
                    String keyRemove = resolveStructureKey(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    if (keyRemove == null) {
                        send(player, message("commands.invalid-structure", "%structures%", getValidStructureList()));
                        return true;
                    }
                    if (plugin.getStructureManager().removeStructure(keyRemove)) {
                        if ("NETHER_PORTAL".equals(keyRemove) && plugin.getGameListener() != null) {
                            plugin.getGameListener().invalidatePendingPortalSearches();
                        }
                        send(player, message("commands.structure-hidden", "%structure%", keyRemove));
                    }
                    return true;

                default:
                    send(player, message("commands.unknown-subcommand"));
                    return true;
            }
        }

        send(player, message("commands.usage-main"));
        return true;
    }

    /**
     * Requests a list of possible completions for a command argument.
     * |
     * Запитує список можливих доповнень для аргументу команди.
     *
     * @param sender Source of the command. / Джерело команди.
     * @param command Command which was executed. / Команда, яка була виконана.
     * @param alias Alias of the command which was used. / Псевдонім команди, який було використано.
     * @param args The arguments passed to the command, including final partial argument to be completed. / Аргументи, передані команді.
     * @return A List of possible completions for the final argument. / Список можливих доповнень.
     */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Suggest all subcommands.
            // Пропонуємо всі підкоманди.
            String[] subcommands = {"start", "pause", "stop", "reset", "reload", "skipstage", "status", "tasks", "new", "locate", "remove"};
            for (String sub : subcommands) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            String currentArg = args[1].toLowerCase();

            if (subCommand.equals("new") || subCommand.equals("remove")) {
                // Suggest known structure names for `/run new`.
                // Пропонуємо відомі назви структур для `/run new`.
                for (String k : getValidStructureNames()) {
                    if (k.toLowerCase().startsWith(currentArg)) completions.add(k);
                }
            } else if (subCommand.equals("locate")) {
                // Suggest pos1/pos2 for `/run locate`.
                // Пропонуємо pos1/pos2 для `/run locate`.
                if ("pos1".startsWith(currentArg)) {
                    completions.add("pos1");
                }
                if ("pos2".startsWith(currentArg)) {
                    completions.add("pos2");
                }
            }
        }
        // For locate with coordinates, we do not offer autocompletion, as these are numeric values.
        // For other subcommands with more than 1 argument, you can add logic here if required.

        return completions;
    }

    private String resolveStructureKey(String rawInput) {
        String normalized = rawInput.toLowerCase(Locale.ROOT)
                .trim()
                .replace('-', ' ')
                .replaceAll("\\s+", " ");
        String direct = STRUCTURE_KEYS.get(normalized);
        if (direct != null) {
            return direct;
        }
        return STRUCTURE_KEYS.get(normalized.replace(" ", "_"));
    }

    private String getValidStructureList() {
        return String.join(", ", getValidStructureNames());
    }

    private List<String> getValidStructureNames() {
        return List.of("lava pool", "village", "nether portal", "fortress", "bastion", "end portal");
    }

    private ParsedStructureInput parseStructureInput(Player player, String[] args) {
        if (args.length < 2) {
            return null;
        }

        if (args.length >= 5 && areTrailingCoordinates(args)) {
            String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 3));
            String key = resolveStructureKey(rawName);
            if (key == null) {
                return null;
            }
            double x = Double.parseDouble(args[args.length - 3]);
            double y = Double.parseDouble(args[args.length - 2]);
            double z = Double.parseDouble(args[args.length - 1]);
            Location custom = new Location(player.getWorld(), x, y, z);
            return new ParsedStructureInput(key, rawName, custom);
        }

        String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String key = resolveStructureKey(rawName);
        if (key == null) {
            return null;
        }
        return new ParsedStructureInput(key, rawName, null);
    }

    private boolean areTrailingCoordinates(String[] args) {
        try {
            Double.parseDouble(args[args.length - 3]);
            Double.parseDouble(args[args.length - 2]);
            Double.parseDouble(args[args.length - 1]);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private record ParsedStructureInput(String structureKey, String displayName, Location customLocation) {}


    /**
     * Handles the `/run locate` command for stronghold triangulation.
     * Can be used with `pos1`/`pos2` or by providing coordinates directly.
     * |
     * Обробляє команду `/run locate` для тріангуляції фортеці.
     * Можна використовувати з `pos1`/`pos2` або надаючи координати напряму.
     */
    private boolean handleLocateCommand(Player player, String[] args) {
        // /run locate pos1 or /run locate pos2
        if (args.length == 2) {
            String locateSubCommand = args[1].toLowerCase();
            if (locateSubCommand.equals("pos1")) {
                playerPos1.put(player.getUniqueId(), player.getLocation());
                send(player, message("commands.locate-pos1"));
                checkAndCalculateLocate(player);
                return true;
            } else if (locateSubCommand.equals("pos2")) {
                playerPos2.put(player.getUniqueId(), player.getLocation());
                send(player, message("commands.locate-pos2"));
                checkAndCalculateLocate(player);
                return true;
            }
            else return false;
        }

        // /run locate <x1> <y1> <z1> <x2> <y2> <z2>
        // Fixed index for Z coordinates and use of float for yaw.
        if (args.length == 7) {
            try {
                double x1 = Double.parseDouble(args[1]);
                //double y1 = Double.parseDouble(args[2]);
                double z1 = Double.parseDouble(args[3]);
                double x2 = Double.parseDouble(args[4]);
                //double y2 = Double.parseDouble(args[5]);
                double z2 = Double.parseDouble(args[6]);

                // Yaw/pitch are not provided via manual coordinate entry.
                // Напрямок погляду (yaw/pitch) не надається при ручному вводі координат.
                Location predicted = LocationUtil.triangulate(x1, z1, 0f, x2, z2, 0f);

                calculateAndDisplayLocate(player, predicted);
                return true;
            } catch (NumberFormatException e) {
                send(player, message("commands.locate-invalid-coordinates"));
                return true;
            }
        }

        send(player, message("commands.locate-usage"));
        return true;
    }

    /**
     * If both pos1 and pos2 are set for a player, calculates the triangulation.
     * Якщо для гравця встановлені обидві позиції (pos1 і pos2), обчислює тріангуляцію.
     */
    private void checkAndCalculateLocate(Player player) {
        UUID playerId = player.getUniqueId();
        if (playerPos1.containsKey(playerId) && playerPos2.containsKey(playerId)) {
            Location loc1 = playerPos1.get(playerId);
            Location loc2 = playerPos2.get(playerId);

            // Calculate the predicted location using triangulation
            Location predicted = LocationUtil.triangulate(
                    loc1.getX(), loc1.getZ(), loc1.getYaw(),
                    loc2.getX(), loc2.getZ(), loc2.getYaw()
            );

            calculateAndDisplayLocate(player, predicted);

            // Clear stored positions after calculation.
            // Очищуємо збережені позиції після обчислення.
            playerPos1.remove(playerId);
            playerPos2.remove(playerId);
        }
    }

    /**
     * Displays the result of the triangulation to the player.
     * Відображає результат тріангуляції гравцеві.
     */
    private void calculateAndDisplayLocate(Player player, Location predicted) {
        send(player, message("commands.locate-header"));

        if (predicted != null) {
            plugin.getStructureManager().setPredictedEndPortalLocation(predicted);
            int netherX = predicted.getBlockX() / 8;
            int netherZ = predicted.getBlockZ() / 8;
            send(player, message("commands.locate-predicted",
                    "%coords%", LocationUtil.format(predicted),
                    "%nether_x%", String.valueOf(netherX),
                    "%nether_z%", String.valueOf(netherZ)));
        } else {
            send(player, message("commands.locate-failed"));
        }
    }

    private String message(String key, String... replacements) {
        return plugin.getConfigManager().getFormattedText(key, replacements);
    }

    private void send(CommandSender sender, String message) {
        MessageUtil.send(sender, message);
    }

    // Supporting methods for displaying status and tasks
    private boolean showStatus(CommandSender sender) {
        GameManager gm = plugin.getGameManager();
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();

        send(sender, cm.getFormattedText("commands.status.header"));
        send(sender, cm.getFormattedText("commands.status.time", "%time%", gm.getFormattedTime()));
        send(sender, cm.getFormattedText(gm.isPaused() ? "commands.status.paused" : "commands.status.running"));
        tm.getCurrentStageName().ifPresent(stageName -> send(sender, cm.getFormattedText("commands.status.stage", "%stage%", stageName)));
        send(sender, cm.getFormattedText("commands.status.players", "%players%", String.valueOf(Bukkit.getOnlinePlayers().size())));
        send(sender, cm.getFormattedText("commands.status.footer"));
        return true;
    }

    private boolean showTasks(Player player) {
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();
        World.Environment currentWorld = player.getWorld().getEnvironment();

        send(player, cm.getFormattedText("commands.tasks.header"));

        List<Task> tasksForWorld = tm.getTasksForWorld(currentWorld);

        if (tasksForWorld.stream().allMatch(Task::isCompleted)) {
            send(player, cm.getFormattedText("commands.tasks.no-tasks"));
        } else {
            for (Task task : tasksForWorld) {
                if (!task.isCompleted()) {
                    send(player, cm.getFormattedText("scoreboard.task-line", "%name%", task.displayName, "%progress%", String.valueOf(task.getProgress()), "%required%", String.valueOf(task.getRequiredAmount())));
                }
            }
        }
        send(player, cm.getFormattedText("commands.tasks.footer"));
        return true;
    }
}
