package org.speedrun.speedrun;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Represents a single speedrun task.
 * A task can be to collect items or find a structure.
 * |
 * Представляє одне завдання спідрану.
 * Завданням може бути збір предметів або пошук структури.
 */
public class Task {

    public enum Type {
        /** A task to collect a certain amount of an item. / Завдання зібрати певну кількість предметів. */
        ITEM,
        /** A task to find a specific structure. / Завдання знайти певну структуру. */
        STRUCTURE
    }

    final String key; // The internal key, e.g., "DIAMOND" or "STRUCTURE_FORTRESS". / Внутрішній ключ, напр., "DIAMOND" або "STRUCTURE_FORTRESS".
    final Type taskType;
    public String displayName; // The user-facing name, e.g., "Find a Fortress". / Ім'я, яке бачить користувач, напр., "Знайти фортецю".
    final int baseRequiredAmount; // The required amount for a single player. / Необхідна кількість для одного гравця.
    public int requiredAmount; // The scaled amount based on player count. / Масштабована кількість, що залежить від числа гравців.
    public int progress = 0;
    public boolean completed = false;
    final World.Environment world; // The world where this task is active. / Світ, у якому це завдання активне.
    private final boolean srbpEnabled; // srbp = Scale Resources By Playercount. / srbp = Масштабувати Ресурси відносно кількості Гравців.

    /**
     * Constructs a Task from a configuration section.
     * |
     * Створює Завдання з секції конфігурації.
     *
     * @param key The unique key for the task. / Унікальний ключ завдання.
     * @param cs The ConfigurationSection containing task details. / ConfigurationSection, що містить деталі завдання.
     * @param world The world environment for this task. / Світ для цього завдання.
     * @param plugin A reference to the main plugin instance. / Посилання на головний екземпляр плагіна.
     */
    public Task(String key, ConfigurationSection cs, World.Environment world, Speedrun plugin) {
        this.key = key;
        this.world = world;

        // Load the display name from the language file first, with a fallback to the config.yml.
        // Спочатку завантажуємо відображуване ім'я з мовного файлу, з резервним варіантом з config.yml.
        String langKey = "tasks." + key;
        String fallbackName = cs.getString("display-name", key);
        this.displayName = plugin.getConfigManager().getLangString(langKey, fallbackName);

        if (key.startsWith("STRUCTURE_")) {
            this.taskType = Type.STRUCTURE;
            this.baseRequiredAmount = 1;
        } else {
            this.taskType = Type.ITEM;
            this.baseRequiredAmount = cs.getInt("amount", 1);
        }
        this.requiredAmount = this.baseRequiredAmount;
        // Read the scaling flag from the config, defaulting to false if not specified.
        // Читаємо прапорець масштабування з конфігу, за замовчуванням false, якщо не вказано.
        this.srbpEnabled = cs.getBoolean("srbp", false);
    }

    /**
     * Scales the required amount of items based on the number of players.
     * This only applies if the 'srbp' flag is true for this task in the config.
     * |
     * Масштабує необхідну кількість предметів залежно від кількості гравців.
     * Це застосовується, тільки якщо прапорець 'srbp' встановлено в true для цього завдання в конфізі.
     *
     * @param playerCount The current number of online players. / Поточна кількість гравців онлайн.
     * @param multiplier The scaling factor from the config. / Коефіцієнт масштабування з конфігу.
     */
    public void scale(int playerCount, double multiplier) {
        if (!srbpEnabled) {
            this.requiredAmount = this.baseRequiredAmount; // Ensure it's reset if scaling is disabled. / Переконуємося, що значення скинуто, якщо масштабування вимкнено.
            return;
        }

        if (playerCount <= 1) {
            this.requiredAmount = this.baseRequiredAmount;
            return;
        }
        this.requiredAmount = (int) Math.ceil(this.baseRequiredAmount * (1 + (playerCount - 1) * multiplier));
    }

    /**
     * Checks if the task's progress meets the required amount and marks it as complete if so.
     * |
     * Перевіряє, чи прогрес завдання досяг необхідної кількості, і, якщо так, позначає його як виконане.
     *
     * @param plugin A reference to the main plugin instance to execute rewards. / Посилання на плагін для виконання винагород.
     */
    public void updateCompletionStatus(Speedrun plugin) {
        if (completed) return;

        if (progress >= requiredAmount) {
            forceComplete(plugin);
        }
    }

    /**
     * Forcibly marks the task as complete, regardless of progress.
     * Triggers completion effects like logging and rewards.
     * |
     * Примусово позначає завдання як виконане, незалежно від прогресу.
     * Спричиняє ефекти завершення, як-от логування та винагороди.
     *
     * @param plugin A reference to the main plugin instance. / Посилання на головний екземпляр плагіна.
     */
    public void forceComplete(Speedrun plugin) {
        if (completed) return;

        completed = true;
        progress = requiredAmount; // Cap progress at the required amount. / Обмежуємо прогрес необхідною кількістю.

        plugin.getGameManager().getLogger().logCompletedTask(displayName, progress);
        // Pass null for the player if it's a general task completion.
        // Передаємо null для гравця, якщо це загальне завершення завдання.
        plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
    }


    // =========================================================================================
    // Getters
    // =========================================================================================

    public boolean isCompleted() { return completed; }
    public String getKey() { return key; }
    public int getRequiredAmount() { return requiredAmount; }
    public int getProgress() { return progress; }
    public World.Environment getWorld() { return world; }
    public Type getTaskType() { return taskType; }
    public boolean isSrbpEnabled() { return srbpEnabled; }
}