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

@SuppressWarnings("UnstableApiUsage")
class SpeedrunCommand {
    private final Speedrun plugin;

    public SpeedrunCommand(Speedrun plugin, Commands registrar) {
        this.plugin = plugin;
        register(registrar);
    }

    public void register(Commands registrar) {
        final LiteralArgumentBuilder<CommandSourceStack> runCommand = Commands.literal("run")
                .requires(source -> source.getSender().hasPermission("speedrun.player")).executes(ctx -> showStatus(ctx.getSource().getSender()))
                .then(Commands.literal("pause")
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getGameManager().togglePause();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reset")
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getGameManager().resetRun();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getConfigManager().reload();
                            plugin.getTaskManager().reloadTasks();
                            ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.reloaded"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("skipstage")
                        .requires(source -> source.getSender().hasPermission("speedrun.admin")).executes(ctx -> {
                            plugin.getTaskManager().skipStage();
                            ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.stage-skipped"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("status").executes(ctx -> showStatus(ctx.getSource().getSender())))
                .then(Commands.literal("tasks").executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player)) {
                        ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.player-only"));
                        return 0;
                    }
                    return showTasks((Player) ctx.getSource().getSender());
                }))
                .then(Commands.literal("new").requires(source -> source.getSender().hasPermission("speedrun.admin"))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(plugin.getConfigManager().getFormatted("commands.player-only"));
                                return 0;
                            }
                            String locName = StringArgumentType.getString(ctx, "name");

                            boolean success = plugin.getStructureManager().updateStructureLocation(locName, player.getLocation(), player);

                            if (!success) {
                                player.sendMessage("§cUnknown structure name: " + locName);
                            }
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("locate").requires(source -> source.getSender().hasPermission("speedrun.admin"))
                        .then(Commands.argument("x1", doubleArg())
                                .then(Commands.argument("z1", doubleArg())
                                        .then(Commands.argument("yaw1", doubleArg())
                                                .then(Commands.argument("x2", doubleArg())
                                                        .then(Commands.argument("z2", doubleArg())
                                                                .then(Commands.argument("yaw2", doubleArg()).executes(ctx -> {
                                                                    double x1 = ctx.getArgument("x1", Double.class);
                                                                    double z1 = ctx.getArgument("z1", Double.class);
                                                                    float yaw1 = ctx.getArgument("yaw1", Double.class).floatValue();
                                                                    double x2 = ctx.getArgument("x2", Double.class);
                                                                    double z2 = ctx.getArgument("z2", Double.class);
                                                                    float yaw2 = ctx.getArgument("yaw2", Double.class).floatValue();

                                                                    Location predicted = LocationUtil.triangulate(x1, z1, yaw1, x2, z2, yaw2);

                                                                    if (predicted != null) {
                                                                        plugin.getStructureManager().setPredictedEndPortalLocation(predicted);
                                                                        int netherX = predicted.getBlockX() / 8;
                                                                        int netherZ = predicted.getBlockZ() / 8;
                                                                        ctx.getSource().getSender().sendMessage("§aPredicted End Portal: " + LocationUtil.format(predicted) + " §7(Nether: §c" + netherX + ", " + netherZ + "§7)");
                                                                    } else {
                                                                        ctx.getSource().getSender().sendMessage("§cCould not calculate location. The lines may be parallel.");
                                                                    }
                                                                    return Command.SINGLE_SUCCESS;
                                                                }))))))));

        registrar.register(runCommand.build());
    }

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

    private int showTasks(Player player) {
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();
        World.Environment currentWorld = player.getWorld().getEnvironment();

        player.sendMessage(cm.getFormatted("commands.tasks.header"));

        List<Task> tasksForWorld = tm.getTasksForWorld(currentWorld);

        if (tasksForWorld.stream().allMatch(Task::isCompleted)) {
            player.sendMessage(cm.getFormatted("commands.tasks.no-tasks"));
        } else {
            for (Task task : tasksForWorld) {
                if (!task.isCompleted()) {
                    player.sendMessage(cm.getFormatted("commands.tasks.task-line", "%task_name%", task.displayName, "%progress%", String.valueOf(task.progress), "%required%", String.valueOf(task.requiredAmount)));
                }
            }
        }
        player.sendMessage(cm.getFormatted("commands.tasks.footer"));
        return Command.SINGLE_SUCCESS;
    }
}