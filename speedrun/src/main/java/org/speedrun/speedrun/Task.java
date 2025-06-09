package org.speedrun.speedrun;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

// =========================================================================================
// Task Data Class
// =========================================================================================
class Task {
    enum Type { ITEM, STRUCTURE }

    final String key;
    final Type taskType;
    final String displayName;
    final int baseRequiredAmount;
    int requiredAmount;
    int progress = 0;
    boolean completed = false;
    final World.Environment world;

    public Task(String key, ConfigurationSection cs, World.Environment world) {
        this.key = key;
        this.displayName = cs.getString("display-name", key);
        this.world = world;

        if (key.startsWith("STRUCTURE_")) {
            this.taskType = Type.STRUCTURE;
            this.baseRequiredAmount = 1;
        } else {
            this.taskType = Type.ITEM;
            this.baseRequiredAmount = cs.getInt("amount", 1);
        }
        this.requiredAmount = this.baseRequiredAmount; // Initial amount
    }

    public void scale(int playerCount, double multiplier) {
        if(playerCount <= 1) {
            this.requiredAmount = this.baseRequiredAmount;
            return;
        }
        this.requiredAmount = (int) Math.ceil(this.baseRequiredAmount * (1 + (playerCount - 1) * multiplier));
    }

    public void updateCompletionStatus(Speedrun plugin) {
        if (completed) return;

        if(progress >= requiredAmount) {
            completed = true;
            progress = requiredAmount; // Cap progress display
            // Fire reward
            plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
        }
    }

    public boolean isCompleted() {
        return completed;
    }
}
