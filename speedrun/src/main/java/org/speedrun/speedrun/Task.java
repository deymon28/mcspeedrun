package org.speedrun.speedrun;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public class Task {
    enum Type {ITEM, STRUCTURE}

    final String key;
    final Type taskType;
    String displayName; // Now can be updated with localized name
    final int baseRequiredAmount;
    int requiredAmount;
    int progress = 0;
    boolean completed = false;
    final World.Environment world;

    public Task(String key, ConfigurationSection cs, World.Environment world, Speedrun plugin) {
        this.key = key;
        this.world = world;

        // FIX: Load display name from lang file first, with fallback to config.yml
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
    }

    public void scale(int playerCount, double multiplier) {
        if (playerCount <= 1) {
            this.requiredAmount = this.baseRequiredAmount;
            return;
        }
        this.requiredAmount = (int) Math.ceil(this.baseRequiredAmount * (1 + (playerCount - 1) * multiplier));
    }

    public void updateCompletionStatus(Speedrun plugin) {
        if (completed) return;

        if (progress >= requiredAmount) {
            completed = true;
            progress = requiredAmount;
            plugin.getLogger().info("[DEBUG] Task completion detected for " + plugin.getName() + ". Calling executeRewardCommands.");
            plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
        }
    }

    public boolean isCompleted() {
        return completed;
    }
}