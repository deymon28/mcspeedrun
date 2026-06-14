package org.speedrun.speedrun.casualGameMode;

import org.jetbrains.annotations.Nullable;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.Task;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.speedrun.speedrun.managers.TaskManager;
import org.speedrun.speedrun.utils.MessageUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Handles the casual-mode navigation compass, including the destination GUI,
 * lodestone-bound compass creation, and action-bar direction fallback.
 */
public class CompassListener implements Listener {

    private final Speedrun plugin;
    private final Map<Player, DestinationSelection> playerDestinations = new HashMap<>();
    private final Map<World, Map<String, Location>> predefinedDestinationsByWorld = new HashMap<>();
    private final Map<String, Material> customDestinationIcons = new HashMap<>();

    private final List<Player> playersInMenu = new ArrayList<>();
    private static final String GUI_TITLE_PREFIX = ChatColor.DARK_BLUE + "Destinations - ";

    private BukkitRunnable compassUpdateTask;

    public CompassListener(Speedrun plugin) {
        this.plugin = plugin;
        initializePredefinedDestinations();
        initializeCustomDestinationIcons();
        startCompassUpdateTask();
    }

    /**
     * Seeds the menu with safe default destinations so the compass has content
     * before structures are discovered during a run.
     */
    private void initializePredefinedDestinations() {
        Map<String, Location> overworldDestinations = new HashMap<>();
        World overworld = findWorldByEnvironment(World.Environment.NORMAL);
        if (overworld != null) {
            overworldDestinations.put("SPAWN", resolveWorldSpawn(overworld));
            predefinedDestinationsByWorld.put(overworld, overworldDestinations);
        }

        World nether = Bukkit.getWorld("world_nether");
        if (nether == null) {
            nether = findWorldByEnvironment(World.Environment.NETHER);
        }
        if (nether != null) {
            Map<String, Location> netherDestinations = new HashMap<>();
            if (plugin.getConfigManager().isNetherReferenceDestinationEnabled()) {
                netherDestinations.put("NETHER_REFERENCE", new Location(nether, 100, 70, 50));
            }
            predefinedDestinationsByWorld.put(nether, netherDestinations);
        }
    }

    private void initializeCustomDestinationIcons() {
        customDestinationIcons.put("SPAWN", Material.RED_BED);
        customDestinationIcons.put("NETHER_REFERENCE", Material.FIRE_CHARGE);
        customDestinationIcons.put("LAVA_POOL", Material.LAVA_BUCKET);
        customDestinationIcons.put("FORTRESS", Material.NETHER_BRICKS);
        customDestinationIcons.put("BASTION", Material.GILDED_BLACKSTONE);
        customDestinationIcons.put("VILLAGE", Material.BELL);
        customDestinationIcons.put("NETHER_PORTAL", Material.OBSIDIAN);
        customDestinationIcons.put("END_PORTAL", Material.END_PORTAL_FRAME);
    }

    private Material getIconForDestinationName(String destinationName) {
        if (customDestinationIcons.containsKey(destinationName)) {
            return customDestinationIcons.get(destinationName);
        }
        return Material.PAPER;
    }

