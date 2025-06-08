package org.speedrun.speedrun;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


public final class Speedrun extends JavaPlugin {

    private int totalSeconds = 0;
    private boolean isTimerRunning = true;

    // Use LinkedHashMap to save the order of displaying locations
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();

    private BukkitRunnable mainTimerTask;

    // Challenges for the Upper World and progress
    private final Map<Material, Integer> overworldTasks = new LinkedHashMap<>();
    private final Map<Material, Integer> overworldProgress = new ConcurrentHashMap<>();
    // New map to track the completion of Upper World tasks
    private final Map<Material, Boolean> overworldTasksCompleted = new ConcurrentHashMap<>();

    // Challenges for the Lower World and Progress
    private final Map<Material, Integer> netherTasks = new LinkedHashMap<>();
    private final Map<Material, Integer> netherProgress = new ConcurrentHashMap<>();
    // New map for tracking the completion of Lower World tasks
    private final Map<Material, Boolean> netherTasksCompleted = new ConcurrentHashMap<>();

    // Timer for the village (10 minutes = 600 seconds)
    private static final long VILLAGE_TIMEOUT_SECONDS = 600;
    private long villageTimeElapsed = 0; // Time elapsed from the start of the speedrun to the village timeout

    // Set for storing all materials that are considered ‘wood’ (all kinds of logs and wood blocks)
    private final Set<Material> allLogMaterials = new HashSet<>();
    // A set to store all materials that are considered ‘ready-to-eat’ food
    private final Set<Material> allCookedFoodMaterials = new HashSet<>();


    @Override
    public void onEnable() {
        // Register the event listener
        getServer().getPluginManager().registerEvents(new SpeedrunListener(this), this);

        // Initialise structures and tasks, including populating the list of logs and meals
        initializeStructures();
        initializeTasks();

        // Start the main timers
        startTimers();

        // Register commands using Brigadier
        registerBrigadierCommands();

        getLogger().info("Speedrun plugin has been enabled.");
    }

    @Override
    public void onDisable() {
        // Cancel the timer task when the plugin is disabled
        if (mainTimerTask != null) {
            mainTimerTask.cancel();
        }
        getLogger().info("Speedrun plugin has been disabled.");
    }

    /**
     * Registers plugin commands using the Brigadier system.
     * This ensures auto-completion and improved command handling.
     */
    private void registerBrigadierCommands() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            // Create the root command "/run"
            LiteralArgumentBuilder<CommandSourceStack> runCommand = Commands.literal("run")
                    .then(Commands.literal("pause")
                            .executes(this::handlePauseCommand) // Logic for "/run pause"
                    )
                    .then(Commands.literal("reset")
                            .executes(this::handleResetCommand) // Logic for "/run reset"
                    );

