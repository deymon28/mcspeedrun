package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.Task;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages all speedrun tasks, their progression, and stages.
 * It loads tasks from the config, tracks their progress, and handles stage completion.
 * |
 * Керує всіма завданнями спідрану, їхнім прогресом та етапами.
 * Завантажує завдання з конфігурації, відстежує їхній прогрес та обробляє завершення етапів.
 */
public class TaskManager {
    private final Speedrun plugin;
    private final List<Task> allTasks = new ArrayList<>();
    // Tasks grouped by their progression stage key.
    // Завдання, згруповані за ключем етапу прогресії.
    private final Map<String, List<Task>> tasksByStage = new LinkedHashMap<>();
    // An ordered list of stage keys to maintain progression order.
    // Впорядкований список ключів етапів для збереження послідовності проходження.
    private final List<String> orderedStageKeys = new ArrayList<>();
    private int currentStageIndex = 0;

    // For CUMULATIVE tracking mode, stores items collected by each player.
    // Для режиму відстеження CUMULATIVE, зберігає предмети, зібрані кожним гравцем.
    private final Map<UUID, Map<Material, Integer>> cumulativePlayerContributions = new ConcurrentHashMap<>();

    // Pre-populated sets for efficient checking of item groups.
    // Попередньо заповнені набори для ефективної перевірки груп предметів.
    private final Set<Material> allLogMaterials = new HashSet<>();
    private final Set<Material> allCookedFoodMaterials = new HashSet<>();

    public TaskManager(Speedrun plugin) {
        this.plugin = plugin;
        populateMaterialSets();
        reloadTasks();
    }

    /**
     * Reloads all tasks and progression stages from the config.yml file.
     * This clears all current task progress.
     * |
     * Перезавантажує всі завдання та етапи проходження з файлу config.yml.
     * Це очищає весь поточний прогрес завдань.
     */
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

        // Sort stage keys to ensure they are loaded in order (e.g., "01_stage", "02_stage").
        // Сортуємо ключі етапів, щоб гарантувати їх завантаження по порядку (напр., "01_stage", "02_stage").
        List<String> sortedStageKeys = new ArrayList<>(progressionSection.getKeys(false));
        sortedStageKeys.sort(Comparator.naturalOrder());

        for (String stageKey : sortedStageKeys) {
            ConfigurationSection stageSection = progressionSection.getConfigurationSection(stageKey);
            if (stageSection == null) continue;

            World.Environment world;
            try {
                world = World.Environment.valueOf(stageSection.getString("world", "NORMAL").toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid world specified in stage '" + stageKey + "'. Defaulting to NORMAL.");
                world = World.Environment.NORMAL;
            }

            ConfigurationSection tasksSection = stageSection.getConfigurationSection("tasks");
            if (tasksSection == null) continue;

            List<Task> currentStageTasks = new ArrayList<>();
            for (String taskKey : tasksSection.getKeys(false)) {
                ConfigurationSection taskInfo = tasksSection.getConfigurationSection(taskKey);
                if (taskInfo != null) {
                    Task task = new Task(taskKey, taskInfo, world, plugin);
                    allTasks.add(task);
                    currentStageTasks.add(task);
                }
            }
            tasksByStage.put(stageKey, currentStageTasks);
            orderedStageKeys.add(stageKey);
        }

        // Apply initial resource scaling based on the current player count.
        // Застосовуємо початкове масштабування ресурсів на основі поточної кількості гравців.
        applyPlayerScaling();
    }

    /**
     * Applies resource requirement scaling to all applicable tasks based on the online player count.
     * Застосовує масштабування вимог до ресурсів до всіх відповідних завдань на основі кількості гравців онлайн.
     */
    private void applyPlayerScaling() {
        if (plugin.getConfigManager().isPlayerScalingEnabled()) {
            int playerCount = Math.max(1, Bukkit.getOnlinePlayers().size());
            double multiplier = plugin.getConfigManager().getPlayerScalingMultiplier();
            allTasks.forEach(task -> {
                if (task.isSrbpEnabled()) { // srbp = Scale Resources By Playercount
                    task.scale(playerCount, multiplier);
                }
            });
        }
    }

    /**
     * Forcibly completes all tasks in the current stage, advancing to the next.
     * Used by an admin command.
     * |
     * Примусово завершує всі завдання поточного етапу, переходячи до наступного.
     * Використовується командою адміністратора.
     */
    public void skipStage() {
        getTasksForCurrentProgressionStage().forEach(task -> task.completed = true);
        checkForStageCompletion();
    }

    /**
     * Updates the progress of all item-based tasks.
     * Called periodically by the GameManager's main timer.
     * |
     * Оновлює прогрес усіх завдань, пов'язаних із предметами.
     * Викликається періодично головним таймером GameManager.
     */
    public void updateItemTasks() {
        if (plugin.getGameManager().isPaused()) return;

        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.INVENTORY) {
            updateTasksFromInventories();
        } else { // CUMULATIVE
            updateTasksFromCumulativeContributions();
        }

