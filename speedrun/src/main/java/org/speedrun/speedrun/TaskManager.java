package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// =========================================================================================
// Task Manager
// Manages all tasks and stages for the speedrun, including loading tasks from config,
// tracking player progress, and checking for stage completion.
// =========================================================================================
public class TaskManager {
    private final Speedrun plugin;
    private final List<Task> allTasks = new ArrayList<>();
    private final Set<Material> allLogMaterials = new HashSet<>();
    private final Set<Material> allCookedFoodMaterials = new HashSet<>();
    private int currentStageIndex = 0;

    // For CUMULATIVE mode: Tracks contributions of items from individual players.
    private final Map<UUID, Map<Material, Integer>> cumulativePlayerContributions = new ConcurrentHashMap<>();

    /**
     * Constructor for TaskManager.
     * @param plugin The main Speedrun plugin instance.
     */
    public TaskManager(Speedrun plugin) {
        this.plugin = plugin;
        populateMaterialSets(); // Initialize sets of materials (e.g., all types of logs).
        reloadTasks(); // Load tasks from the configuration.
    }

    /**
     * Clears existing tasks and reloads them from the plugin's configuration.
     * This method is called during plugin initialization and upon command reload.
     */
    public void reloadTasks() {
        allTasks.clear();
        cumulativePlayerContributions.clear();
        currentStageIndex = 0;

        ConfigurationSection progressionSection = plugin.getConfigManager().getRawConfig().getConfigurationSection("progression");
        if (progressionSection == null) {
            plugin.getLogger().severe("'progression' section not found in config.yml! No tasks loaded.");
            return;
        }

        // Sort keys numerically to ensure stages are processed in correct order (e.g., "1", "2", "10").
        List<String> sortedStageKeys = new ArrayList<>(progressionSection.getKeys(false));
        sortedStageKeys.sort(Comparator.naturalOrder());

        for (String stageKey : sortedStageKeys) {
            ConfigurationSection stageSection = progressionSection.getConfigurationSection(stageKey);
            if (stageSection == null) {
                plugin.getLogger().warning("Invalid stage section for key: " + stageKey);
                continue;
            }

            World.Environment world;
            String worldString = stageSection.getString("world", "NORMAL").toUpperCase(); // Changed default to "NORMAL"
            try {
                // Attempt to get the World.Environment enum from the config string.
                // Using "NORMAL" as the default for the Overworld, as "OVERWORLD" is not a direct enum constant.
                world = World.Environment.valueOf(worldString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("Invalid world environment '" + worldString + "' specified in stage " + stageKey + ". Skipping this stage.");
                continue; // Skip this stage if the world environment is invalid.
            }

            ConfigurationSection tasksSection = stageSection.getConfigurationSection("tasks");
            if(tasksSection == null) {
                plugin.getLogger().warning("No 'tasks' section found for stage: " + stageKey + ". Skipping tasks for this stage.");
                continue;
            }

            for(String taskKey : tasksSection.getKeys(false)) {
                ConfigurationSection taskInfo = tasksSection.getConfigurationSection(taskKey);
                if(taskInfo == null) {
                    plugin.getLogger().warning("Invalid task info for key: " + taskKey + " in stage " + stageKey + ". Skipping this task.");
                    continue;
                }

                Task task = new Task(taskKey, taskInfo, world);
                allTasks.add(task);
            }
        }
    }

    /**
     * Skips the current stage, marking all its tasks as complete and advancing to the next stage.
     */
    public void skipStage() {
        if (!isCurrentStageComplete()) {
            // Mark all tasks in current stage as complete to trigger progression.
            getTasksForCurrentStage().forEach(task -> task.completed = true);
        }
        checkForStageCompletion(); // This will now advance the stage if all are complete.
    }

    /**
     * Updates the progress of item-related tasks based on the configured tracking mode.
     * This method is called periodically (e.g., via a repeating task or event listener).
     */
    public void updateItemTasks() {
        if (plugin.getGameManager().isPaused()) return; // Do not update tasks if the game is paused.

        ConfigManager.TrackingMode mode = plugin.getConfigManager().getTrackingMode();
        if(mode == ConfigManager.TrackingMode.INVENTORY) {
            updateTasksFromInventory(); // Recalculate based on current player inventories.
        } else { // ConfigManager.TrackingMode.CUMULATIVE
            updateTasksFromCumulative(); // Sum up cumulative player contributions.
        }

        // After updating progress, ensure all tasks' completion statuses are accurate.
        for(Task task : allTasks) {
            task.updateCompletionStatus(plugin);
        }

        checkForStageCompletion(); // Check if the current stage has been completed.
    }

    /**
     * Checks if the current stage is complete and, if so, advances to the next incomplete stage.
     * Broadcasts messages and executes commands on stage completion.
     */
    private void checkForStageCompletion() {
        if (isCurrentStageComplete()) {
            // Find the index of the next incomplete stage.
            int nextStage = findNextIncompleteStage(currentStageIndex);
            if(nextStage != currentStageIndex) {
                currentStageIndex = nextStage; // Advance to the new stage.
                Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.stage-complete"));
                // Execute reward commands configured for stage completion.
                plugin.getConfigManager().executeRewardCommands("on-stage-complete", null);
            }
        }
    }

    /**
     * Finds the index of the next incomplete stage, starting from a given index.
     * @param startIndex The index to start checking from.
     * @return The index of the next incomplete stage, or the current index if no further incomplete stages are found.
     */
    private int findNextIncompleteStage(int startIndex) {
        // Get the distinct worlds that represent different stages, maintaining insertion order.
        // Collecting to a LinkedHashSet then converting to an ArrayList is necessary here
        // to ensure unique stages are processed in the order they first appear,
        // and to allow indexed access for stage progression.
        Set<World.Environment> distinctStages = allTasks.stream()
                .map(t -> t.world)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<World.Environment> stageOrder = new ArrayList<>(distinctStages);

        if(startIndex >= stageOrder.size()) return startIndex; // Already at the end of stages.

        World.Environment currentStageWorld = stageOrder.get(startIndex);

        // Check if all tasks for the current world stage are complete.
        boolean allComplete = allTasks.stream()
                .filter(t -> t.world == currentStageWorld)
                .allMatch(Task::isCompleted);

        if(allComplete && startIndex + 1 < stageOrder.size()) {
            return startIndex + 1; // If current stage is complete, move to the next stage.
        }

        return startIndex; // Stay on current stage if not complete or no next stage.
    }

    /**
     * Retrieves a list of tasks belonging to the current active stage.
     * @return A list of Task objects for the current stage.
     */
    public List<Task> getTasksForCurrentStage() {
        // Get the distinct worlds that represent different stages, maintaining insertion order.
        // Collecting to a LinkedHashSet then converting to an ArrayList is necessary here
        // to ensure unique stages are processed in the order they first appear,
        // and to allow indexed access for stage progression.
        Set<World.Environment> distinctStages = allTasks.stream()
                .map(t -> t.world)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<World.Environment> stageOrder = new ArrayList<>(distinctStages);

        if(currentStageIndex >= stageOrder.size()) return Collections.emptyList(); // No more stages.

        World.Environment currentWorld = stageOrder.get(currentStageIndex);
        return allTasks.stream().filter(t -> t.world == currentWorld).collect(Collectors.toList());
    }

    /**
     * Gets the display name of the current stage.
     * @return An Optional containing the current stage name (e.g., "NORMAL", "NETHER"), or empty if no tasks are loaded.
     */
    public Optional<String> getCurrentStageName() {
        return getTasksForCurrentStage().stream()
                .findFirst()
                .map(task -> task.world.name()); // Returns the enum name (e.g., "NORMAL", "NETHER").
    }

    /**
     * Checks if all tasks in the current stage are completed.
     * @return true if all tasks are completed, false otherwise.
     */
    public boolean isCurrentStageComplete() {
        return getTasksForCurrentStage().stream().allMatch(Task::isCompleted);
    }

    /**
     * Updates item task progress by summing items found in all online players' inventories.
     * This method resets task progress before recounting from inventories, suitable for INVENTORY tracking.
     */
    private void updateTasksFromInventory() {
        // Reset progress for ITEM tasks before recounting from inventories.
        allTasks.forEach(task -> {
            if (task.taskType == Task.Type.ITEM) {
                task.progress = 0;
            }
        });

        // Sum items from all online players' inventories.
        for(Player player : Bukkit.getOnlinePlayers()) {
            for(ItemStack item : player.getInventory().getContents()) {
                if (item == null) continue;
                updateProgressForItem(item.getType(), item.getAmount());
            }
        }
    }

    /**
     * Updates item task progress by summing cumulative contributions from all players.
     * This method resets task progress before summing, suitable for CUMULATIVE tracking.
     */
    private void updateTasksFromCumulative() {
        allTasks.forEach(task -> {
            if (task.taskType == Task.Type.ITEM) {
                task.progress = 0; // Reset before summing.
                Material taskMaterial = Material.getMaterial(task.key);
                if(taskMaterial == null) {
                    plugin.getLogger().warning("Task key '" + task.key + "' for item task is not a valid Material.");
                    return;
                }

                // Sum contributions for this material from all players and directly assign to progress.
                task.progress = cumulativePlayerContributions.values().stream()
                        .mapToInt(playerMap -> playerMap.getOrDefault(taskMaterial, 0))
                        .sum();
            }
        });
    }

    /**
     * Tracks an item pickup event for cumulative tracking mode.
     * @param player The player who picked up the item.
     * @param item The ItemStack that was picked up.
     */
    public void trackItemPickup(Player player, ItemStack item) {
        if(plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;

        // Merge the new amount into the player's cumulative contributions for this material.
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    /**
     * Tracks an item craft event for cumulative tracking mode.
     * @param player The player who crafted the item.
     * @param item The ItemStack that was crafted.
     */
    public void trackItemCraft(Player player, ItemStack item) {
        if(plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;

        // Merge the new amount into the player's cumulative contributions for this material.
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    /**
     * Updates the progress for a specific item material.
     * This is used internally by `updateTasksFromInventory` and `updateTasksFromCumulative`.
     * @param material The Material of the item.
     * @param amount The amount of the item.
     */
    private void updateProgressForItem(Material material, int amount) {
        for(Task task : allTasks) {
            if (task.taskType != Task.Type.ITEM) continue;

            // Handle special cases for 'OAK_LOG' (any log) and 'BREAD' (any cooked food).
            if (task.key.equals("OAK_LOG") && allLogMaterials.contains(material)) {
                task.progress += amount;
            } else if (task.key.equals("BREAD") && allCookedFoodMaterials.contains(material)) {
                task.progress += amount;
            } else if (task.key.equals(material.name())) { // Direct material match.
                task.progress += amount;
            }
        }
    }

    /**
     * Handles the event when a specific structure is found.
     * Marks the corresponding task as complete and checks for stage completion.
     * @param structureKey The key of the structure found (e.g., "VILLAGE", "END_PORTAL").
     */
    public void onStructureFound(String structureKey) {
        allTasks.stream()
                .filter(t -> t.taskType == Task.Type.STRUCTURE && t.key.equalsIgnoreCase(structureKey))
                .findFirst()
                .ifPresent(task -> {
                    task.progress = 1; // Mark as complete (1 for structure tasks).
                    task.updateCompletionStatus(plugin); // Update its status.
                });
        checkForStageCompletion(); // Check if this completion advanced the stage.
    }

    /**
     * Returns a list of all tasks loaded into the manager.
     * @return A List of all Task objects.
     */
    public List<Task> getAllTasks() { return allTasks; }

    /**
     * Returns a list of tasks specifically for a given world environment from the current stage.
     * @param world The World.Environment to filter tasks by.
     * @return A List of Task objects relevant to the specified world in the current stage.
     */
    public List<Task> getTasksForWorld(World.Environment world) {
        return getTasksForCurrentStage().stream()
                .filter(task -> task.world == world)
                .collect(Collectors.toList());
    }

    /**
     * Populates the sets of materials used for generic item tasks (e.g., all logs, all cooked foods).
     */
    private void populateMaterialSets() {
        // Add all Minecraft log materials.
        allLogMaterials.addAll(Arrays.asList(
                Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
                Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
                Material.CRIMSON_STEM, Material.WARPED_STEM
        ));
        // Add all Minecraft cooked food materials.
        allCookedFoodMaterials.addAll(Arrays.asList(
                Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN,
                Material.COOKED_SALMON, Material.COOKED_COD, Material.BAKED_POTATO,
                Material.BREAD // Although not 'cooked' in the same way, often grouped with cooked foods for tasks.
        ));
    }
}
