package org.speedrun.speedrun;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Represents a single speedrun task.
 * A task can be to collect items or find a structure.
 */
public class Task {

    public enum Type {
        /** A task to collect a certain amount of an item. */
        ITEM,
        /** A task to find a specific structure. */
        STRUCTURE
    }

    final String key; // The internal key, e.g., "DIAMOND" or "STRUCTURE_FORTRESS".
    final Type taskType;
    public String displayName; // The user-facing name, e.g., "Find a Fortress".
    final int baseRequiredAmount; // The required amount for a single player.
    public int requiredAmount; // The scaled amount based on player count.
    public int progress = 0;
    public boolean completed = false;
    private long completedAtMillis = 0L;
    final World.Environment world; // The world where this task is active.
    private final boolean srbpEnabled; // srbp = Scale Resources By Playercount.

    /**
     * Constructs a Task from a configuration section.
     *
     * @param key The unique key for the task.
     * @param cs The ConfigurationSection containing task details.
     * @param world The world environment for this task.
     * @param plugin A reference to the main plugin instance.
     */
    public Task(String key, ConfigurationSection cs, World.Environment world, Speedrun plugin) {
        this.key = key;
        this.world = world;

        // Load the display name from the language file first, with a fallback to the config.yml.
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
        this.srbpEnabled = cs.getBoolean("srbp", false);
    }

    Task(String key, Type taskType, String displayName, int baseRequiredAmount, boolean srbpEnabled, World.Environment world) {
        this.key = key;
        this.taskType = taskType;
        this.displayName = displayName;
        this.baseRequiredAmount = baseRequiredAmount;
        this.requiredAmount = baseRequiredAmount;
        this.srbpEnabled = srbpEnabled;
        this.world = world;
    }

    /**
     * Scales the required amount of items based on the number of players.
     * This only applies if the 'srbp' flag is true for this task in the config.
     *
     * @param playerCount The current number of online players.
     * @param multiplier The scaling factor from the config.
     */
    public void scale(int playerCount, double multiplier) {
        this.requiredAmount = calculateRequiredAmount(baseRequiredAmount, srbpEnabled, playerCount, multiplier);
    }

    public static int calculateRequiredAmount(int baseRequiredAmount, boolean srbpEnabled, int playerCount, double multiplier) {
        if (!srbpEnabled || playerCount <= 1) {
            return baseRequiredAmount;
        }
        return (int) Math.ceil(baseRequiredAmount * (1 + (playerCount - 1) * multiplier));
    }

    /**
     * Checks if the task's progress meets the required amount and marks it as complete if so.
     *
     * @param plugin A reference to the main plugin instance to execute rewards.
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
     *
     * @param plugin A reference to the main plugin instance.
     */
    public void forceComplete(Speedrun plugin) {
        if (completed) return;

        completed = true;
        completedAtMillis = System.currentTimeMillis();
        progress = requiredAmount; // Cap progress at the required amount.

        plugin.getGameManager().getLogger().logCompletedTask(displayName, progress);
        // Pass null for the player if it's a general task completion.
        plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
    }

    /**
     * Restores saved progress without completion effects (a run resumed after a restart).
     * Restored completed tasks count as completed long ago, so a hide timeout hides them at once.
     */
    public void restoreProgress(int savedProgress, boolean savedCompleted) {
        completed = savedCompleted;
        progress = savedCompleted ? requiredAmount : Math.max(0, savedProgress);
        completedAtMillis = savedCompleted ? 1L : 0L;
    }

    public void resetProgress() {
        if (taskType == Type.ITEM && !completed) {
            progress = 0;
        }
    }

    public void addProgress(int amount) {
        if (completed || amount <= 0) {
            return;
        }
        progress += amount;
    }

    public void setProgress(int progress) {
        if (completed) {
            return;
        }
        this.progress = Math.max(0, progress);
    }


    // =========================================================================================
    // Getters
    // =========================================================================================

    public boolean isCompleted() { return completed; }
    public String getKey() { return key; }
    public int getRequiredAmount() { return requiredAmount; }
    public int getProgress() { return progress; }
    public long getCompletedAtMillis() { return completedAtMillis; }
    public World.Environment getWorld() { return world; }
    public Type getTaskType() { return taskType; }
    public boolean isSrbpEnabled() { return srbpEnabled; }
}
