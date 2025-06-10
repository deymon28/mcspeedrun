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
    // allTasks now holds ALL tasks loaded from the config, regardless of their stage.
    private final List<Task> allTasks = new ArrayList<>();
    // tasksByStage and orderedStageKeys are kept to manage the *progression* of named stages
    // and trigger stage-specific rewards, but not for displaying tasks.
    private final Map<String, List<Task>> tasksByStage = new LinkedHashMap<>();
    private final List<String> orderedStageKeys = new ArrayList<>();

    private final Set<Material> allLogMaterials = new HashSet<>();
    private final Set<Material> allCookedFoodMaterials = new HashSet<>();
    private int currentStageIndex = 0; // Tracks the index of the current *progression* stage.

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
        allTasks.clear(); // Clear all tasks
        tasksByStage.clear(); // Clear stage-grouped tasks
        orderedStageKeys.clear(); // Clear stage order
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
            String worldString = stageSection.getString("world", "NORMAL").toUpperCase();
            try {
                world = World.Environment.valueOf(worldString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("Invalid world environment '" + worldString + "' specified in stage " + stageKey + ". Skipping this stage.");
                continue;
            }

            ConfigurationSection tasksSection = stageSection.getConfigurationSection("tasks");
            if (tasksSection == null) {
                plugin.getLogger().warning("No 'tasks' section found for stage: " + stageKey + ". Skipping tasks for this stage.");
                continue;
            }

            List<Task> currentStageTasks = new ArrayList<>();
            for (String taskKey : tasksSection.getKeys(false)) {
                ConfigurationSection taskInfo = tasksSection.getConfigurationSection(taskKey);
                if (taskInfo == null) {
                    plugin.getLogger().warning("Invalid task info for key: " + taskKey + " in stage " + stageKey + ". Skipping this task.");
                    continue;
                }

                Task task = new Task(taskKey, taskInfo, world);
                allTasks.add(task); // Add to the flat list of all tasks
                currentStageTasks.add(task); // Add to the stage-specific list
            }
            tasksByStage.put(stageKey, currentStageTasks); // Store tasks grouped by stage key
            orderedStageKeys.add(stageKey); // Maintain the order of stage keys
        }
    }

    /**
     * Skips the current stage, marking all its tasks as complete and advancing to the next stage.
     */
    public void skipStage() {
        // Mark all tasks in the current *progression* stage as complete to trigger progression.
        getTasksForCurrentProgressionStage().forEach(task -> task.completed = true);
        checkForStageCompletion(); // This will now advance the stage if all are complete.
    }

    /**
     * Updates the progress of item-related tasks based on the configured tracking mode.
     * This method is called periodically (e.g., via a repeating task or event listener).
     */
    public void updateItemTasks() {
        if (plugin.getGameManager().isPaused()) return; // Do not update tasks if the game is paused.

        ConfigManager.TrackingMode mode = plugin.getConfigManager().getTrackingMode();
        if (mode == ConfigManager.TrackingMode.INVENTORY) {
            updateTasksFromInventory(); // Recalculate based on current player inventories.
        } else { // ConfigManager.TrackingMode.CUMULATIVE
            updateTasksFromCumulative(); // Sum up cumulative player contributions.
        }

        // After updating progress, ensure all tasks' completion statuses are accurate.
        // Iterate over ALL tasks, as they are all "active" for progress tracking.
        for (Task task : allTasks) {
            task.updateCompletionStatus(plugin);
        }

        checkForStageCompletion(); // Check if the current *progression* stage has been completed.
    }

    /**
     * Checks if the current *progression* stage is complete and, if so, advances to the next incomplete stage.
     * Broadcasts messages and executes commands on stage completion.
     */
    private void checkForStageCompletion() {
        if (isCurrentProgressionStageComplete()) {
            int nextStageIndex = currentStageIndex + 1; // Try to advance to the next index.

            if (nextStageIndex < orderedStageKeys.size()) {
                currentStageIndex = nextStageIndex; // Advance to the new *progression* stage.
                Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.stage-complete"));
                plugin.getConfigManager().executeRewardCommands("on-stage-complete", null); // null for player, implies @a or similar
            } else {
                plugin.getLogger().info("All progression stages completed!");
                // Optionally, trigger game end here or a specific "all stages complete" event.
            }
        }
    }

    /**
     * Retrieves a list of tasks belonging to the current active *progression* stage.
     * This method is used internally for stage completion checks and advancement logic,
     * not for displaying tasks to the player based on their world.
     * @return A list of Task objects for the current *progression* stage.
     */
    private List<Task> getTasksForCurrentProgressionStage() {
        if (currentStageIndex >= orderedStageKeys.size()) {
            return Collections.emptyList(); // No more stages.
        }
        String currentStageKey = orderedStageKeys.get(currentStageIndex);
        return tasksByStage.getOrDefault(currentStageKey, Collections.emptyList());
    }

    /**
     * Gets the display name of the current *progression* stage.
     * @return An Optional containing the current stage name (e.g., "1_GETTING_STARTED"), or empty if no tasks are loaded.
     */
    public Optional<String> getCurrentStageName() {
        if (currentStageIndex >= orderedStageKeys.size()) {
            return Optional.empty(); // No more stages.
        }
        String currentStageKey = orderedStageKeys.get(currentStageIndex);
        return Optional.of(currentStageKey);
    }

    /**
     * Checks if all tasks in the current *progression* stage are completed.
     * @return true if all tasks in the current *progression* stage are completed, false otherwise.
     */
    public boolean isCurrentProgressionStageComplete() {
        return getTasksForCurrentProgressionStage().stream().allMatch(Task::isCompleted);
    }

    /**
     * Updates item task progress by summing items found in all online players' inventories.
     * This method resets task progress before recounting from inventories, suitable for INVENTORY tracking.
     */
    private void updateTasksFromInventory() {
        // Reset progress for ITEM tasks across ALL tasks before recounting from inventories.
        allTasks.forEach(task -> {
            if (task.taskType == Task.Type.ITEM) {
                task.progress = 0;
            }
        });

        // Sum items from all online players' inventories.
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
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
        // Reset progress for ITEM tasks across ALL tasks before summing.
        allTasks.forEach(task -> {
            if (task.taskType == Task.Type.ITEM) {
                task.progress = 0; // Reset before summing.
                Material taskMaterial = Material.getMaterial(task.key);
                if (taskMaterial == null) {
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
     * @param item   The ItemStack that was picked up.
     */
    public void trackItemPickup(Player player, ItemStack item) {
        if (plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;

        // Merge the new amount into the player's cumulative contributions for this material.
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    /**
     * Tracks an item craft event for cumulative tracking mode.
     * @param player The player who crafted the item.
     * @param item   The ItemStack that was crafted.
     */
    public void trackItemCraft(Player player, ItemStack item) {
        if (plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;

        // Merge the new amount into the player's cumulative contributions for this material.
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    /**
     * Updates the progress for a specific item material.
     * This is used internally by `updateTasksFromInventory` and `updateTasksFromCumulative`.
     * @param material The Material of the item.
     * @param amount   The amount of the item.
     */
    private void updateProgressForItem(Material material, int amount) {
        // Update progress for ALL item tasks that match.
        for (Task task : allTasks) {
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
        // Update any matching structure task across ALL tasks.
        allTasks.stream()
                .filter(t -> t.taskType == Task.Type.STRUCTURE && t.key.equalsIgnoreCase(structureKey))
                .findFirst()
                .ifPresent(task -> {
                    task.progress = 1; // Mark as complete (1 for structure tasks).
                    task.updateCompletionStatus(plugin); // Update its status.
                    plugin.getConfigManager().executeRewardCommands("on-task-complete", null); // Trigger task complete rewards
                });
        checkForStageCompletion(); // Check if this completion advanced the *progression* stage.
    }

    /**
     * Returns a list of all tasks loaded into the manager.
     * @return A List of all Task objects.
     */
    public List<Task> getAllTasks() {
        return allTasks;
    }

    /**
     * Returns a list of tasks specifically for a given world environment.
     * This method is intended to be used for DISPLAYING tasks to the player,
     * showing all relevant tasks for their current world regardless of progression stage.
     * @param world The World.Environment to filter tasks by.
     * @return A List of Task objects relevant to the specified world.
     */
    public List<Task> getTasksForWorld(World.Environment world) {
        return allTasks.stream()
                .filter(task -> task.world == world)
                .collect(Collectors.toList());
    }

    /**
     * Populates the sets of materials used for generic item tasks (e.g., all logs, all cooked foods).
     */
    private void populateMaterialSets() {
        // Add all Minecraft log materials.
        allLogMaterials.addAll(Arrays.asList(
                Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.JUNGLE_LOG,
                Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG,
                Material.BAMBOO_BLOCK,

                // Stripped logs
                Material.STRIPPED_OAK_LOG, Material.STRIPPED_BIRCH_LOG, Material.STRIPPED_SPRUCE_LOG,
                Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_ACACIA_LOG, Material.STRIPPED_DARK_OAK_LOG,
                Material.STRIPPED_MANGROVE_LOG, Material.STRIPPED_CHERRY_LOG, Material.STRIPPED_BAMBOO_BLOCK,

                // Wood blocks
                Material.OAK_WOOD, Material.BIRCH_WOOD, Material.SPRUCE_WOOD, Material.JUNGLE_WOOD,
                Material.ACACIA_WOOD, Material.DARK_OAK_WOOD, Material.MANGROVE_WOOD, Material.CHERRY_WOOD,

                // Stripped wood blocks
                Material.STRIPPED_OAK_WOOD, Material.STRIPPED_BIRCH_WOOD, Material.STRIPPED_SPRUCE_WOOD,
                Material.STRIPPED_JUNGLE_WOOD, Material.STRIPPED_ACACIA_WOOD, Material.STRIPPED_DARK_OAK_WOOD,
                Material.STRIPPED_MANGROVE_WOOD, Material.STRIPPED_CHERRY_WOOD,

                // Nether logs
                Material.CRIMSON_STEM, Material.WARPED_STEM, Material.CRIMSON_HYPHAE, Material.WARPED_HYPHAE,
                Material.STRIPPED_CRIMSON_STEM, Material.STRIPPED_WARPED_STEM, Material.STRIPPED_CRIMSON_HYPHAE,
                Material.STRIPPED_WARPED_HYPHAE
        ));
        // Add all Minecraft cooked food materials.
        allCookedFoodMaterials.addAll(Arrays.asList(
                Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN, Material.COOKED_SALMON,
                Material.COOKED_COD, Material.BAKED_POTATO, Material.RABBIT_STEW, Material.MUSHROOM_STEW,
                Material.BEETROOT_SOUP, Material.PUMPKIN_PIE, Material.DRIED_KELP, Material.COOKED_MUTTON,
                Material.COOKED_RABBIT, Material.BREAD, Material.SUSPICIOUS_STEW, Material.SWEET_BERRIES,
                Material.GLOW_BERRIES
        ));
    }
}
