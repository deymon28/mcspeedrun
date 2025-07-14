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


    private static final Map<String, String> STRUCTURE_KEYS = Map.of(
            "lavapool", "LAVA_POOL",
            "lava_pool", "LAVA_POOL",
            "village", "VILLAGE",
            "netherportal", "NETHER_PORTAL",
            "nether_portal", "NETHER_PORTAL",
            "fortress", "FORTRESS",
            "bastion", "BASTION",
            "endportal", "END_PORTAL",
            "end_portal", "END_PORTAL"
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
            sender.sendMessage("§cThis command can only be used by a player.");
            return true; // CommandExecutor is waiting boolean
        }

        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "start":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    if (!plugin.getGameManager().isRunning()) {
                        plugin.getGameManager().startRun();
                        player.sendMessage("§aThe speedrun has begun!");
                    } else {
                        player.sendMessage("§cSpeedran has already been launched.");
                    }
                    return true;

                case "pause":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }

                    plugin.getGameManager().togglePause();
                    player.sendMessage("§cSpeedran on pause.");

                    return true;

                case "stop":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    if (plugin.getGameManager().isRunning()) {
                        plugin.getGameManager().stopRun(false); // false means that it is not a victory
                        player.sendMessage("§aSpeedran has been stopped.");
                    } else {
                        player.sendMessage("§cSpeedran is not running.");
                    }
                    return true;

                case "reset":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    plugin.getGameManager().resetRun();
                    plugin.getCasualGameModeManager().reset();
                    player.sendMessage("§aSpeedran reset.");
                    return true;

                case "reload":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    plugin.getConfigManager().reload();
                    plugin.getTaskManager().reloadTasks(); // Reload tasks after configuration
                    player.sendMessage(plugin.getConfigManager().getFormattedText("commands.reloaded"));
                    return true;

                case "skipstage":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    plugin.getTaskManager().skipStage();
                    player.sendMessage(plugin.getConfigManager().getFormattedText("commands.stage-skipped"));
                    return true;

                case "status":
                    return showStatus(player);

                case "tasks":
                    return showTasks(player);

                case "new":
                    // Join the arguments back together and map them to the internal key
                    String rawKey = String.join("_", Arrays.copyOfRange(args, 1, args.length)).toLowerCase();
                    String key = STRUCTURE_KEYS.get(rawKey);

                    if (key == null) {
                        player.sendMessage("§cInvalid structure name. Valid options: "
                                + String.join(", ", STRUCTURE_KEYS.keySet()));
                        return true;
                    }

                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }

                    // 1. Always un-hide the structure (even if coords stay the same)
                    plugin.getStructureManager().restoreStructure(key);

                    // 2. Update (or set) the coordinates
                    boolean success;
                    if (key.equals("NETHER_PORTAL") && rawKey.equals("nether_portal")) {
                        success = plugin.getStructureManager().reassignNetherPortal(player);
                    } else {
                        success = plugin.getStructureManager().updateStructureLocation(
                                key,          // use the internal key directly
                                player.getLocation(),
                                player
                        );
                    }

                    if (!success) {
                        player.sendMessage("§cFailed to update location for: " + rawKey);
                        return true;
                    }

                    // 3. Notify and return
                    player.sendMessage("§aLocation updated and re-shown: " + rawKey);
                    return true;


                case "locate":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    return handleLocateCommand(player, args);

                case "remove":
                    if (args.length < 2) {
                        player.sendMessage("§cUsage: /run remove <structure>");
                        return true;
                    }
                    String rawRemove = String.join("_", Arrays.copyOfRange(args, 1, args.length)).toLowerCase();
                    String keyRemove = STRUCTURE_KEYS.get(rawRemove);
                    if (keyRemove == null) {
                        player.sendMessage("§cInvalid structure name. Valid: "
                                + String.join(", ", STRUCTURE_KEYS.keySet()));
                        return true;
                    }
                    if (plugin.getStructureManager().removeStructure(keyRemove)) {
                        player.sendMessage("§aStructure hidden: " + rawRemove);
                    }
                    return true;

                default:
                    player.sendMessage("§cUnknown subcommand. Use: start, pause, stop, reset, reload, skipstage, status, tasks, new, locate, remove.");
                    return true;
            }
        }

        player.sendMessage("§aUsage: /run <start|pause|stop|reset|reload|skipstage|status|tasks|new|locate|remove>");
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
                for (String k : STRUCTURE_KEYS.values()) {
                    if (k.toLowerCase().startsWith(currentArg)) completions.add(k);
                }
                /// Add special case. / Додаємо особливий випадок.
                // if ("nether portal".startsWith(currentArg)) {
                //     completions.add("nether portal");
                // }
                // if ("lava pool".startsWith(currentArg)) {
                //     completions.add("lava pool");
                // }
                // if ("end portal".startsWith(currentArg)) {
                //     completions.add("end portal");
                // }

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
                player.sendMessage("§aPosition 1 set to your current location and direction.");
                checkAndCalculateLocate(player);
                return true;
            } else if (locateSubCommand.equals("pos2")) {
                playerPos2.put(player.getUniqueId(), player.getLocation());
                player.sendMessage("§aPosition 2 set to your current location and direction.");
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
                player.sendMessage("§cInvalid coordinate format. Use numbers.");
                return true;
            }
        }

        // If none of the formats match, show usage
        player.sendMessage("§aUsage: /run locate <pos1|pos2> or /run locate <x1> <y1> <z1> <x2> <y2> <z2>");
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
        player.sendMessage("§a--- End Portal Location Calculation ---");

        if (predicted != null) {
            plugin.getStructureManager().setPredictedEndPortalLocation(predicted);
            int netherX = predicted.getBlockX() / 8;
            int netherZ = predicted.getBlockZ() / 8;
            player.sendMessage("§aPredicted End Portal: §e" + LocationUtil.format(predicted) + " §7(Нижний мир: §c" + netherX + ", " + netherZ + "§7)");
        } else {
            player.sendMessage("§cCould not calculate location. Lines may be parallel.");
        }
    }

    // Supporting methods for displaying status and tasks
    private boolean showStatus(CommandSender sender) {
        GameManager gm = plugin.getGameManager();
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();

        sender.sendMessage(cm.getFormattedText("commands.status.header"));
        sender.sendMessage(cm.getFormattedText("commands.status.time", "%time%", gm.getFormattedTime()));
        sender.sendMessage(cm.getFormattedText(gm.isPaused() ? "commands.status.paused" : "commands.status.running"));
        tm.getCurrentStageName().ifPresent(stageName -> sender.sendMessage(cm.getFormattedText("commands.status.stage", "%stage%", stageName)));
        sender.sendMessage(cm.getFormattedText("commands.status.players", "%players%", String.valueOf(Bukkit.getOnlinePlayers().size())));
        sender.sendMessage(cm.getFormattedText("commands.status.footer"));
        return true; // Return true for successful command execution
    }

    private boolean showTasks(Player player) {
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();
        World.Environment currentWorld = player.getWorld().getEnvironment();

        player.sendMessage(cm.getFormattedText("commands.tasks.header"));

        List<Task> tasksForWorld = tm.getTasksForWorld(currentWorld);

        if (tasksForWorld.stream().allMatch(Task::isCompleted)) {
            player.sendMessage(cm.getFormattedText("commands.tasks.no-tasks"));
        } else {
            for (Task task : tasksForWorld) {
                if (!task.isCompleted()) {
                    // Use getters to access Task fields.
                    player.sendMessage(cm.getFormattedText("scoreboard.task-line", "%name%", task.displayName, "%progress%", String.valueOf(task.getProgress()), "%required%", String.valueOf(task.getRequiredAmount())));
                }
            }
        }
        player.sendMessage(cm.getFormattedText("commands.tasks.footer"));
        return true; // Return true for successful command execution
    }
}
