package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.Task;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.utils.RewardUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns staged speedrun task progression.
 */
public class TaskManager {
    private final Speedrun plugin;
    private final List<StageDefinition> stages = new ArrayList<>();
    private final List<Task> allTasks = new ArrayList<>();
    private int currentStageIndex = 0;

    private final Set<String> discoveredStructures = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<Material, Integer>> cumulativePlayerContributions = new ConcurrentHashMap<>();
    private final Set<Material> allLogMaterials = new HashSet<>();
    private final Set<Material> allCookedFoodMaterials = new HashSet<>();

    public TaskManager(Speedrun plugin) {
        this.plugin = plugin;
        populateMaterialSets();
        reloadTasks();
    }

    /**
     * Reloads all stage definitions and clears runtime task progress.
     */
    public void reloadTasks() {
        allTasks.clear();
        stages.clear();
        discoveredStructures.clear();
        cumulativePlayerContributions.clear();
        currentStageIndex = 0;

        ConfigurationSection progressionSection = plugin.getConfigManager().getRawConfig().getConfigurationSection("progression");
        if (progressionSection == null) {
            plugin.getLogger().severe("'progression' section not found in config.yml! No tasks loaded.");
            return;
        }

        List<String> sortedStageKeys = new ArrayList<>(progressionSection.getKeys(false));
        sortedStageKeys.remove("settings");
        sortedStageKeys.sort(String::compareTo);

        for (String stageKey : sortedStageKeys) {
            ConfigurationSection stageSection = progressionSection.getConfigurationSection(stageKey);
            if (stageSection == null) {
                continue;
            }

            World.Environment world = parseWorld(stageKey, stageSection.getString("world", "NORMAL"));
            ConfigurationSection tasksSection = stageSection.getConfigurationSection("tasks");
            List<Task> stageTasks = new ArrayList<>();

            if (tasksSection != null) {
                for (String taskKey : tasksSection.getKeys(false)) {
                    ConfigurationSection taskInfo = tasksSection.getConfigurationSection(taskKey);
                    if (taskInfo == null) {
                        continue;
                    }
                    Task task = new Task(taskKey, taskInfo, world, plugin);
                    allTasks.add(task);
                    stageTasks.add(task);
                }
            }

            String displayName = plugin.getConfigManager().getLangString(
                    "stages." + stageKey,
                    stageSection.getString("display-name", stageKey));
            stages.add(new StageDefinition(stageKey, displayName, world, stageTasks));
        }

        rescaleTasksForOnlinePlayers();
        advanceCompletedStages(false);
    }

    public void rescaleTasksForOnlinePlayers() {
        if (!plugin.getConfigManager().isPlayerScalingEnabled()) {
            allTasks.forEach(task -> task.scale(1, 0));
            return;
        }

        int playerCount = Math.max(1, Bukkit.getOnlinePlayers().size());
        double multiplier = plugin.getConfigManager().getPlayerScalingMultiplier();
        allTasks.forEach(task -> task.scale(playerCount, multiplier));
    }

    public void skipStage() {
        getTasksForCurrentProgressionStage().forEach(task -> task.forceComplete(plugin));
        advanceCompletedStages(true);
    }

    public void updateItemTasks() {
        if (plugin.getGameManager().isPaused() || isProgressionComplete()) {
            return;
        }

        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.INVENTORY) {
            updateCurrentStageTasksFromInventories();
        } else {
            updateCurrentStageTasksFromCumulativeContributions();
        }

