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

import java.util.*;

/**
 * Обработчик команды /run.
 * ИЗМЕНЕНО: Добавлена логика для подкоманд /run locate pos1 и /run locate pos2.
 */
public class RunCommand implements CommandExecutor, TabCompleter {

    private final Speedrun plugin;
    // Хранение временных позиций для каждого игрока
    private final Map<UUID, Location> playerPos1 = new HashMap<>();
    private final Map<UUID, Location> playerPos2 = new HashMap<>();

    public RunCommand(Speedrun plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭту команду может использовать только игрок.");
            return true; // CommandExecutor ожидает boolean
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
                        player.sendMessage("§aСпидран начат!");
                    } else {
                        player.sendMessage("§cСпидран уже запущен.");
                    }
                    return true;

                case "pause":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }

                    plugin.getGameManager().togglePause();
                    player.sendMessage("§cСпидран на паузе.");

                    return true;

                case "stop":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    if (plugin.getGameManager().isRunning()) {
                        plugin.getGameManager().stopRun(false); // false означает, что не победа
                        player.sendMessage("§aСпидран остановлен.");
                    } else {
                        player.sendMessage("§cСпидран не запущен.");
                    }
                    return true;

                case "reset":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    plugin.getGameManager().resetRun();
                    player.sendMessage("§aСпидран сброшен.");
                    return true;

                case "reload":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    plugin.getConfigManager().reload();
                    plugin.getTaskManager().reloadTasks(); // Перезагрузить задачи после конфига
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
                    // Нет проверки разрешений для статуса
                    return showStatus(player);

                case "tasks":
                    // Нет проверки разрешений для задач
                    return showTasks(player);

                case "new":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    if (args.length > 1) {
                        String fullLocName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        String key = fullLocName.replace(' ', '_').toUpperCase();

                        boolean success;
                        if (key.equals("NETHER_PORTAL") && fullLocName.equalsIgnoreCase("nether portal")) {
                            success = plugin.getStructureManager().reassignNetherPortal(player);
                        } else {
                            success = plugin.getStructureManager().updateStructureLocation(fullLocName, player.getLocation(), player);
                        }

                        if (!success) {
                            player.sendMessage("§cНе удалось обновить локацию: " + fullLocName);
                        }
                        return true;
                    }
                    player.sendMessage("§cИспользование: /run new <имя_структуры> или /run new nether portal");
                    return true;


                case "locate":
                    if (!player.hasPermission("speedrun.admin")) {
                        player.sendMessage(plugin.getConfigManager().getFormattedText("commands.no-permission"));
                        return true;
                    }
                    return handleLocateCommand(player, args);

                default:
                    player.sendMessage("§cНеизвестная подкоманда. Используйте: start, stop, reset, reload, skipstage, status, tasks, new, locate.");
                    return true;
            }
        }

        player.sendMessage("§aИспользование: /run <start|stop|reset|reload|skipstage|status|tasks|new|locate>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Предлагаем основные подкоманды
            String[] subcommands = {"start", "stop", "reset", "reload", "skipstage", "status", "tasks", "new", "locate"};
            for (String sub : subcommands) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            String currentArg = args[1].toLowerCase();

            if (subCommand.equals("new")) {
                // Предлагаем известные структуры для команды /run new
                for (String structureKey : plugin.getStructureManager().getFoundStructures().keySet()) {
                    String displayName = plugin.getStructureManager().getLocalizedStructureName(structureKey);
                    if (displayName.toLowerCase().startsWith(currentArg)) {
                        completions.add(displayName);
                    }
                }
                // Добавляем специальные случаи, если они не покрыты foundStructures
                if ("nether portal".startsWith(currentArg)) {
                    completions.add("nether portal");
                }
                if ("lava pool".startsWith(currentArg)) { // Хотя это уже в foundStructures, явно добавить не помешает
                    completions.add("lava pool");
                }
                if ("end portal".startsWith(currentArg)) { // Аналогично
                    completions.add("end portal");
                }

            } else if (subCommand.equals("locate")) {
                // Предлагаем pos1 и pos2 для команды /run locate
                if ("pos1".startsWith(currentArg)) {
                    completions.add("pos1");
                }
                if ("pos2".startsWith(currentArg)) {
                    completions.add("pos2");
                }
            }
        }
        // Для locate с координатами, не предлагаем автодополнение, так как это числовые значения.
        // Для других подкоманд с более чем 1 аргументом, можно добавить логику здесь, если требуется.

        return completions;
    }


    /**
     * Обрабатывает подкоманду /run locate.
     * Поддерживает /run locate pos1, /run locate pos2 и /run locate <x1> <y1> <z1> <x2> <y2> <z2>.
     */
    private boolean handleLocateCommand(Player player, String[] args) {
        // /run locate pos1 или /run locate pos2
        if (args.length == 2) {
            String locateSubCommand = args[1].toLowerCase();
            if (locateSubCommand.equals("pos1")) {
                playerPos1.put(player.getUniqueId(), player.getLocation());
                player.sendMessage("§aПозиция 1 установлена на ваши текущие координаты и направление.");
                checkAndCalculateLocate(player);
                return true;
            } else if (locateSubCommand.equals("pos2")) {
                playerPos2.put(player.getUniqueId(), player.getLocation());
                player.sendMessage("§aПозиция 2 установлена на ваши текущие координаты и направление.");
                checkAndCalculateLocate(player);
                return true;
            }
            else return false;
        }

        // /run locate <x1> <y1> <z1> <x2> <y2> <z2>
        // Исправлен индекс для Z-координат и использование float для yaw.
        if (args.length == 7) {
            try {
                double x1 = Double.parseDouble(args[1]);
                //double y1 = Double.parseDouble(args[2]);
                double z1 = Double.parseDouble(args[3]);
                double x2 = Double.parseDouble(args[4]);
                //double y2 = Double.parseDouble(args[5]);
                double z2 = Double.parseDouble(args[6]);

                // Для ручного ввода координат, yaw и pitch не предоставляются, используем 0f.
                // Если вам нужен yaw/pitch для ручного ввода, их нужно добавить как аргументы команды.
                Location predicted = LocationUtil.triangulate(x1, z1, 0f, x2, z2, 0f);

                calculateAndDisplayLocate(player, predicted);
                return true;
            } catch (NumberFormatException e) {
                player.sendMessage("§cНеверный формат координат. Используйте числа.");
                return true;
            }
        }

        // Если ни один из форматов не соответствует, показать использование
        player.sendMessage("§aИспользование: /run locate <x1> <y1> <z1> <x2> <y2> <z2>");
        player.sendMessage("§aИли: /run locate <pos1|pos2>");
        return true;
    }

    /**
     * Проверяет, установлены ли обе позиции (pos1 и pos2) для игрока,
     * и если да, вызывает расчет.
     */
    private void checkAndCalculateLocate(Player player) {
        UUID playerId = player.getUniqueId();
        if (playerPos1.containsKey(playerId) && playerPos2.containsKey(playerId)) {
            Location loc1 = playerPos1.get(playerId);
            Location loc2 = playerPos2.get(playerId);

            // Вычислить предсказанное местоположение с помощью триангуляции
            Location predicted = LocationUtil.triangulate(
                    loc1.getX(), loc1.getZ(), loc1.getYaw(),
                    loc2.getX(), loc2.getZ(), loc2.getYaw()
            );

            calculateAndDisplayLocate(player, predicted);

            // Очистить сохраненные позиции после расчета
            playerPos1.remove(playerId);
            playerPos2.remove(playerId);
        }
    }

    /**
     * Выполняет расчет расстояния и угла между двумя локациями (если предоставлено)
     * и выводит результат игроку.
     * Этот метод теперь обобщен для принятия потенциально нулевого предсказанного местоположения.
     */
    private void calculateAndDisplayLocate(Player player, Location predicted) {
        player.sendMessage("§a--- Расчет местоположения Портала Края ---");

        if (predicted != null) {
            plugin.getStructureManager().setPredictedEndPortalLocation(predicted);
            int netherX = predicted.getBlockX() / 8;
            int netherZ = predicted.getBlockZ() / 8;
            player.sendMessage("§aПредполагаемый портал в Энд: §e" + LocationUtil.format(predicted) + " §7(Нижний мир: §c" + netherX + ", " + netherZ + "§7)");
        } else {
            player.sendMessage("§cНе удалось рассчитать местоположение. Линии могут быть параллельны или позиции не установлены.");
        }
    }

    // Вспомогательные методы для отображения статуса и задач
    private boolean showStatus(CommandSender sender) { // Изменен тип возвращаемого значения на boolean
        GameManager gm = plugin.getGameManager();
        TaskManager tm = plugin.getTaskManager();
        ConfigManager cm = plugin.getConfigManager();

        sender.sendMessage(cm.getFormattedText("commands.status.header"));
        sender.sendMessage(cm.getFormattedText("commands.status.time", "%time%", gm.getFormattedTime()));
        sender.sendMessage(cm.getFormattedText(gm.isPaused() ? "commands.status.paused" : "commands.status.running"));
        tm.getCurrentStageName().ifPresent(stageName -> sender.sendMessage(cm.getFormattedText("commands.status.stage", "%stage%", stageName)));
        sender.sendMessage(cm.getFormattedText("commands.status.players", "%players%", String.valueOf(Bukkit.getOnlinePlayers().size())));
        sender.sendMessage(cm.getFormattedText("commands.status.footer"));
        return true; // Возвращаем true для успешного выполнения команды
    }

    private boolean showTasks(Player player) { // Изменен тип возвращаемого значения на boolean
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
                    // Используем геттеры для доступа к полям Task
                    player.sendMessage(cm.getFormattedText("scoreboard.task-line", "%name%", task.displayName, "%progress%", String.valueOf(task.getProgress()), "%required%", String.valueOf(task.getRequiredAmount())));
                }
            }
        }
        player.sendMessage(cm.getFormattedText("commands.tasks.footer"));
        return true; // Возвращаем true для успешного выполнения команды
    }
}
