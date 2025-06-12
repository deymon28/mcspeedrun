package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TaskManager {
    private final Speedrun plugin;
    private final List<Task> allTasks = new ArrayList<>();
    private final Map<String, List<Task>> tasksByStage = new LinkedHashMap<>();
    private final List<String> orderedStageKeys = new ArrayList<>();
    private final Set<Material> allLogMaterials = new HashSet<>();
    private final Set<Material> allCookedFoodMaterials = new HashSet<>();
    private int currentStageIndex = 0;
    private final Map<UUID, Map<Material, Integer>> cumulativePlayerContributions = new ConcurrentHashMap<>();

    public TaskManager(Speedrun plugin) {
        this.plugin = plugin;
        populateMaterialSets();
        reloadTasks();
    }

    public void reloadTasks() {
        allTasks.clear();
        tasksByStage.clear();
        orderedStageKeys.clear();
        cumulativePlayerContributions.clear();
        currentStageIndex = 0;

        ConfigurationSection progressionSection = plugin.getConfigManager().getRawConfig().getConfigurationSection("progression");
        if (progressionSection == null) {
            plugin.getLogger().severe("'progression' section not found in config.yml! No tasks loaded.");
            return;
        }
        List<String> sortedStageKeys = new ArrayList<>(progressionSection.getKeys(false));
        sortedStageKeys.sort(Comparator.naturalOrder());
        for (String stageKey : sortedStageKeys) {
            ConfigurationSection stageSection = progressionSection.getConfigurationSection(stageKey);
            if (stageSection == null) continue;
            World.Environment world;
            try {
                world = World.Environment.valueOf(stageSection.getString("world", "NORMAL").toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("Invalid world in stage " + stageKey);
                continue;
            }
            ConfigurationSection tasksSection = stageSection.getConfigurationSection("tasks");
            if (tasksSection == null) continue;

            List<Task> currentStageTasks = new ArrayList<>();
            for (String taskKey : tasksSection.getKeys(false)) {
                ConfigurationSection taskInfo = tasksSection.getConfigurationSection(taskKey);
                if (taskInfo == null) continue;
                // FIX: Pass the plugin instance to the Task constructor for localization
                Task task = new Task(taskKey, taskInfo, world, plugin);
                allTasks.add(task);
                currentStageTasks.add(task);
            }
            tasksByStage.put(stageKey, currentStageTasks);
            orderedStageKeys.add(stageKey);
        }
    }

    public void skipStage() {
        getTasksForCurrentProgressionStage().forEach(task -> task.completed = true);
        checkForStageCompletion();
    }

    public void updateItemTasks() {
        if (plugin.getGameManager().isPaused()) return;
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.INVENTORY) {
            updateTasksFromInventory();
        } else {
            updateTasksFromCumulative();
        }
        for (Task task : allTasks) {
            task.updateCompletionStatus(plugin);
        }
        checkForStageCompletion();
    }

    private void checkForStageCompletion() {
        if (currentStageIndex >= orderedStageKeys.size()) return;
        if (isCurrentProgressionStageComplete()) {
            currentStageIndex++; // Advance index
            if (currentStageIndex < orderedStageKeys.size()) {
                Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.stage-complete"));
                plugin.getConfigManager().executeRewardCommands("on-stage-complete", null);
            } else {
                plugin.getLogger().info("All progression stages completed!");
            }
        }
    }

    // Getter for tasks in the current stage
    private List<Task> getTasksForCurrentProgressionStage() {
        if (currentStageIndex >= orderedStageKeys.size()) return Collections.emptyList();
        String currentStageKey = orderedStageKeys.get(currentStageIndex);
        return tasksByStage.getOrDefault(currentStageKey, Collections.emptyList());
    }

    // IMPROVEMENT: Renamed for clarity
    public Optional<String> getCurrentStageName() {
        if (currentStageIndex >= orderedStageKeys.size()) return Optional.empty();
        return Optional.of(orderedStageKeys.get(currentStageIndex));
    }

    public boolean isCurrentProgressionStageComplete() {
        return getTasksForCurrentProgressionStage().stream().allMatch(Task::isCompleted);
    }

    // Updates tasks from player inventories
    private void updateTasksFromInventory() {
        allTasks.forEach(task -> {
            if (task.taskType == Task.Type.ITEM) task.progress = 0;
        });
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null) updateProgressForItem(item.getType(), item.getAmount());
            }
        }
    }

    // Cumulative task updates
    private void updateTasksFromCumulative() {
        allTasks.forEach(task -> {
            if (task.taskType == Task.Type.ITEM) {
                task.progress = 0;
                Material taskMaterial = Material.getMaterial(task.key);
                if (taskMaterial != null) {
                    task.progress = cumulativePlayerContributions.values().stream()
                            .mapToInt(playerMap -> playerMap.getOrDefault(taskMaterial, 0)).sum();
                }
            }
        });
    }

    public void trackItemPickup(Player player, ItemStack item) {
        if (plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).merge(item.getType(), item.getAmount(), Integer::sum);
    }

    public void trackItemCraft(Player player, ItemStack item) {
        if (plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).merge(item.getType(), item.getAmount(), Integer::sum);
    }

    private void updateProgressForItem(Material material, int amount) {
        for (Task task : allTasks) {
            if (task.taskType != Task.Type.ITEM) continue;
            if ((task.key.equals("OAK_LOG") && allLogMaterials.contains(material)) ||
                    (task.key.equals("BREAD") && allCookedFoodMaterials.contains(material)) ||
                    task.key.equals(material.name())) {
                task.progress += amount;
            }
        }
    }

    public void onStructureFound(String structureKey, Player player) {
        allTasks.stream()
                .filter(t -> t.taskType == Task.Type.STRUCTURE && t.key.equalsIgnoreCase("STRUCTURE_" + structureKey))
                .findFirst()
                .ifPresent(task -> {
                    if (!task.isCompleted()) {
                        task.progress = 1;
                        task.updateCompletionStatus(plugin);
                        //Test sounds on player
                        player.playSound(player.getLocation(), Sound.MUSIC_DISC_13, 1.0f, 1.0f);

                    }
                });
        checkForStageCompletion();
    }

    public List<Task> getAllTasks() { return allTasks; }

    public List<Task> getTasksForWorld(World.Environment world) {
        return allTasks.stream().filter(task -> task.world == world).collect(Collectors.toList());
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
}