        getTasksForCurrentProgressionStage().forEach(task -> task.updateCompletionStatus(plugin));
        advanceCompletedStages(true);
    }

    public void trackItemPickup(Player player, ItemStack item) {
        if (item == null || plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) {
            return;
        }
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    public void trackItemCraft(Player player, ItemStack item) {
        if (item == null || plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) {
            return;
        }
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    public boolean onStructureFound(String structureKey, Player player) {
        discoveredStructures.add(structureKey.toUpperCase(Locale.ROOT));
        boolean completedActiveTask = applyDiscoveredStructuresToCurrentStage(player);
        advanceCompletedStages(true);
        return completedActiveTask;
    }

    private boolean applyDiscoveredStructuresToCurrentStage(Player player) {
        boolean[] completedAnyTask = {false};
        getTasksForCurrentProgressionStage().stream()
                .filter(task -> task.getTaskType() == Task.Type.STRUCTURE)
                .filter(task -> discoveredStructures.contains(structureKeyFromTask(task)))
                .forEach(task -> {
                    boolean wasCompleted = task.isCompleted();
                    task.setProgress(1);
                    task.updateCompletionStatus(plugin);
                    if (!wasCompleted && task.isCompleted()) {
                        completedAnyTask[0] = true;
                        playStructureTaskSound();
                    }
        });
        return completedAnyTask[0];
    }

    private String structureKeyFromTask(Task task) {
        return task.getKey().substring("STRUCTURE_".length()).toUpperCase(Locale.ROOT);
    }

    public List<Task> getAllTasks() {
        return Collections.unmodifiableList(allTasks);
    }

    /**
     * Returns visible tasks for the player's current world according to the configured display mode.
     */
    public List<Task> getTasksForWorld(World.Environment world) {
        ConfigManager.TaskDisplayMode displayMode = plugin.getConfigManager().getTaskDisplayMode();
        if (displayMode == ConfigManager.TaskDisplayMode.ALL_STAGES
                || displayMode == ConfigManager.TaskDisplayMode.ALL_GAME_STAGES) {
            return allTasks.stream()
                    .filter(task -> task.getWorld() == world)
                    .filter(this::shouldDisplayTask)
                    .toList();
        }

        if (isProgressionComplete()) {
            return Collections.emptyList();
        }

        StageDefinition currentStage = stages.get(currentStageIndex);
        if (currentStage.world() != world) {
            return Collections.emptyList();
        }

        return currentStage.tasks().stream()
                .filter(this::shouldDisplayTask)
                .toList();
    }

    private boolean shouldDisplayTask(Task task) {
        if (!task.isCompleted() || !plugin.getConfigManager().isCompletedTaskHideEnabled()) {
            return true;
        }
        long completedAt = task.getCompletedAtMillis();
        return completedAt <= 0
                || System.currentTimeMillis() - completedAt < plugin.getConfigManager().getCompletedTaskHideTimeoutMillis();
    }

    public Optional<String> getCurrentStageName() {
        if (isProgressionComplete()) {
            return Optional.empty();
        }
        return Optional.of(stages.get(currentStageIndex).displayName());
    }

    public boolean isCurrentProgressionStageComplete() {
        return isProgressionComplete() || getTasksForCurrentProgressionStage().stream().allMatch(Task::isCompleted);
    }

    private void updateCurrentStageTasksFromInventories() {
        List<Task> currentTasks = getTasksForCurrentProgressionStage();
        currentTasks.forEach(Task::resetProgress);

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null) {
                    updateProgressForItem(currentTasks, item.getType(), item.getAmount());
                }
            }
        }
    }

    private void updateCurrentStageTasksFromCumulativeContributions() {
        for (Task task : getTasksForCurrentProgressionStage()) {
            if (task.getTaskType() != Task.Type.ITEM) {
                continue;
            }

            int progress = cumulativePlayerContributions.values().stream()
                    .mapToInt(playerMap -> playerMap.entrySet().stream()
                            .filter(entry -> matchesTaskMaterial(task, entry.getKey()))
                            .mapToInt(Map.Entry::getValue)
                            .sum())
                    .sum();
            task.setProgress(progress);
        }
    }

    private void updateProgressForItem(List<Task> tasks, Material material, int amount) {
        for (Task task : tasks) {
            if (task.getTaskType() != Task.Type.ITEM || task.isCompleted()) {
                continue;
            }

            if (matchesTaskMaterial(task, material)) {
                task.addProgress(amount);
            }
        }
    }

    private boolean matchesTaskMaterial(Task task, Material material) {
        return (task.getKey().equals("OAK_LOG") && allLogMaterials.contains(material))
                || (task.getKey().equals("BREAD") && allCookedFoodMaterials.contains(material))
                || task.getKey().equals(material.name());
    }

    private void advanceCompletedStages(boolean announce) {
        boolean advanced = false;

        while (!isProgressionComplete() && getTasksForCurrentProgressionStage().stream().allMatch(Task::isCompleted)) {
            currentStageIndex++;
            advanced = true;

            if (!isProgressionComplete()) {
                applyDiscoveredStructuresToCurrentStage(null);
            }

            if (announce && !isProgressionComplete()) {
                MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.stage-complete"));
                plugin.getConfigManager().executeRewardCommands("on-stage-complete", null);
            }
        }

        if (advanced && isProgressionComplete()) {
            plugin.getLogger().info("All progression stages completed!");
        }
    }

    private List<Task> getTasksForCurrentProgressionStage() {
        if (isProgressionComplete()) {
            return Collections.emptyList();
        }
        return stages.get(currentStageIndex).tasks();
    }

    private boolean isProgressionComplete() {
        return currentStageIndex >= stages.size();
    }

    private World.Environment parseWorld(String stageKey, String rawWorld) {
        try {
            return World.Environment.valueOf(rawWorld.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid world specified in stage '" + stageKey + "'. Defaulting to NORMAL.");
            return World.Environment.NORMAL;
        }
    }

    private void playStructureTaskSound() {
        RewardUtil.playStructureTaskCompleteSound(null);
    }

    private void populateMaterialSets() {
        allLogMaterials.addAll(Arrays.asList(
                Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG, Material.ACACIA_LOG,
                Material.DARK_OAK_LOG, Material.CRIMSON_STEM, Material.WARPED_STEM, Material.OAK_WOOD, Material.SPRUCE_WOOD,
                Material.BIRCH_WOOD, Material.JUNGLE_WOOD, Material.ACACIA_WOOD, Material.DARK_OAK_WOOD, Material.CRIMSON_HYPHAE,
                Material.WARPED_HYPHAE, Material.STRIPPED_OAK_LOG, Material.STRIPPED_SPRUCE_LOG, Material.STRIPPED_BIRCH_LOG,
                Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_ACACIA_LOG, Material.STRIPPED_DARK_OAK_LOG,
                Material.STRIPPED_CRIMSON_STEM, Material.STRIPPED_WARPED_STEM, Material.STRIPPED_OAK_WOOD,
                Material.STRIPPED_SPRUCE_WOOD, Material.STRIPPED_BIRCH_WOOD, Material.STRIPPED_JUNGLE_WOOD,
                Material.STRIPPED_ACACIA_WOOD, Material.STRIPPED_DARK_OAK_WOOD, Material.STRIPPED_CRIMSON_HYPHAE,
                Material.STRIPPED_WARPED_HYPHAE
        ));
        allCookedFoodMaterials.addAll(Arrays.asList(
                Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN, Material.COOKED_SALMON,
                Material.COOKED_COD, Material.BAKED_POTATO, Material.BREAD, Material.COOKED_MUTTON, Material.COOKED_RABBIT,
                Material.PUMPKIN_PIE, Material.MUSHROOM_STEW, Material.RABBIT_STEW, Material.BEETROOT_SOUP
        ));
    }

    private record StageDefinition(String key, String displayName, World.Environment world, List<Task> tasks) {
    }
}
