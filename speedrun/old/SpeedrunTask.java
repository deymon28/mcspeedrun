package org.speedrun.speedrun;

import org.bukkit.Material;

public class SpeedrunTask {
    private final String description;
    private final int duration;
    private final TaskType type;
    private final Object targetIdentifier;
    private final Material targetMaterial;
    private final int targetAmount;

    public SpeedrunTask(String description, int duration, TaskType type, Object targetIdentifier) {
        this(description, duration, type, targetIdentifier, null, 0);
    }

    public SpeedrunTask(String description, int duration, TaskType type, Material material, int amount) {
        this(description, duration, type, material.name(), material, amount);
    }

    private SpeedrunTask(String description, int duration, TaskType type, Object targetIdentifier, Material targetMaterial, int targetAmount) {
        this.description = description;
        this.duration = duration;
        this.type = type;
        this.targetIdentifier = targetIdentifier;
        this.targetMaterial = targetMaterial;
        this.targetAmount = targetAmount;
    }

    public String getDescription() { return description; }
    public int getDuration() { return duration; }
    public TaskType getType() { return type; }
    public Object getTargetIdentifier() { return targetIdentifier; }
    public Material getTargetMaterial() { return targetMaterial; }
    public int getTargetAmount() { return targetAmount; }
}