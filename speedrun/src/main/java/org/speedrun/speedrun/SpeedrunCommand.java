package org.speedrun.speedrun;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.mojang.brigadier.arguments.StringArgumentType;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;

import org.bukkit.Location;

import java.util.List;

// =========================================================================================
// Command Handler
// Handles the registration and execution of speedrun-related commands.
// =========================================================================================

@SuppressWarnings("UnstableApiUsage")
class SpeedrunCommand {
    private final Speedrun plugin;

    /**
     * Constructor for SpeedrunCommand.*
     *
     * @param plugin    The main Speedrun plugin instance.
     * @param registrar The Brigader Commands registrar to register commands with.
     */
    public SpeedrunCommand(Speedrun plugin, Commands registrar) {
        this.plugin = plugin;
        register(registrar);
    }

    /**
     * Registers all speedrun commands using the Brigadier command API.* <p>
     * Commands include 'run' (status), 'run pause', 'run reset', 'run reload',* <p>
     * 'run skipstage', 'run status', and 'run tasks'.
     *
     * @param registrar The Commands registrar from PaperMC to register the commands.
     */
    public void register(Commands registrar) {
        // Define the base 'run' command.
        final LiteralArgumentBuilder<CommandSourceStack> runCommand = Commands.literal("run")
                // Only players with 'speedrun.player' permission can use this command.
                .requires(source -> source.getSender().hasPermission("speedrun.player")).executes(ctx -> {
                    // Default execution for 'run' command shows the status.
                    return showStatus(ctx.getSource().getSender());
                })
                // Subcommand for 'run pause'.
                .then(Commands.literal("pause")
                        // Only players with 'speedrun.admin' permission can pause.
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getGameManager().togglePause(); // Toggles game pause.
                            return Command.SINGLE_SUCCESS;
                        }))
// Subcommand for 'run reset'.
                .then(Commands.literal("reset")
// Only players with 'speedrun.admin' permission can reset.
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getGameManager().resetRun(); // Resets the speedrun.
                            return Command.SINGLE_SUCCESS;
                        }))
// Subcommand for 'run reload'.
                .then(Commands.literal("reload")
// Only players with 'speedrun.admin' permission can reload.
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getConfigManager().reload(); // Reloads plugin configuration.
                            plugin.getTaskManager().reloadTasks(); // Reloads tasks.
                            ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.reloaded"));
                            return Command.SINGLE_SUCCESS;
                        }))
                // Subcommand for 'run skipstage'.
                .then(Commands.literal("skipstage")
                        // Only players with 'speedrun.admin' permission can skip stages.
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getTaskManager().skipStage(); // Skips to the next stage.
                            ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.stage-skipped"));
                            return Command.SINGLE_SUCCESS;
                        }))
                // Subcommand for 'run status'.
                .then(Commands.literal("status").executes(ctx -> showStatus(ctx.getSource().getSender()))) // Shows game status.
                // Subcommand for 'run tasks'.
                .then(Commands.literal("tasks").executes(ctx -> {
                    // Ensure the sender is a player for this command.
                    if (!(ctx.getSource().getSender() instanceof Player)) {
                        ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.player-only"));
                        return 0;
                    }
                    return showTasks((Player) ctx.getSource().getSender()); // Shows tasks for the player's current world.
                }))

                .then(Commands.literal("new").requires(source -> source.getSender().hasPermission("speedrun.admin")).then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.player-only"));
                        return 0;
                    }
                    String locName = StringArgumentType.getString(ctx, "name");

                    boolean success = plugin.getStructureManager().updateStructureLocation(locName, player.getLocation(), player);

                    if (success) {
                        player.sendMessage(plugin.getConfigManager().getFormatted("messages.structure-found", "%player%", player.getName(), "%structure%", locName, "%coords%", LocationUtil.format(player.getLocation())));
                    } else {
                        player.sendMessage("§cUnknown structure name: " + locName);
                    }
                    return Command.SINGLE_SUCCESS;
                })))
                // Subcommand for 'run locate <x1> <z1> <yaw1> <x2> <z2> <yaw2>'.
                .then(Commands.literal("locate").requires(source -> source.getSender().hasPermission("speedrun.admin")).then(Commands.argument("x1", doubleArg()).then(Commands.argument("z1", doubleArg()).then(Commands.argument("yaw1", doubleArg()).then(Commands.argument("x2", doubleArg()).then(Commands.argument("z2", doubleArg()).then(Commands.argument("yaw2", doubleArg()).executes(ctx -> {
                    double x1 = ctx.getArgument("x1", Double.class);
                    double z1 = ctx.getArgument("z1", Double.class);
                    float yaw1 = ctx.getArgument("yaw1", Double.class).floatValue();
                    double x2 = ctx.getArgument("x2", Double.class);
                    double z2 = ctx.getArgument("z2", Double.class);
                    float yaw2 = ctx.getArgument("yaw2", Double.class).floatValue();

                    Location predicted = LocationUtil.triangulate(x1, z1, yaw1, x2, z2, yaw2);

                    if (predicted != null) {
                        plugin.getStructureManager().setPredictedEndPortalLocation(predicted);
                        ctx.getSource().getSender().sendMessage("§aPredicted End Portal location set to: " + LocationUtil.format(predicted));
                    } else {
                        ctx.getSource().getSender().sendMessage("§cCould not calculate location. The lines may be parallel.");
                    }
                    return Command.SINGLE_SUCCESS;
                }))))))));

        // Build and register the command.
        registrar.register(runCommand.build());
    }

    /**
     * Displays the current speedrun status to the command sender.* <p>
     * Includes game time, pause status, current stage, and online players.
     *
     * @param sender The sender of the command (Player or Console).
     * @return Command.SINGLE_SUCCESS if successful.
     */
    private int showStatus(org.bukkit.command.CommandSender sender) {
        GameManager gm = plugin.getGameManager();
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();

        sender.sendMessage(cm.getFormatted("commands.status.header"));
        sender.sendMessage(cm.getFormatted("commands.status.time", "%time%", gm.getFormattedTime()));
        sender.sendMessage(cm.getFormatted(gm.isPaused() ? "commands.status.paused" : "commands.status.running"));
        tm.getCurrentStageName().ifPresent(stageName -> sender.sendMessage(cm.getFormatted("commands.status.stage", "%stage%", stageName)));
        sender.sendMessage(cm.getFormatted("commands.status.players", "%players%", String.valueOf(Bukkit.getOnlinePlayers().size())));
        sender.sendMessage(cm.getFormatted("commands.status.footer"));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Displays the tasks relevant to the player's current world.
     * Only uncompleted tasks are shown.
     *
     * @param player The player requesting to see tasks.
     * @return Command.SINGLE_SUCCESS if successful.
     */
    private int showTasks(Player player) {
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();
        World.Environment currentWorld = player.getWorld().getEnvironment();

        player.sendMessage(cm.getFormatted("commands.tasks.header"));

        List<Task> tasksForWorld = tm.getTasksForWorld(currentWorld);

        if (tasksForWorld.isEmpty()) {
            player.sendMessage(cm.getFormatted("commands.tasks.no-tasks"));
        } else {
            for (Task task : tasksForWorld) {
                if (!task.isCompleted()) {
                    // Corrected varargs usage for getFormatted
                    player.sendMessage(cm.getFormatted("commands.tasks.task-line", "%task_name%", task.displayName, "%progress%", String.valueOf(task.progress), "%required%", String.valueOf(task.requiredAmount)));
                }
            }
        }
        player.sendMessage(cm.getFormatted("commands.tasks.footer"));
        return Command.SINGLE_SUCCESS;
    }
}