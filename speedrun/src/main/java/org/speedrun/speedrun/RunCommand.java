package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.speedrun.speedrun.managers.ConfigManager;
import org.speedrun.speedrun.managers.GameManager;
import org.speedrun.speedrun.managers.TaskManager;
import org.speedrun.speedrun.utils.LocationUtil;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.webconfig.RuntimeConfigApplier;

import java.util.*;
import java.util.LinkedHashMap;
import java.util.function.BiFunction;

/**
 * Handles the `/run` command and its subcommands.
 * Provides administrative controls for the speedrun and informational commands for players.
 */
public class RunCommand implements CommandExecutor, TabCompleter {

    private final Speedrun plugin;
    private final BiFunction<String, String[], String> messageProvider;
    // Temporary storage for player positions for the stronghold triangulation command.
    private final Map<UUID, Location> playerPos1 = new HashMap<>();
    private final Map<UUID, Location> playerPos2 = new HashMap<>();

    public RunCommand(Speedrun plugin) {
        this(plugin, (key, replacements) -> plugin.getConfigManager().getFormattedText(key, replacements));
    }

    RunCommand(Speedrun plugin, BiFunction<String, String[], String> messageProvider) {
        this.plugin = plugin;
        this.messageProvider = Objects.requireNonNull(messageProvider);
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

    static final String ADMIN_PERMISSION = "speedrun.admin";
    /** Subcommands shown in help and tab completion; the boolean marks administrator-only ones. */
    private static final Map<String, Boolean> SUBCOMMANDS = subcommands();

    private static Map<String, Boolean> subcommands() {
        Map<String, Boolean> commands = new LinkedHashMap<>();
        commands.put("help", false);
        commands.put("status", false);
        commands.put("tasks", false);
        commands.put("records", false);
        commands.put("givecompass", false);
        commands.put("start", true);
        commands.put("pause", true);
        commands.put("stop", true);
        commands.put("reset", true);
        commands.put("skipstage", true);
        commands.put("new", true);
        commands.put("remove", true);
        commands.put("locate", true);
        commands.put("reload", true);
        commands.put("webconfig", true);
        return commands;
    }

    /**
     * Executes {@code /run}. Administrative subcommands also work from the server console;
     * subcommands that need a position in the world require a player.
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            return showHelp(sender, label);
        }
        String subCommand = args[0].toLowerCase(Locale.ROOT);
        Player player = sender instanceof Player p ? p : null;
        Boolean adminOnly = SUBCOMMANDS.get(subCommand);
        if (adminOnly == null) {
            send(sender, message("commands.unknown-subcommand", "%label%", label));
            return true;
        }
        if (adminOnly && !sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, message("commands.no-permission"));
            return true;
        }

        switch (subCommand) {
            case "help":
                return showHelp(sender, label);

            case "start":
                if (!plugin.getGameManager().isRunning()) {
                    // startRun() broadcasts the start to everyone, including the sender.
                    plugin.getGameManager().startRun();
                } else {
                    send(sender, message("commands.run-already-started"));
                }
                return true;

            case "pause":
                if (!plugin.getGameManager().isRunning()) {
                    send(sender, message("commands.run-not-running"));
                    return true;
                }
                plugin.getGameManager().togglePause();
                return true;

            case "stop":
                if (plugin.getGameManager().isRunning()) {
                    plugin.getGameManager().stopRun(false);
                    send(sender, message("commands.run-stopped"));
                } else {
                    send(sender, message("commands.run-not-running"));
                }
                return true;

            case "reset":
                plugin.getGameManager().resetRun();
                return true;

            case "reload":
                plugin.getConfigManager().reload();
                plugin.getTraceLogger().reload();
                plugin.getTaskManager().reloadTasks();
                new RuntimeConfigApplier(plugin).apply(new JSONObject());
                plugin.getGameManager().refreshRuntimeConfig();
                plugin.getAdminNotices().clear();
                if (plugin.getWebConfigManager() != null) {
                    plugin.getWebConfigManager().restart();
                }
                send(sender, message("commands.reloaded"));
                return true;

            case "webconfig":
                return handleWebConfigCommand(sender, args);

            case "skipstage":
                plugin.getTaskManager().skipStage();
                send(sender, message("commands.stage-skipped"));
                return true;

            case "status":
                return showStatus(sender);

            case "tasks":
                if (player == null) {
                    send(sender, message("commands.player-only"));
                    return true;
                }
                return showTasks(player);

            case "records":
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    if (!sender.hasPermission(ADMIN_PERMISSION)) {
                        send(sender, message("commands.no-permission"));
                        return true;
                    }
                    plugin.getRecordsManager().reset();
                    send(sender, message("records.reset"));
                    return true;
                }
                plugin.getRecordsManager().show(sender);
                return true;

            case "new":
                if (player == null) {
                    send(sender, message("commands.player-only"));
                    return true;
                }
                return handleNewCommand(player, args);

            case "locate":
                if (player == null) {
                    send(sender, message("commands.player-only"));
                    return true;
                }
                return handleLocateCommand(player, args);

            case "givecompass":
                return handleGiveCompassCommand(sender, args);

            case "remove":
                return handleRemoveCommand(sender, args);

            default:
                send(sender, message("commands.unknown-subcommand", "%label%", label));
                return true;
        }
    }

    private boolean handleNewCommand(Player player, String[] args) {
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

        send(player, success
                ? message("commands.location-updated", "%location%", parsed.displayName())
                : message("commands.location-update-failed", "%location%", parsed.displayName()));
        return true;
    }

    private boolean handleRemoveCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, message("commands.usage-remove"));
            return true;
        }
        String keyRemove = resolveStructureKey(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        if (keyRemove == null) {
            send(sender, message("commands.invalid-structure", "%structures%", getValidStructureList()));
            return true;
        }
        if (plugin.getStructureManager().removeStructure(keyRemove)) {
            if ("NETHER_PORTAL".equals(keyRemove) && plugin.getGameListener() != null) {
                plugin.getGameListener().invalidatePendingPortalSearches();
            }
            send(sender, message("commands.structure-hidden", "%structure%",
                    plugin.getStructureManager().getLocalizedStructureName(keyRemove)));
        }
        return true;
    }

    private boolean showHelp(CommandSender sender, String label) {
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);
        send(sender, message("commands.help.header"));
        for (Map.Entry<String, Boolean> entry : SUBCOMMANDS.entrySet()) {
            if (entry.getValue() && !admin) {
                continue;
            }
            send(sender, message("commands.help." + entry.getKey(), "%label%", label));
        }
        if (admin) {
            send(sender, message("commands.help.admin-footer"));
        }
        return true;
    }

    /**
     * Suggests subcommands and arguments the sender is allowed to use.
     */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> completions = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);

        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Boolean> entry : SUBCOMMANDS.entrySet()) {
                if ((admin || !entry.getValue()) && entry.getKey().startsWith(current)) {
                    completions.add(entry.getKey());
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase(Locale.ROOT);
            String currentArg = args[1].toLowerCase(Locale.ROOT);

            if ((subCommand.equals("new") || subCommand.equals("remove")) && admin) {
                for (String k : getValidStructureNames()) {
                    if (k.toLowerCase(Locale.ROOT).startsWith(currentArg)) completions.add(k);
                }
            } else if (subCommand.equals("locate") && admin) {
                for (String option : List.of("pos1", "pos2")) {
                    if (option.startsWith(currentArg)) completions.add(option);
                }
            } else if (subCommand.equals("givecompass") && admin) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(currentArg)) {
                        completions.add(online.getName());
                    }
                }
            } else if (subCommand.equals("webconfig") && admin) {
                for (String option : List.of("restart", "stop")) {
                    if (option.startsWith(currentArg)) completions.add(option);
                }
            } else if (subCommand.equals("records") && admin && "reset".startsWith(currentArg)) {
                completions.add("reset");
            }
        }
        return completions;
    }

    static String resolveStructureKey(String rawInput) {
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
            playerPos1.remove(playerId);
            playerPos2.remove(playerId);
        }
    }

    /**
     * Displays the result of the triangulation to the player.
     */
    private void calculateAndDisplayLocate(Player player, Location predicted) {
        send(player, message("commands.locate-header"));

        if (predicted != null) {
            predicted.setWorld(findWorldByEnvironment(World.Environment.NORMAL, player.getWorld()));
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

    private World findWorldByEnvironment(World.Environment environment, World fallback) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return world;
            }
        }
        return fallback;
    }

    private boolean handleGiveCompassCommand(CommandSender sender, String[] args) {
        Player targetPlayer = sender instanceof Player self ? self : null;
        if (args.length == 2) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                send(sender, message("commands.givecompass.no-permission-others"));
                return true;
            }
            targetPlayer = Bukkit.getPlayerExact(args[1]);
            if (targetPlayer == null) {
                send(sender, message("commands.givecompass.player-not-found", "%player%", args[1]));
                return true;
            }
        } else if (args.length > 2 || targetPlayer == null) {
            send(sender, message("commands.givecompass.usage"));
            return true;
        }

        ItemStack compass = plugin.getCasualGameModeManager() != null
                && plugin.getCasualGameModeManager().getCompassListener() != null
                ? plugin.getCasualGameModeManager().getCompassListener().createNavigationCompass(null, null)
                : createLegacyCompass();

        targetPlayer.getInventory().addItem(compass);
        MessageUtil.send(targetPlayer, plugin.getConfigManager().getFormatted("commands.givecompass.received"));
        if (!sender.equals(targetPlayer)) {
            send(sender, message("commands.givecompass.gave", "%player%", targetPlayer.getName()));
        }
        return true;
    }

    private boolean handleWebConfigCommand(CommandSender sender, String[] args) {
        if (plugin.getWebConfigManager() == null) {
            send(sender, message("commands.webconfig.unavailable"));
            return true;
        }
        if (args.length > 2) {
            send(sender, message("commands.webconfig.usage"));
            return true;
        }

        String action = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "show";
        switch (action) {
            case "show" -> {
                sendWebLink(sender, "commands.webconfig.url");
                return true;
            }
            case "restart" -> {
                plugin.getWebConfigManager().restart();
                sendWebLink(sender, "commands.webconfig.restarted");
                return true;
            }
            case "stop" -> {
                plugin.getWebConfigManager().stop();
                send(sender, message("commands.webconfig.stopped"));
                return true;
            }
            default -> {
                send(sender, message("commands.webconfig.usage"));
                return true;
            }
        }
    }

    /** Sends the private editor link; in game it is a clickable message. */
    private void sendWebLink(CommandSender sender, String key) {
        String url = plugin.getWebConfigManager().getUrl();
        if (url == null) {
            send(sender, message("commands.webconfig.unavailable"));
            return;
        }
        MessageUtil.send(sender, plugin.getConfigManager().getFormatted(
                sender instanceof Player ? key : key + "-console", "%url%", url));
        send(sender, message("commands.webconfig.private"));
    }

    private ItemStack createLegacyCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getConfigManager().getFormattedText("items.navigation-compass.name"));
            meta.setLore(plugin.getConfigManager().getFormattedTextList("items.navigation-compass.lore"));
            compass.setItemMeta(meta);
        }
        return compass;
    }

    private String message(String key, String... replacements) {
        return messageProvider.apply(key, replacements);
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
        String statusKey = !gm.isRunning() ? (gm.isDragonKilledEnd() ? "commands.status.finished" : "commands.status.idle")
                : gm.isPaused() ? "commands.status.paused" : "commands.status.running";
        send(sender, cm.getFormattedText(statusKey));
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