            // Register the command. Aliases are taken from plugin.yml.
            commands.registrar().register(runCommand.build());
        });
    }

    /**
     * Handler for the "/run pause" command.
     *
     * @param ctx Brigadier command context.
     * @return Command execution result.
     */
    private int handlePauseCommand(CommandContext<CommandSourceStack> ctx) {
        // Check permissions (Brigadier also supports declarative permissions,
        // but for simplicity, we leave the check here).
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("speedrun.control")) {
            sender.sendMessage(Component.text("You dont have premission.", NamedTextColor.RED));
            return 0; // Return 0 for failure
        }

        isTimerRunning = !isTimerRunning;
        Bukkit.broadcast(Component.text("Timer " + (isTimerRunning ? "resume" : "paused") + ".", NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS; // Return 1 for successful execution
    }

    /**
     * Handler for the "/run reset" command.
     *
     * @param ctx Brigadier command context.
     * @return Command execution result.
     */
    private int handleResetCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("speedrun.control")) {
            sender.sendMessage(Component.text("You dont have premission.", NamedTextColor.RED));
            return 0;
        }

        totalSeconds = 0;
        isTimerRunning = true;
        initializeStructures();
        initializeTasks(); // Reinitialize tasks to reset progress AND completion status
        Bukkit.broadcast(Component.text("The speedrun was restarted.", NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }


    /**
     * Initializes or resets the list of tracked structures.
     */
    private void initializeStructures() {
        foundLocations.clear();
        foundLocations.put("Village", null);
        foundLocations.put("Nether P(Over)", null); // Portal in the overworld
        foundLocations.put("Nether P(Neth)", null); // Portal in the nether
        foundLocations.put("Fortress", null);
        foundLocations.put("Bastion", null);
        foundLocations.put("End Portal", null);
        villageTimeElapsed = 0;
    }

    /**
     * Initializes item collection tasks for both worlds.
     */
    private void initializeTasks() {
        populateMaterials(); // Fill the list of all log types and cooked food types
        initOverworldTasks();
        initNetherTasks();
    }

    /**
     * Defines tasks and their required quantities for the Overworld.
     * Resets progress for these tasks.
     */
    private void initOverworldTasks() {
        overworldTasks.clear();
        overworldProgress.clear();
        overworldTasksCompleted.clear(); // Clear Completion Status

        overworldTasks.put(Material.OAK_LOG, 64);      // Wood (sum of all log types)
        overworldTasks.put(Material.COBBLESTONE, 64); // Stone
        overworldTasks.put(Material.IRON_INGOT, 7);   // Iron
        overworldTasks.put(Material.BREAD, 64);        // Cooked Food (use BREAD as key for simplicity)

        for (Material mat : overworldTasks.keySet()) {
            overworldProgress.put(mat, 0);
            overworldTasksCompleted.put(mat, false); // Initialise as unexecuted
        }
    }

    /**
     * Defines tasks and their required quantities for the Nether.
     * Resets progress for these tasks.
     */
    private void initNetherTasks() {
        netherTasks.clear();
        netherProgress.clear();
        netherTasksCompleted.clear(); // Clear Completion Status

        netherTasks.put(Material.ENDER_PEARL, 16);    // Ender Pearl (corrected from Ender Eye based on common usage)
        netherTasks.put(Material.BLAZE_ROD, 8);    // Blaze Rods

        for (Material mat : netherTasks.keySet()) {
            netherProgress.put(mat, 0);
            netherTasksCompleted.put(mat, false); // Initialise as unexecuted
        }
    }

    /**
     * Fills the allLogMaterials and allCookedFoodMaterials sets.
     */
    private void populateMaterials() {
        allLogMaterials.clear();
        // Core logs
        allLogMaterials.add(Material.OAK_LOG);
        allLogMaterials.add(Material.BIRCH_LOG);
        allLogMaterials.add(Material.SPRUCE_LOG);
        allLogMaterials.add(Material.JUNGLE_LOG);
        allLogMaterials.add(Material.ACACIA_LOG);
        allLogMaterials.add(Material.DARK_OAK_LOG);
        allLogMaterials.add(Material.MANGROVE_LOG);
        allLogMaterials.add(Material.CHERRY_LOG);
        allLogMaterials.add(Material.BAMBOO_BLOCK);

        // Stripped logs
        allLogMaterials.add(Material.STRIPPED_OAK_LOG);
        allLogMaterials.add(Material.STRIPPED_BIRCH_LOG);
        allLogMaterials.add(Material.STRIPPED_SPRUCE_LOG);
        allLogMaterials.add(Material.STRIPPED_JUNGLE_LOG);
        allLogMaterials.add(Material.STRIPPED_ACACIA_LOG);
        allLogMaterials.add(Material.STRIPPED_DARK_OAK_LOG);
        allLogMaterials.add(Material.STRIPPED_MANGROVE_LOG);
        allLogMaterials.add(Material.STRIPPED_CHERRY_LOG);
        allLogMaterials.add(Material.STRIPPED_BAMBOO_BLOCK);


        // Wood blocks
        allLogMaterials.add(Material.OAK_WOOD);
        allLogMaterials.add(Material.BIRCH_WOOD);
        allLogMaterials.add(Material.SPRUCE_WOOD);
        allLogMaterials.add(Material.JUNGLE_WOOD);
        allLogMaterials.add(Material.ACACIA_WOOD);
        allLogMaterials.add(Material.DARK_OAK_WOOD);
        allLogMaterials.add(Material.MANGROVE_WOOD);
        allLogMaterials.add(Material.CHERRY_WOOD);

        // Stripped wood blocks
        allLogMaterials.add(Material.STRIPPED_OAK_WOOD);
        allLogMaterials.add(Material.STRIPPED_BIRCH_WOOD);
        allLogMaterials.add(Material.STRIPPED_SPRUCE_WOOD);
        allLogMaterials.add(Material.STRIPPED_JUNGLE_WOOD);
        allLogMaterials.add(Material.STRIPPED_ACACIA_WOOD);
        allLogMaterials.add(Material.STRIPPED_DARK_OAK_WOOD);
        allLogMaterials.add(Material.STRIPPED_MANGROVE_WOOD);
        allLogMaterials.add(Material.STRIPPED_CHERRY_WOOD);

        // Nether logs/stems
        allLogMaterials.add(Material.CRIMSON_STEM);
        allLogMaterials.add(Material.WARPED_STEM);
        allLogMaterials.add(Material.CRIMSON_HYPHAE);
        allLogMaterials.add(Material.STRIPPED_CRIMSON_STEM);
        allLogMaterials.add(Material.STRIPPED_WARPED_STEM);
        allLogMaterials.add(Material.STRIPPED_CRIMSON_HYPHAE);
        allLogMaterials.add(Material.STRIPPED_WARPED_HYPHAE);


        allCookedFoodMaterials.clear();
        allCookedFoodMaterials.add(Material.COOKED_BEEF);
        allCookedFoodMaterials.add(Material.COOKED_PORKCHOP);
        allCookedFoodMaterials.add(Material.COOKED_CHICKEN);
        allCookedFoodMaterials.add(Material.COOKED_SALMON);
        allCookedFoodMaterials.add(Material.COOKED_COD);
        allCookedFoodMaterials.add(Material.BAKED_POTATO);
        allCookedFoodMaterials.add(Material.RABBIT_STEW);
        allCookedFoodMaterials.add(Material.MUSHROOM_STEW);
        allCookedFoodMaterials.add(Material.BEETROOT_SOUP);
        allCookedFoodMaterials.add(Material.PUMPKIN_PIE);
        allCookedFoodMaterials.add(Material.DRIED_KELP);
        allCookedFoodMaterials.add(Material.COOKED_MUTTON);
        allCookedFoodMaterials.add(Material.COOKED_RABBIT);
        allCookedFoodMaterials.add(Material.BREAD); // Include bread in the list of ready meals
    }

    /**
     * Updates task progress by summing items from all online players' inventories.
     * This function is called regularly to maintain up-to-date progress.
     */
    public void updateItemTasks() {
        // Reset current progress (but not completion status)
        overworldTasks.keySet().forEach(mat -> overworldProgress.put(mat, 0));
        netherTasks.keySet().forEach(mat -> netherProgress.put(mat, 0));

        for (Player player : Bukkit.getOnlinePlayers()) {
            Arrays.stream(player.getInventory().getContents())
                    .filter(Objects::nonNull)
                    .forEach(item -> {
                        Material type = item.getType();
                        int amount = item.getAmount();

                        // Special treatment for the ‘Wood’ task: summarise all log types
                        if (allLogMaterials.contains(type) && overworldTasks.containsKey(Material.OAK_LOG)) {
                            overworldProgress.merge(Material.OAK_LOG, amount, Integer::sum);
                        }
                        // Special processing for the ‘Ready meal’ task: summarise all types of ready meals
                        else if (allCookedFoodMaterials.contains(type) && overworldTasks.containsKey(Material.BREAD)) {
                            overworldProgress.merge(Material.BREAD, amount, Integer::sum);
                        }
                        // For other tasks in the Upper World
                        else if (overworldTasks.containsKey(type)) {
                            overworldProgress.merge(type, amount, Integer::sum);
                        }

                        // For Lower World assignments
                        if (netherTasks.containsKey(type)) {
                            netherProgress.merge(type, amount, Integer::sum);
                        }
                    });
        }

        // Update task completion status based on current progress
        overworldTasks.forEach((mat, req) -> {
            int progress = overworldProgress.getOrDefault(mat, 0);
            if (progress >= req) {
                overworldTasksCompleted.put(mat, true);
            }
        });

        netherTasks.forEach((mat, req) -> {
            int progress = netherProgress.getOrDefault(mat, 0);
            if (progress >= req) {
                netherTasksCompleted.put(mat, true);
            }
        });
    }

    /**
     * Starts the plugin's main timer, which updates time, tasks, and scoreboards.
     */
    private void startTimers() {
        mainTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (isTimerRunning) {
                    totalSeconds++;
                    // The timer for the village continues to run if it has not yet been found
                    if (foundLocations.containsKey("Village") && foundLocations.get("Village") == null) {
                        villageTimeElapsed++;
                    }
                }

                updateItemTasks(); // Update the progress and completion status of tasks

                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateScoreboard(player);
                }

                // Delete the village record if the search time has expired
                if (foundLocations.containsKey("Village")
                        && foundLocations.get("Village") == null
                        && villageTimeElapsed >= VILLAGE_TIMEOUT_SECONDS) {
                    foundLocations.remove("Village");
                    Bukkit.broadcast(Component.text("Time's up to find the village!", NamedTextColor.RED));
                }
            }
        };
        mainTimerTask.runTaskTimer(this, 20L, 20L);
    }

    /**
     * Updates the scoreboard for the specified player, displaying time, found structures, and task progress.
     *
     * @param player The player for whom to update the scoreboard.
     */
    public void updateScoreboard(@NotNull Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = manager.getNewScoreboard();

        Objective objective = board.registerNewObjective("speedrun", Criteria.DUMMY,
                Component.text("SPEEDRUN", NamedTextColor.GOLD, TextDecoration.BOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        AtomicInteger score = new AtomicInteger(15);

        objective.getScore("§fTime: §e" + formatTime(totalSeconds)).setScore(score.getAndDecrement());
        objective.getScore("§r").setScore(score.getAndDecrement());

        objective.getScore("§bLocations:").setScore(score.getAndDecrement());
        for (Map.Entry<String, Location> entry : foundLocations.entrySet()) {
            String status = (entry.getValue() != null) ? "§a" + formatLocation(entry.getValue()) : "§c???";
            objective.getScore("§7" + entry.getKey() + ": " + status).setScore(score.getAndDecrement());
        }
        if (foundLocations.containsKey("Village") && foundLocations.get("Village") == null) {
            long remainingTime = VILLAGE_TIMEOUT_SECONDS - villageTimeElapsed;
            if (remainingTime > 0) {
                objective.getScore("§7(Village: " + formatTime((int) remainingTime) + " to delete)").setScore(score.getAndDecrement());
            }
        }

        objective.getScore("§r§r").setScore(score.getAndDecrement());

        // Check if all tasks of the Upper World have been completed
        boolean allOverworldTasksDone = overworldTasksCompleted.values().stream().allMatch(Boolean::booleanValue);

        if (!allOverworldTasksDone) {
            objective.getScore("§aOverworld:").setScore(score.getAndDecrement());
            overworldTasks.forEach((mat, req) -> {
                // Display the task only if it is not completed
                if (!overworldTasksCompleted.getOrDefault(mat, false)) {
                    int progress = overworldProgress.getOrDefault(mat, 0);
                    // Progress cannot exceed the required number to display
                    if (progress > req) progress = req;
                    String displayName = (mat == Material.BREAD) ? "Cooked Food" : getDisplayName(mat);
                    String text = String.format("§7- %s: §f%d§7/§f%d", displayName, progress, req);
                    objective.getScore(text).setScore(score.getAndDecrement());
                }
            });
            objective.getScore(" ").setScore(score.getAndDecrement()); // Add a space only if the Overworld block is displayed
        }

        // Check if all tasks of the Lower World have been completed
        boolean allNetherTasksDone = netherTasksCompleted.values().stream().allMatch(Boolean::booleanValue);

        if (!allNetherTasksDone) {
            objective.getScore("§cNether:").setScore(score.getAndDecrement());
            netherTasks.forEach((mat, req) -> {
                // Display the task only if it is not completed
                if (!netherTasksCompleted.getOrDefault(mat, false)) {
                    int progress = netherProgress.getOrDefault(mat, 0);
                    // Progress cannot exceed the required number to display
                    if (progress > req) progress = req;
                    String text = String.format("§7- %s: §f%d§7/§f%d", getDisplayName(mat), progress, req);
                    objective.getScore(text).setScore(score.getAndDecrement());
                }
            });
        }
        player.setScoreboard(board);
    }

    /**
     * Formats a material name into a more readable form (e.g., OAK_LOG -> Oak Log).
     *
     * @param mat The material to format.
     * @return The formatted name.
     */
    private String getDisplayName(@NotNull Material mat) {
        return Arrays.stream(mat.name().split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    /**
     * Registers a found structure.
     *
     * @param player The player who found the structure.
     * @param key    The structure key (e.g., "Fortress", "Nether P (Over)").
     * @param loc    The location where the structure was found.
     */
    public void structureFound(Player player, String key, Location loc) {
        if (foundLocations.containsKey(key) && foundLocations.get(key) != null) return;

        foundLocations.put(key, loc);
        Bukkit.broadcast(Component.text(player.getName(), NamedTextColor.AQUA)
                .append(Component.text(" find ", NamedTextColor.WHITE))
                .append(Component.text(key, NamedTextColor.GOLD))
                .append(Component.text(" at coordinates " + formatLocation(loc), NamedTextColor.WHITE)));

        if (key.equals("Village")) {
            villageTimeElapsed = 0; // Reset the timer to the village when found
        }
    }

    /**
     * Checks if the village search is currently active.
     *
     * @return True if the village is still being searched for and the timeout has not expired.
     */
    public boolean isVillageSearchActive() {
        return foundLocations.containsKey("Village") && foundLocations.get("Village") == null && villageTimeElapsed < VILLAGE_TIMEOUT_SECONDS;
    }

    /**
     * Called when the Ender Dragon is killed. Stops the speedrun timer.
     */
    public void dragonKilled() {
        if (isTimerRunning) {
            isTimerRunning = false;
            Bukkit.broadcast(Component.text("The Dragon of the Edge has been slain! The speedrun is complete!", NamedTextColor.GREEN, TextDecoration.BOLD));
            // You might want to save the final time or perform other actions here
        }
    }

    /**
     * Formats time from seconds into HH:MM:SS.
     *
     * @param seconds Total seconds.
     * @return Formatted time string.
     */
    private String formatTime(int seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    /**
     * Formats a location into an X, Y, Z string.
     *
     * @param loc The location.
     *
     * @return Formatted coordinate string.
     */
    private String formatLocation(Location loc) {
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}