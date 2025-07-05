package org.speedrun.speedrun;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public class Task {
    // Изменено на public, чтобы быть доступным из TaskManager
    public enum Type {ITEM, STRUCTURE}

    final String key;
    final Type taskType;
    String displayName; // Теперь может быть обновлено локализованным именем
    final int baseRequiredAmount;
    int requiredAmount;
    int progress = 0;
    boolean completed = false;
    final World.Environment world;
    private final boolean srbpEnabled; // НОВОЕ: Флаг для масштабирования по количеству игроков

    // Конструктор для задач, загруженных из конфига
    public Task(String key, ConfigurationSection cs, World.Environment world, Speedrun plugin) {
        this.key = key;
        this.world = world;

        // Загрузить отображаемое имя из файла языка сначала, с запасным вариантом из config.yml
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
        // НОВОЕ: Считать флаг srbp из секции конфига, по умолчанию false, если не указано
        this.srbpEnabled = cs.getBoolean("srbp", false);
    }

    public void scale(int playerCount, double multiplier) {
        // Масштабировать только если srbpEnabled истинно для этой конкретной задачи
        if (!srbpEnabled) {
            this.requiredAmount = this.baseRequiredAmount; // Убедиться, что оно сброшено, если srbp ложно
            return;
        }

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
            plugin.getLogger().info("[DEBUG] Task completion detected for " + displayName + ". Calling executeRewardCommands.");
            // Передать null для игрока, если это общее завершение задачи, или игрока, который ее завершил, если применимо
            plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
        }
    }

    public boolean isCompleted() {
        return completed;
    }

    // Геттеры, которые используются ScoreboardManager и TaskManager
    public String getKey() {
        return key;
    }

    public int getRequiredAmount() {
        return requiredAmount;
    }

    public int getProgress() {
        return progress;
    }

    public World.Environment getWorld() {
        return world;
    }

    public Type getTaskType() {
        return taskType;
    }

    public boolean isSrbpEnabled() {
        return srbpEnabled;
    }
}