package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;
import org.speedrun.speedrun.utils.LocationUtil;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.Task;
import org.speedrun.speedrun.utils.TimeUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the in-game sidebar scoreboard for each player.
 * It displays the timer, game status, found locations, and current tasks.
 * |
 * Керує внутрішньоігровим бічним скорбордом для кожного гравця.
 * Відображає таймер, статус гри, знайдені локації та поточні завдання.
 */
public class ScoreboardManager {
    private final Speedrun plugin;
    // Each player gets their own scoreboard object to prevent conflicts.
    // Кожен гравець отримує власний об'єкт скорборду для уникнення конфліктів.
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();
    // Counter to generate unique "whitespace" lines to bypass scoreboard line limitations.
    // Лічильник для генерації унікальних "пробільних" рядків для обходу обмежень скорборду.
    private int whitespaceCounter;

    public ScoreboardManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates or updates the scoreboard for a specific player.
     * This method rebuilds the entire scoreboard from scratch on each call to ensure it's always up-to-date.
     * |
     * Створює або оновлює скорборд для конкретного гравця.
     * Цей метод перебудовує весь скорборд з нуля при кожному виклику, щоб гарантувати його актуальність.
     *
     * @param player The player whose scoreboard should be updated. / Гравець, чий скорборд потрібно оновити.
     */
    public void updateScoreboard(@NotNull Player player) {
        Scoreboard board = playerBoards.computeIfAbsent(player.getUniqueId(), uuid -> Bukkit.getScoreboardManager().getNewScoreboard());
        whitespaceCounter = 0;

        // Unregister the old objective to clear the board before adding new lines.
        // Скасовуємо реєстрацію старого objective, щоб очистити дошку перед додаванням нових рядків.
        Objective objective = board.getObjective("speedrun");
        if (objective != null) {
            objective.unregister();
        }
        objective = board.registerNewObjective("speedrun", Criteria.DUMMY, plugin.getConfigManager().getFormatted("scoreboard.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Use an AtomicInteger for a mutable integer that can be decremented in a single expression.
        // Використовуємо AtomicInteger для мутабельного цілого числа, яке можна декрементувати в одному виразі.
        AtomicInteger score = new AtomicInteger(15);
        ConfigManager cm = plugin.getConfigManager();
        GameManager gm = plugin.getGameManager();
        StructureManager sm = plugin.getStructureManager();

        // Line 1: Timer
        // Рядок 1: Таймер
        objective.getScore(cm.getFormattedText("scoreboard.time") + " §e" + gm.getFormattedTime()).setScore(score.getAndDecrement());

        // Line 2: Status (or whitespace)
        // Рядок 2: Статус (або пробіл)
        String statusLine = gm.isDragonKilledEnd() ? cm.getFormattedText("scoreboard.s-end")
                : gm.isPaused() ? cm.getFormattedText("scoreboard.paused")
                : getWhitespace();
        objective.getScore(statusLine).setScore(score.getAndDecrement());

        // Line 3: Locations Header
        // Рядок 3: Заголовок локацій
        objective.getScore(cm.getFormattedText("scoreboard.locations-header")).setScore(score.getAndDecrement());

        // Lines 4+: Found Structures
        // Рядки 4+: Знайдені структури
        for (Map.Entry<String, Location> entry : sm.getFoundStructures().entrySet()) {
            String key = entry.getKey();

            // if (sm.getFoundStructures().containsKey(key) && sm.isStructureHidden(key)) continue;
            if (sm.isStructureHidden(key)) continue;

            // Don't show Lava Pool if a Nether Portal has been found.
            // Не показуємо Озеро Лави, якщо портал в Незер вже знайдено.
            if (key.equals("LAVA_POOL") && sm.isPortalPartiallyFound()) continue;
            if (key.equals("VILLAGE") && sm.isVillageSearchFailed()) continue;

            String displayName = sm.getLocalizedStructureName(key);
            String line;
            Location displayLoc;

            // Special handling for Nether Portal to show coords for the player's current dimension.
            // Спеціальна обробка для порталу в Незер, щоб показувати координати для поточного виміру гравця.
            if (key.equals("NETHER_PORTAL")) {
                displayLoc = sm.getPortalLocationForWorld(player.getWorld().getEnvironment());
            } else {
                displayLoc = entry.getValue();
            }

            // Special handling for End Portal to show predicted coordinates.
            // Спеціальна обробка для порталу в Край, щоб показувати передбачені координати.
            if (key.equals("END_PORTAL") && displayLoc == null) {
                Location predictedLoc = sm.getPredictedEndPortalLocation();
                if (predictedLoc != null) {
                    int netherX = predictedLoc.getBlockX() / 8;
                    int netherZ = predictedLoc.getBlockZ() / 8;
                    line = "§e" + displayName + ": §6" + predictedLoc.getBlockX() + ", " + predictedLoc.getBlockZ() + " §7(§c" + netherX + ", " + netherZ + "§7)";
                } else {
                    line = cm.getFormattedText("scoreboard.location-pending", "%name%", displayName);
                }
            } else {
                if (displayLoc != null) {
                    line = cm.getFormattedText("scoreboard.location-found", "%name%", displayName, "%coords%", LocationUtil.format(displayLoc));
                } else {
                    // If a village is being searched for, show the countdown timer.
                    // Якщо ведеться пошук села, показуємо таймер зворотного відліку.
                    if (key.equals("VILLAGE") && sm.isVillageSearchActive()) {
                        String timer = cm.getFormattedText("scoreboard.village-timer", "%time%", TimeUtil.formatMinutesSeconds(gm.getVillageTimeRemaining()));
                        line = cm.getFormattedText("scoreboard.location-pending", "%name%", displayName) + " " + timer;
                    } else {
                        line = cm.getFormattedText("scoreboard.location-pending", "%name%", displayName);
                    }
                }
            }
            objective.getScore(line).setScore(score.getAndDecrement());
        }

        // Tasks Section
        // Секція завдань
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

    /**
     * Generates a unique string of invisible characters to serve as a line separator.
     * Scoreboards don't allow duplicate lines, so this creates unique "empty" lines.
     * |
     * Генерує унікальний рядок невидимих символів, що слугує роздільником.
     * Скорборди не дозволяють дублікатів рядків, тому це створює унікальні "порожні" рядки.
     *
     * @return A unique whitespace string. / Унікальний пробільний рядок.
     */
    private String getWhitespace() {
        return "§r".repeat(++whitespaceCounter) + " ";
    }
}