        // After updating progress, check if any tasks are now complete.
        // Після оновлення прогресу перевіряємо, чи не завершилося якесь завдання.
        allTasks.forEach(task -> task.updateCompletionStatus(plugin));
        checkForStageCompletion();
    }

    /**
     * Checks if all tasks in the current stage are complete. If so, advances to the next stage.
     * Перевіряє, чи всі завдання поточного етапу завершені. Якщо так, переходить до наступного етапу.
     */
    private void checkForStageCompletion() {
        if (currentStageIndex >= orderedStageKeys.size()) return; // All stages are already complete. / Всі етапи вже завершено.

        if (isCurrentProgressionStageComplete()) {
            currentStageIndex++;
            if (currentStageIndex < orderedStageKeys.size()) {
                // More stages remaining.
                // Залишилися ще етапи.
                Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.stage-complete"));
                plugin.getConfigManager().executeRewardCommands("on-stage-complete", null);
            } else {
                // This was the final stage.
                // Це був останній етап.
                plugin.getLogger().info("All progression stages completed!");
            }
        }
    }

    /**
     * Updates task progress by scanning all online players' inventories.
     * Оновлює прогрес завдань, скануючи інвентарі всіх гравців онлайн.
     */
    private void updateTasksFromInventories() {
        // Reset progress for item tasks before recounting.
        // Скидаємо прогрес для завдань з предметами перед перерахунком.
        allTasks.forEach(task -> {
            if (task.getTaskType() == Task.Type.ITEM) task.progress = 0;
        });

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null) {
                    updateProgressForItem(item.getType(), item.getAmount());
                }
            }
        }
    }

    /**
     * Updates task progress based on the cumulative contributions map.
     * Оновлює прогрес завдань на основі мапи сукупних внесків.
     */
    private void updateTasksFromCumulativeContributions() {
        allTasks.forEach(task -> {
            if (task.getTaskType() == Task.Type.ITEM) {
                // Sum the contributions of all players for the required material.
                // Сумуємо внески всіх гравців для потрібного матеріалу.
                task.progress = cumulativePlayerContributions.values().stream()
                        .mapToInt(playerMap -> playerMap.getOrDefault(Material.getMaterial(task.getKey()), 0))
                        .sum();
            }
        });
    }

    /**
     * Tracks an item that a player has picked up (for CUMULATIVE mode).
     * Відстежує предмет, який гравець підібрав (для режиму CUMULATIVE).
     */
    public void trackItemPickup(Player player, ItemStack item) {
        if (plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    /**
     * Tracks an item that a player has crafted (for CUMULATIVE mode).
     * Відстежує предмет, який гравець скрафтив (для режиму CUMULATIVE).
     */
    public void trackItemCraft(Player player, ItemStack item) {
        if (plugin.getConfigManager().getTrackingMode() != ConfigManager.TrackingMode.CUMULATIVE) return;
        cumulativePlayerContributions.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(item.getType(), item.getAmount(), Integer::sum);
    }

    /**
     * Adds a given amount to the progress of any task matching the material.
     * Handles grouped materials like logs or cooked food.
     * |
     * Додає задану кількість до прогресу будь-якого завдання, що відповідає матеріалу.
     * Обробляє згруповані матеріали, як-от колоди чи приготована їжа.
     */
    private void updateProgressForItem(Material material, int amount) {
        for (Task task : allTasks) {
            if (task.getTaskType() != Task.Type.ITEM || task.isCompleted()) continue;

            boolean matchesGroup = (task.getKey().equals("OAK_LOG") && allLogMaterials.contains(material)) ||
                    (task.getKey().equals("BREAD") && allCookedFoodMaterials.contains(material));

            if (matchesGroup || task.getKey().equals(material.name())) {
                task.progress += amount;
            }
        }
    }

    /**
     * Called by StructureManager when a structure is found.
     * Completes any corresponding structure-based tasks.
     * |
     * Викликається StructureManager, коли знайдено структуру.
     * Завершує будь-які відповідні завдання, пов'язані зі структурами.
     */
    public void onStructureFound(String structureKey, Player player) {
        allTasks.stream()
                .filter(t -> t.getTaskType() == Task.Type.STRUCTURE && t.getKey().equalsIgnoreCase("STRUCTURE_" + structureKey))
                .findFirst()
                .ifPresent(task -> {
                    if (!task.isCompleted()) {
                        task.progress = 1;
                        task.updateCompletionStatus(plugin);
                        // Play a sound for the player who found it, or for everyone if found by the environment.
                        // Програємо звук для гравця, що знайшов, або для всіх, якщо знайдено оточенням.
                        if (player != null) {
                            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                        } else {
                            Bukkit.getOnlinePlayers().forEach(p ->
                                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f));
                        }
                    }
                });
        checkForStageCompletion();
    }

    // =========================================================================================
    // Getters
    // =========================================================================================

    /** @return A list of all loaded tasks. / Список усіх завантажених завдань. */
    public List<Task> getAllTasks() { return allTasks; }

    /** @return A list of tasks relevant to the specified world environment. / Список завдань, що стосуються вказаного світу. */
    public List<Task> getTasksForWorld(World.Environment world) {
        return allTasks.stream().filter(task -> task.getWorld() == world).collect(Collectors.toList());
    }

    /** @return A list of tasks for the current progression stage. / Список завдань для поточного етапу прогресії. */
    private List<Task> getTasksForCurrentProgressionStage() {
        if (currentStageIndex >= orderedStageKeys.size()) return Collections.emptyList();
        String currentStageKey = orderedStageKeys.get(currentStageIndex);
        return tasksByStage.getOrDefault(currentStageKey, Collections.emptyList());
    }

    /** @return The name of the current progression stage, if any. / Назва поточного етапу прогресії, якщо є. */
    public Optional<String> getCurrentStageName() {
        if (currentStageIndex >= orderedStageKeys.size()) return Optional.empty();
        return Optional.of(orderedStageKeys.get(currentStageIndex));
    }

    /** @return True if all tasks in the current stage are complete. / True, якщо всі завдання поточного етапу завершені. */
    public boolean isCurrentProgressionStageComplete() {
        return getTasksForCurrentProgressionStage().stream().allMatch(Task::isCompleted);
    }

    /**
     * Populates the material sets for grouped item tasks.
     * Заповнює набори матеріалів для групових завдань з предметами.
     */
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