    private ItemStack getFillerItem(World world) {
        Material fillerMaterial = Material.BLACK_STAINED_GLASS_PANE;
        String name = ChatColor.RESET + "";
        if (world.getEnvironment() == World.Environment.NETHER) {
            fillerMaterial = Material.RED_STAINED_GLASS_PANE;
            name = plugin.getConfigManager().getFormattedText("compass.gui.nether");
        } else {
            fillerMaterial = Material.LIME_STAINED_GLASS_PANE;
            name = plugin.getConfigManager().getFormattedText("compass.gui.overworld");
        }
        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            filler.setItemMeta(meta);
        }
        return filler;
    }

    /**
     * Creates the navigation compass item and binds it to the hidden lodestone
     * when one exists for the selected destination.
     *
     * @param targetLocation world coordinate used by Bukkit's fallback compass target
     * @return configured compass item
     */
    public ItemStack createNavigationCompass(Location targetLocation) {
        return createNavigationCompass(targetLocation, null);
    }

    /**
     * Creates a compass with native lodestone metadata for stable tracking in
     * Nether and custom dimensions.
     *
     * @param targetLocation target shown in fallback systems
     * @param lodestoneLocation hidden lodestone location, or null to use targetLocation
     * @return configured compass item
     */
    public ItemStack createNavigationCompass(Location targetLocation, @Nullable Location lodestoneLocation) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getCompassDisplayName());
            meta.setLore(plugin.getConfigManager().getFormattedTextList("items.navigation-compass.lore"));
            if (meta instanceof CompassMeta compassMeta) {
                if (lodestoneLocation != null) {
                    compassMeta.setLodestone(lodestoneLocation);
                    compassMeta.setLodestoneTracked(true);
                } else {
                    compassMeta.setLodestone(null);
                    compassMeta.setLodestoneTracked(false);
                }
                compass.setItemMeta(compassMeta);
            } else {
                compass.setItemMeta(meta);
            }
        }
        return compass;
    }


    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.getCasualGameModeManager().isCasualModeActive()) {
            Player player = event.getPlayer();
            ItemStack item = event.getItem();

            if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                    (event.getHand() == EquipmentSlot.HAND || (Bukkit.getVersion().contains("1.8") && event.getHand() == null))) {

                if (item != null && item.getType() == Material.COMPASS) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && meta.hasDisplayName() &&
                            meta.getDisplayName().equals(getCompassDisplayName())) {

                        event.setCancelled(true);
                        openCompassMainMenu(player);
                    }
                }
            }
        }
    }

    private void openCompassMainMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, getMainMenuTitle());
        ItemStack filler = createMenuItem(Material.BLACK_STAINED_GLASS_PANE, ChatColor.RESET.toString(), Collections.emptyList());
        for (int i = 0; i < menu.getSize(); i++) {
            menu.setItem(i, filler);
        }

        menu.setItem(11, createMenuItem(
                Material.COMPASS,
                plugin.getConfigManager().getFormattedText("compass.gui.locations-menu"),
                plugin.getConfigManager().getFormattedTextList("compass.gui.locations-lore")));
        menu.setItem(13, createMenuItem(
                Material.WRITABLE_BOOK,
                plugin.getConfigManager().getFormattedText("compass.gui.team-roadmap"),
                plugin.getConfigManager().getFormattedTextList("compass.gui.team-roadmap-lore")));
        menu.setItem(15, createMenuItem(
                Material.PLAYER_HEAD,
                plugin.getConfigManager().getFormattedText("compass.gui.personal-roadmap"),
                plugin.getConfigManager().getFormattedTextList("compass.gui.personal-roadmap-lore")));

        player.openInventory(menu);
        trackMenuOpen(player);
    }

    private void openDestinationMenu(Player player) {
        World playerWorld = player.getWorld();
        refreshSpawnDestination(playerWorld);
        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(playerWorld);

        if (destinationsForWorld == null || destinationsForWorld.isEmpty()) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.no-destinations"));
            return;
        }

        int numDestinations = destinationsForWorld.size();
        int rows = (int) Math.ceil(numDestinations / 9.0);
        if (rows == 0) rows = 1;
        int size = rows * 9;
        if (size > 54) size = 54;

        String guiTitle = GUI_TITLE_PREFIX + getWorldDisplayName(playerWorld);

        Inventory menu = Bukkit.createInventory(null, size, guiTitle);

        ItemStack filler = getFillerItem(playerWorld);
        for (int i = 0; i < size; i++) {
            menu.setItem(i, filler);
        }

        int slot = 0;
        List<Map.Entry<String, Location>> sortedDestinations = destinationsForWorld.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());

        for (Map.Entry<String, Location> entry : sortedDestinations) {
            if (slot >= size) break;
            String name = entry.getKey();
            Location loc = entry.getValue();

            ItemStack item = new ItemStack(getIconForDestinationName(name));
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(plugin.getConfigManager().getFormattedText("compass.gui.destination-name",
                        "%destination%", getDestinationDisplayName(name)));
                meta.setLore(Arrays.asList(
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-x", "%x%", String.valueOf(loc.getBlockX())),
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-y", "%y%", String.valueOf(loc.getBlockY())),
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-z", "%z%", String.valueOf(loc.getBlockZ())),
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-world", "%world%", loc.getWorld().getName()),
                        "",
                        plugin.getConfigManager().getFormattedText("compass.gui.click-hint")
                ));
                item.setItemMeta(meta);
            }
            menu.setItem(slot++, item);
        }

        player.openInventory(menu);
        trackMenuOpen(player);
    }

    private void openTeamRoadmapMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 54, getTeamRoadmapTitle());
        populateRoadmapMenu(menu, player, false);
        player.openInventory(menu);
        trackMenuOpen(player);
    }

    private void openPersonalRoadmapMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 54, getPersonalRoadmapTitle());
        populateRoadmapMenu(menu, player, true);
        player.openInventory(menu);
        trackMenuOpen(player);
    }

    private void populateRoadmapMenu(Inventory menu, Player player, boolean personal) {
        ItemStack filler = createMenuItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.RESET.toString(), Collections.emptyList());
        for (int i = 0; i < menu.getSize(); i++) {
            menu.setItem(i, filler);
        }

        int row = 0;
        for (TaskManager.StageView stage : plugin.getTaskManager().getStageViews()) {
            int rowStart = row * 9;
            if (rowStart >= menu.getSize()) {
                break;
            }

            menu.setItem(rowStart, createStageItem(stage));
            if (stage.tasks().isEmpty()) {
                menu.setItem(rowStart + 1, createMenuItem(
                        Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        plugin.getConfigManager().getFormattedText("compass.gui.empty-stage"),
                        Collections.emptyList()));
            } else {
                int slot = rowStart + 1;
                for (Task task : stage.tasks()) {
                    if (slot >= rowStart + 9 || slot >= menu.getSize()) {
                        break;
                    }
                    menu.setItem(slot++, createTaskItem(task, player, personal));
                }
            }
            row++;
        }
    }

    private ItemStack createStageItem(TaskManager.StageView stage) {
        Material material = switch (stage.world()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
        String status = stage.complete()
                ? plugin.getConfigManager().getFormattedText("compass.gui.stage-complete")
                : stage.active()
                ? plugin.getConfigManager().getFormattedText("compass.gui.stage-active")
                : plugin.getConfigManager().getFormattedText("compass.gui.stage-pending");

        return createMenuItem(material,
                plugin.getConfigManager().getFormattedText("compass.gui.stage-header",
                        "%stage%", stage.displayName()),
                Arrays.asList(
                        plugin.getConfigManager().getFormattedText("compass.gui.task-world",
                                "%world%", getEnvironmentDisplayName(stage.world())),
                        status));
    }

    private ItemStack createTaskItem(Task task, Player player, boolean personal) {
        int progress = personal ? plugin.getTaskManager().getPersonalProgress(task, player) : task.getProgress();
        int cappedProgress = Math.min(progress, task.getRequiredAmount());
        List<String> lore = new ArrayList<>();
        lore.add(plugin.getConfigManager().getFormattedText("compass.gui.task-world",
                "%world%", getEnvironmentDisplayName(task.getWorld())));
        lore.add(plugin.getConfigManager().getFormattedText("compass.gui.task-progress",
                "%progress%", String.valueOf(cappedProgress),
                "%required%", String.valueOf(task.getRequiredAmount())));
        if (personal && task.getTaskType() == Task.Type.ITEM) {
            lore.add(plugin.getConfigManager().getFormattedText("compass.gui.task-personal-progress"));
        }
        lore.add(task.isCompleted()
                ? plugin.getConfigManager().getFormattedText("compass.gui.task-completed")
                : plugin.getConfigManager().getFormattedText("compass.gui.task-open"));

        return createMenuItem(getRoadmapTaskMaterial(task, progress), getTaskDisplayName(task), lore);
    }

    private Material getRoadmapTaskMaterial(Task task, int progress) {
        if (task.isCompleted()) {
            return Material.LIME_STAINED_GLASS_PANE;
        }
        if (progress > 0) {
            return Material.YELLOW_STAINED_GLASS_PANE;
        }
        if (task.getTaskType() == Task.Type.STRUCTURE) {
            return Material.ENDER_EYE;
        }
        return Material.PAPER;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (title.equals(getMainMenuTitle())) {
            event.setCancelled(true);
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }
            switch (event.getRawSlot()) {
                case 11 -> openDestinationMenu(player);
                case 13 -> openTeamRoadmapMenu(player);
                case 15 -> openPersonalRoadmapMenu(player);
                default -> {
                }
            }
            return;
        }

        if (title.equals(getTeamRoadmapTitle()) || title.equals(getPersonalRoadmapTitle())) {
            event.setCancelled(true);
            return;
        }

        if (title.startsWith(GUI_TITLE_PREFIX)) {
            event.setCancelled(true);

            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) {
                return;
            }

            ItemStack clickedItem = event.getCurrentItem();
            ItemStack filler = getFillerItem(player.getWorld());
            if (clickedItem.getType() == filler.getType() &&
                    clickedItem.hasItemMeta() && filler.hasItemMeta() &&
                    Objects.equals(clickedItem.getItemMeta().getDisplayName(), filler.getItemMeta().getDisplayName())) {
                return;
            }

            ItemMeta clickedItemMeta = clickedItem.getItemMeta();
            if (clickedItemMeta == null || !clickedItemMeta.hasDisplayName()) {
                return;
            }

            String destinationName = resolveDestinationKey(player.getWorld(), ChatColor.stripColor(clickedItemMeta.getDisplayName()));

            World playerWorld = player.getWorld();
            refreshSpawnDestination(playerWorld);
            Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(playerWorld);

            if (destinationsForWorld != null && destinationsForWorld.containsKey(destinationName)) {
                setPlayerDestination(player, destinationName, destinationsForWorld.get(destinationName));
                player.closeInventory();
            } else {
                MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.destination-not-found"));
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();
        if (title.startsWith(GUI_TITLE_PREFIX)
                || title.equals(getMainMenuTitle())
                || title.equals(getTeamRoadmapTitle())
                || title.equals(getPersonalRoadmapTitle())) {
            playersInMenu.remove(player);
        }
    }

    public void setPlayerDestination(Player player, Location targetLocation) {
        setPlayerDestination(player, resolveDestinationKey(targetLocation), targetLocation);
    }

    private void setPlayerDestination(Player player, String destinationKey, Location targetLocation) {
        if (targetLocation != null) {
            playerDestinations.put(player, new DestinationSelection(destinationKey, targetLocation));
            Location lodestoneLocation = findLodestoneForDestination(targetLocation);

            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.target-set",
                    "%destination%", destinationKey != null ? getDestinationDisplayName(destinationKey) : plugin.getConfigManager().getLangString("compass.gui.custom-location", "a custom location"),
                    "%world%", targetLocation.getWorld().getName()));
            giveConfiguredCompass(player, targetLocation, lodestoneLocation);
        } else {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.target-invalid"));
        }
    }

    /**
     * Updates the player's current vanilla compass target.
     * @param player The player whose compass target is to be updated.
     * @param targetLocation The location to set as the compass target.
     */
    public void updatePlayerNavigationCompass(Player player, Location targetLocation) {
        player.setCompassTarget(targetLocation);
    }


    private void startCompassUpdateTask() {
        compassUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (playersInMenu.contains(player)) {
                        continue;
                    }

                    ItemStack mainHandItem = player.getInventory().getItemInMainHand();
                    ItemStack offHandItem = player.getInventory().getItemInOffHand();

                    boolean isHoldingCompass = false;
                    if (mainHandItem != null && mainHandItem.getType() == Material.COMPASS) {
                        ItemMeta meta = mainHandItem.getItemMeta();
                        if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(getCompassDisplayName())) {
                            isHoldingCompass = true;
                        }
                    }
                    if (!isHoldingCompass && offHandItem != null && offHandItem.getType() == Material.COMPASS) {
                        ItemMeta meta = offHandItem.getItemMeta();
                        if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(getCompassDisplayName())) {
                            isHoldingCompass = true;
                        }
                    }

                    if (isHoldingCompass) {
                        DestinationSelection selection = playerDestinations.get(player);
                        Location destination = resolveSelectedDestination(selection);
                        if (destination != null) {
                            player.setCompassTarget(destination);

                            String destinationName = selection.destinationKey() != null
                                    ? selection.destinationKey()
                                    : getKeyByValue(predefinedDestinationsByWorld.getOrDefault(destination.getWorld(), Collections.emptyMap()), destination);
                            String targetWorldDisplayName = getWorldDisplayName(destination.getWorld());

                            if (!player.getWorld().equals(destination.getWorld())) {
                                MessageUtil.actionBar(player, plugin.getConfigManager().getFormatted("compass.other-world-actionbar",
                                        "%destination%", destinationName != null ? getDestinationDisplayName(destinationName) : plugin.getConfigManager().getLangString("compass.gui.generic-location", "location"),
                                        "%world%", targetWorldDisplayName));
                            } else if (player.getWorld().getEnvironment() != World.Environment.NORMAL) {
                                String label = destinationName != null ? getDestinationDisplayName(destinationName) : plugin.getConfigManager().getLangString("compass.gui.generic-location", "Location");
                                MessageUtil.actionBar(player, formatDistanceHud(player, destination, label));
                            }
                            else {
                                String distanceString = String.format("%.1f", player.getLocation().distance(destination)) + "m";

                                if (destinationName == null) { return; }

                                MessageUtil.actionBar(player, plugin.getConfigManager().getFormatted("compass.target-actionbar",
                                        "%destination%", getDestinationDisplayName(Objects.requireNonNull(destinationName)),
                                        "%distance%", distanceString));
                            }
                        } else {
                            MessageUtil.actionBar(player, plugin.getConfigManager().getFormatted("compass.no-target-actionbar"));
                        }
                    }
                }
            }
        };
        compassUpdateTask.runTaskTimer(plugin, 10L, 10L);
    }

    public void stopCompassUpdateTask() {
        if (compassUpdateTask != null) {
            compassUpdateTask.cancel();
            compassUpdateTask = null;
            plugin.getLogger().info("Compass update task cancelled.");
        }
    }

    private String formatDistanceHud(Player player, Location destination, String label) {
        String distance = String.format("%.1fm", player.getLocation().distance(destination));

        return plugin.getConfigManager().getFormattedText("compass.target-actionbar",
                "%destination%", label,
                "%distance%", distance);
    }

    /**
     * Adds a dynamically discovered structure or location to the compass's available destinations.
     * This makes it appear in the compass menu.
     *
     * @param player The player who discovered the location (for feedback messages).
     * @param name The name of the discovered location (e.g., "Pillager Outpost").
     * @param location The actual coordinates of the discovered location.
     */
    public void addDynamicDestination(@Nullable Player player, String name, Location location) {
        World world = location.getWorld();
        if (world == null) {
            if (player != null) {
                MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.null-world"));
            }
            plugin.getLogger().warning("Skipped dynamic destination '" + name + "' because the world was null.");
            return;
        }

        predefinedDestinationsByWorld.computeIfAbsent(world, k -> new HashMap<>()).put(name, location);

        if (player != null) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.discovered",
                    "%destination%", getDestinationDisplayName(name)));
        }
    }

    /**
     * Returns the current destination set for a specific player.
     * This is useful for other classes (like GameListener) to retrieve a player's last known target.
     * @param player The player whose destination to retrieve.
     * @return The Location of the player's current destination, or null if none is set.
     */
    public Location getPlayerDestination(Player player) {
        return resolveSelectedDestination(playerDestinations.get(player));
    }

    private <T, E> T getKeyByValue(Map<T, E> map, E value) {
        for (Map.Entry<T, E> entry : map.entrySet()) {
            if (Objects.equals(value, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void reset() {
        stopCompassUpdateTask();
        playerDestinations.clear();
        predefinedDestinationsByWorld.clear();
        initializePredefinedDestinations();

        plugin.getLogger().info("Compass data and task reset.");

        if (plugin.getCasualGameModeManager().isCasualModeActive()) {
            startCompassUpdateTask();
        }
    }

    private void giveConfiguredCompass(Player player, Location targetLocation, @Nullable Location lodestoneLocation) {
        ItemStack configuredCompass = createNavigationCompass(targetLocation, lodestoneLocation);
        replaceOrAddCompass(player, configuredCompass);
        player.setCompassTarget(targetLocation);
    }

    private void replaceOrAddCompass(Player player, ItemStack compass) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != Material.COMPASS) {
                continue;
            }

            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(getCompassDisplayName())) {
                player.getInventory().setItem(slot, compass);
                return;
            }
        }

        player.getInventory().addItem(compass);
    }

    private String getCompassDisplayName() {
        return plugin.getConfigManager().getFormattedText("items.navigation-compass.name");
    }

    private void trackMenuOpen(Player player) {
        if (!playersInMenu.contains(player)) {
            playersInMenu.add(player);
        }
    }

    private ItemStack createMenuItem(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getTaskDisplayName(Task task) {
        return plugin.getConfigManager().getLangString("tasks." + task.getKey(), task.getKey());
    }

    private String getMainMenuTitle() {
        return plugin.getConfigManager().getFormattedText("compass.gui.main-title");
    }

    private String getTeamRoadmapTitle() {
        return plugin.getConfigManager().getFormattedText("compass.gui.roadmap-title");
    }

    private String getPersonalRoadmapTitle() {
        return plugin.getConfigManager().getFormattedText("compass.gui.personal-title");
    }

    private String getEnvironmentDisplayName(World.Environment environment) {
        return switch (environment) {
            case NETHER -> plugin.getConfigManager().getFormattedText("scoreboard.nether-tasks-header");
            case THE_END -> plugin.getConfigManager().getFormattedText("scoreboard.end-tasks-header");
            default -> plugin.getConfigManager().getFormattedText("scoreboard.normal-tasks-header");
        };
    }

    private Location findLodestoneForDestination(Location targetLocation) {
        if (targetLocation == null) {
            return null;
        }

        String destinationKey = null;
        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(targetLocation.getWorld());
        if (destinationsForWorld != null) {
            destinationKey = getKeyByValue(destinationsForWorld, targetLocation);
        }

        if (destinationKey == null) {
            return null;
        }
        if ("SPAWN".equals(destinationKey)) {
            return plugin.getStructureManager().ensureHiddenLodestone(destinationKey, targetLocation);
        }
        return plugin.getStructureManager().getHiddenLodestone(destinationKey);
    }

    private String resolveDestinationKey(Location targetLocation) {
        if (targetLocation == null || targetLocation.getWorld() == null) {
            return null;
        }

        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(targetLocation.getWorld());
        return destinationsForWorld != null ? getKeyByValue(destinationsForWorld, targetLocation) : null;
    }

    private Location resolveSelectedDestination(@Nullable DestinationSelection selection) {
        if (selection == null) {
            return null;
        }
        if (selection.destinationKey() == null) {
            return selection.fallbackLocation();
        }
        World world = selection.fallbackLocation().getWorld();
        if (world == null) {
            return selection.fallbackLocation();
        }
        refreshSpawnDestination(world);
        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(world);
        if (destinationsForWorld == null) {
            return selection.fallbackLocation();
        }
        return destinationsForWorld.getOrDefault(selection.destinationKey(), selection.fallbackLocation());
    }

    private String getDestinationDisplayName(String key) {
        return plugin.getConfigManager().getLangString("structures." + key, key);
    }

    private String resolveDestinationKey(World world, String displayName) {
        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(world);
        if (destinationsForWorld == null) {
            return displayName;
        }

        for (String key : destinationsForWorld.keySet()) {
            if (getDestinationDisplayName(key).equals(displayName)) {
                return key;
            }
        }
        return displayName;
    }

    private String getWorldDisplayName(World world) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            return plugin.getConfigManager().getFormattedText("compass.gui.nether");
        }
        if (world.getEnvironment() == World.Environment.NORMAL) {
            return plugin.getConfigManager().getFormattedText("compass.gui.overworld");
        }
        return world.getName();
    }

    private World findWorldByEnvironment(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return world;
            }
        }
        return null;
    }

    private void refreshSpawnDestination(World world) {
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        predefinedDestinationsByWorld
                .computeIfAbsent(world, ignored -> new HashMap<>())
                .put("SPAWN", resolveWorldSpawn(world));
    }

    private Location resolveWorldSpawn(World world) {
        Location spawn = world.getSpawnLocation();
        return new Location(world, spawn.getX(), spawn.getY(), spawn.getZ(), 0.0f, 0.0f);
    }

    private record DestinationSelection(String destinationKey, Location fallbackLocation) {
    }
}
