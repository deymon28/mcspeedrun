package org.speedrun.speedrun.casualGameMode;

import org.jetbrains.annotations.Nullable;
import org.speedrun.speedrun.Speedrun;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.speedrun.speedrun.utils.MessageUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles the casual-mode navigation compass, including the destination GUI,
 * lodestone-bound compass creation, and action-bar direction fallback.
 */
public class CompassListener implements Listener {

    private final Speedrun plugin;
    private final Map<UUID, DestinationSelection> playerDestinations = new HashMap<>();
    private final Map<World, Map<String, Location>> predefinedDestinationsByWorld = new HashMap<>();
    private final Map<String, Material> customDestinationIcons = new HashMap<>();
    private final Map<UUID, DeathDestination> playerDeathDestinations = new HashMap<>();

    private final Set<UUID> playersInMenu = new HashSet<>();
    /** Marks plugin compasses independently of their (translated) display name. */
    private final NamespacedKey compassKey;
    /** Destination key stored on menu icons. */
    private final NamespacedKey destinationKey;

    private BukkitRunnable compassUpdateTask;

    public CompassListener(Speedrun plugin) {
        this.plugin = plugin;
        this.compassKey = new NamespacedKey(plugin, "navigation_compass");
        this.destinationKey = new NamespacedKey(plugin, "destination");
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
        customDestinationIcons.put("PLAYER_DEATH", Material.SKELETON_SKULL);
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
            meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
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
        plugin.getTraceLogger().trace("compass", "compass_item_created",
                "target", targetLocation,
                "lodestone", lodestoneLocation,
                "lodestone_tracked", lodestoneLocation != null);
        return compass;
    }


    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.getCasualGameModeManager().isCasualModeActive()) {
            Player player = event.getPlayer();
            ItemStack item = event.getItem();

            if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                    && event.getHand() == EquipmentSlot.HAND
                    && isNavigationCompass(item)) {
                event.setCancelled(true);
                openDestinationMenu(player);
            }
        }
    }

    private void openDestinationMenu(Player player) {
        World playerWorld = player.getWorld();
        refreshSpawnDestination(playerWorld);
        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(playerWorld);
        DeathDestination deathDestination = getDeathDestination(player);

        if ((destinationsForWorld == null || destinationsForWorld.isEmpty()) && deathDestination == null) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.no-destinations"));
            return;
        }
        plugin.getTraceLogger().trace("compass", "menu_opened",
                "player", player,
                "world", playerWorld,
                "destination_count", destinationsForWorld != null ? destinationsForWorld.size() : 0,
                "death_destination", deathDestination != null);

        int numDestinations = destinationsForWorld != null ? destinationsForWorld.size() : 0;
        if (deathDestination != null) {
            numDestinations++;
        }
        int rows = (int) Math.ceil(numDestinations / 9.0);
        if (rows == 0) rows = 1;
        int size = rows * 9;
        if (size > 54) size = 54;

        String guiTitle = plugin.getConfigManager().getFormattedText("compass.gui.title",
                "%world%", getWorldDisplayName(playerWorld));

        DestinationMenuHolder holder = new DestinationMenuHolder();
        Inventory menu = Bukkit.createInventory(holder, size, guiTitle);
        holder.inventory = menu;

        ItemStack filler = getFillerItem(playerWorld);
        for (int i = 0; i < size; i++) {
            menu.setItem(i, filler);
        }

        int slot = 0;
        List<Map.Entry<String, Location>> sortedDestinations = destinationsForWorld == null
                ? Collections.emptyList()
                : destinationsForWorld.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());

        for (Map.Entry<String, Location> entry : sortedDestinations) {
            if (slot >= size) break;
            if (deathDestination != null && slot == size - 1) {
                break;
            }
            String name = entry.getKey();
            Location loc = entry.getValue();

            ItemStack item = new ItemStack(getIconForDestinationName(name));
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String displayName = getDestinationDisplayName(name);
                if (plugin.getStructureManager().isApproximateStructure(name)) {
                    displayName = plugin.getConfigManager().getFormattedText("compass.gui.approximate-destination-name",
                            "%destination%", displayName);
                }
                meta.setDisplayName(plugin.getConfigManager().getFormattedText("compass.gui.destination-name",
                        "%destination%", displayName));
                List<String> lore = new ArrayList<>(Arrays.asList(
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-x", "%x%", String.valueOf(loc.getBlockX())),
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-y", "%y%", String.valueOf(loc.getBlockY())),
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-z", "%z%", String.valueOf(loc.getBlockZ())),
                        plugin.getConfigManager().getFormattedText("compass.gui.lore-world", "%world%", loc.getWorld().getName())));
                if (plugin.getStructureManager().isApproximateStructure(name)) {
                    lore.add(plugin.getConfigManager().getFormattedText("compass.gui.approximate-lore"));
                }
                lore.add("");
                lore.add(
                        plugin.getConfigManager().getFormattedText("compass.gui.click-hint")
                );
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(destinationKey, PersistentDataType.STRING, name);
                item.setItemMeta(meta);
            }
            menu.setItem(slot++, item);
        }
        if (deathDestination != null) {
            menu.setItem(size - 1, createDeathDestinationItem(deathDestination));
        }

        player.openInventory(menu);
        playersInMenu.add(player.getUniqueId());
    }

    private ItemStack createDeathDestinationItem(DeathDestination deathDestination) {
        Location loc = deathDestination.location();
        ItemStack item = new ItemStack(getIconForDestinationName("PLAYER_DEATH"));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getConfigManager().getFormattedText("compass.gui.death-location-name",
                    "%player%", deathDestination.playerName()));
            meta.setLore(Arrays.asList(
                    plugin.getConfigManager().getFormattedText("compass.gui.lore-x", "%x%", String.valueOf(loc.getBlockX())),
                    plugin.getConfigManager().getFormattedText("compass.gui.lore-y", "%y%", String.valueOf(loc.getBlockY())),
                    plugin.getConfigManager().getFormattedText("compass.gui.lore-z", "%z%", String.valueOf(loc.getBlockZ())),
                    plugin.getConfigManager().getFormattedText("compass.gui.lore-world", "%world%", loc.getWorld().getName()),
                    "",
                    plugin.getConfigManager().getFormattedText("compass.gui.death-click-hint")
            ));
            meta.getPersistentDataContainer().set(destinationKey, PersistentDataType.STRING, "PLAYER_DEATH");
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof DestinationMenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof DestinationMenuHolder)) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR || !clickedItem.hasItemMeta()) {
            return;
        }
        String destinationName = clickedItem.getItemMeta().getPersistentDataContainer()
                .get(destinationKey, PersistentDataType.STRING);
        if (destinationName == null) {
            return; // Filler pane.
        }

        World playerWorld = player.getWorld();
        refreshSpawnDestination(playerWorld);
        if ("PLAYER_DEATH".equals(destinationName)) {
            DeathDestination deathDestination = getDeathDestination(player);
            if (deathDestination != null) {
                setPlayerDestination(player, "PLAYER_DEATH", deathDestination.location());
                player.closeInventory();
            }
            return;
        }

        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(playerWorld);
        if (destinationsForWorld != null && destinationsForWorld.containsKey(destinationName)) {
            setPlayerDestination(player, destinationName, destinationsForWorld.get(destinationName));
            player.closeInventory();
        } else {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.destination-not-found"));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof DestinationMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof DestinationMenuHolder) {
            playersInMenu.remove(event.getPlayer().getUniqueId());
        }
    }

    /** Releases per-player state when a player leaves; the death point is kept for the run. */
    public void forgetPlayer(Player player) {
        playersInMenu.remove(player.getUniqueId());
    }

    /** True for compasses created by this plugin, including ones created before item tagging. */
    public boolean isNavigationCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE)) {
            return true;
        }
        return meta.hasDisplayName() && meta.getDisplayName().equals(getCompassDisplayName());
    }

    public void setPlayerDestination(Player player, Location targetLocation) {
        setPlayerDestination(player, resolveDestinationKey(targetLocation), targetLocation);
    }

    private void setPlayerDestination(Player player, String destinationKey, Location targetLocation) {
        if (targetLocation != null) {
            playerDestinations.put(player.getUniqueId(), new DestinationSelection(destinationKey, targetLocation));
            Location lodestoneLocation = findLodestoneForDestination(player, destinationKey, targetLocation);
            plugin.getTraceLogger().trace("compass", "target_selected",
                    "player", player,
                    "destination_key", destinationKey,
                    "target", targetLocation,
                    "lodestone", lodestoneLocation,
                    "requires_lodestone", shouldEnsureHiddenLodestone(destinationKey));

            if (lodestoneLocation == null && shouldEnsureHiddenLodestone(destinationKey)) {
                String lodestoneKey = hiddenLodestoneKeyFor(player, destinationKey);
                plugin.getStructureManager().ensureHiddenLodestoneAsync(lodestoneKey, targetLocation)
                        .thenAccept(loadedLodestone -> Bukkit.getScheduler().runTask(plugin, () -> {
                            DestinationSelection currentSelection = playerDestinations.get(player.getUniqueId());
                            if (!player.isOnline()
                                    || currentSelection == null
                                    || !Objects.equals(currentSelection.destinationKey(), destinationKey)
                                    || !sameBlockLocation(currentSelection.fallbackLocation(), targetLocation)) {
                                plugin.getTraceLogger().trace("compass", "async_lodestone_result_ignored",
                                        "player", player,
                                        "destination_key", destinationKey,
                                        "target", targetLocation,
                                        "lodestone", loadedLodestone);
                                return;
                            }
                            // The player may have dropped or stored the compass while the chunk
                            // loaded; only update a compass they still carry, never hand out a new one.
                            finishSetPlayerDestination(player, destinationKey, targetLocation, loadedLodestone, false);
                        }));
                return;
            }

            finishSetPlayerDestination(player, destinationKey, targetLocation, lodestoneLocation, true);
        } else {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.target-invalid"));
            plugin.getTraceLogger().trace("compass", "target_rejected",
                    "player", player,
                    "destination_key", destinationKey,
                    "reason", "null_target");
        }
    }

    private void finishSetPlayerDestination(Player player, String destinationKey, Location targetLocation,
                                            @Nullable Location lodestoneLocation, boolean mayAddCompass) {
        MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.target-set",
                "%destination%", destinationKey != null ? getDestinationDisplayName(destinationKey) : plugin.getConfigManager().getLangString("compass.gui.custom-location", "a custom location"),
                "%world%", targetLocation.getWorld().getName()));
        giveConfiguredCompass(player, targetLocation, lodestoneLocation, mayAddCompass);
        plugin.getTraceLogger().trace("compass", "target_applied",
                "player", player,
                "destination_key", destinationKey,
                "target", targetLocation,
                "lodestone", lodestoneLocation,
                "lodestone_tracked", lodestoneLocation != null);
    }

    /**
     * Updates the player's current vanilla compass target.
     * @param player The player whose compass target is to be updated.
     * @param targetLocation The location to set as the compass target.
     */
    public void updatePlayerNavigationCompass(Player player, Location targetLocation) {
        player.setCompassTarget(targetLocation);
    }

    public void recordPlayerDeathLocation(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) {
            return;
        }
        plugin.getStructureManager().clearHiddenLodestoneForKey(deathLodestoneKey(player));
        plugin.getStructureManager().ensureHiddenLodestone(deathLodestoneKey(player), location);
        playerDeathDestinations.put(player.getUniqueId(), new DeathDestination(player.getName(), location.clone()));
        plugin.getTraceLogger().trace("compass", "death_destination_recorded",
                "player", player,
                "location", location,
                "lodestone", plugin.getStructureManager().getHiddenLodestone(deathLodestoneKey(player)));
    }


    private void startCompassUpdateTask() {
        compassUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (playersInMenu.contains(player.getUniqueId())) {
                        continue;
                    }

                    boolean isHoldingCompass = isNavigationCompass(player.getInventory().getItemInMainHand())
                            || isNavigationCompass(player.getInventory().getItemInOffHand());

                    if (isHoldingCompass) {
                        DestinationSelection selection = playerDestinations.get(player.getUniqueId());
                        Location destination = resolveSelectedDestination(selection);
                        if (destination != null) {
                            if (player.getWorld().equals(destination.getWorld())) {
                                player.setCompassTarget(destination);
                            }

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

                                if (destinationName == null) {
                                    continue;
                                }

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
        return resolveSelectedDestination(playerDestinations.get(player.getUniqueId()));
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
        for (UUID playerId : new ArrayList<>(playerDeathDestinations.keySet())) {
            plugin.getStructureManager().clearHiddenLodestoneForKey("PLAYER_DEATH:" + playerId);
        }
        playerDeathDestinations.clear();
        initializePredefinedDestinations();

        plugin.getLogger().info("Compass data and task reset.");

        if (plugin.getCasualGameModeManager().isCasualModeActive()) {
            startCompassUpdateTask();
        }
    }

    private void giveConfiguredCompass(Player player, Location targetLocation, @Nullable Location lodestoneLocation,
                                       boolean mayAddCompass) {
        ItemStack configuredCompass = createNavigationCompass(targetLocation, lodestoneLocation);
        replaceOrAddCompass(player, configuredCompass, mayAddCompass);
        if (player.getWorld().equals(targetLocation.getWorld())) {
            player.setCompassTarget(targetLocation);
        }
    }

    private void replaceOrAddCompass(Player player, ItemStack compass, boolean mayAdd) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != Material.COMPASS) {
                continue;
            }

            if (isNavigationCompass(stack)) {
                player.getInventory().setItem(slot, compass);
                return;
            }
        }

        if (mayAdd) {
            player.getInventory().addItem(compass);
        }
    }

    private String getCompassDisplayName() {
        return plugin.getConfigManager().getFormattedText("items.navigation-compass.name");
    }

    private Location findLodestoneForDestination(Player player, String destinationKey, Location targetLocation) {
        if (targetLocation == null) {
            return null;
        }

        String resolvedDestinationKey = destinationKey;
        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(targetLocation.getWorld());
        if (resolvedDestinationKey == null && destinationsForWorld != null) {
            resolvedDestinationKey = getKeyByValue(destinationsForWorld, targetLocation);
        }

        if (resolvedDestinationKey == null) {
            return null;
        }
        if (shouldEnsureHiddenLodestone(resolvedDestinationKey)) {
            return plugin.getStructureManager().ensureHiddenLodestone(hiddenLodestoneKeyFor(player, resolvedDestinationKey), targetLocation);
        }
        return plugin.getStructureManager().getHiddenLodestone(resolvedDestinationKey, targetLocation);
    }

    private boolean shouldEnsureHiddenLodestone(String destinationKey) {
        return destinationKey != null
                && (destinationKey.equals("SPAWN")
                || destinationKey.equals("PLAYER_DEATH")
                || destinationKey.equals("LAVA_POOL")
                || destinationKey.equals("VILLAGE")
                || destinationKey.equals("NETHER_PORTAL")
                || destinationKey.equals("FORTRESS")
                || destinationKey.equals("BASTION")
                || destinationKey.equals("END_PORTAL"));
    }

    private String hiddenLodestoneKeyFor(Player player, String destinationKey) {
        if ("PLAYER_DEATH".equals(destinationKey)) {
            return deathLodestoneKey(player);
        }
        return destinationKey;
    }

    private boolean sameBlockLocation(Location first, Location second) {
        return first != null
                && second != null
                && first.getWorld() != null
                && first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
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
        if ("PLAYER_DEATH".equals(key)) {
            return plugin.getConfigManager().getLangString("structures.PLAYER_DEATH", "Last Death");
        }
        return plugin.getConfigManager().getLangString("structures." + key, key);
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

    private DeathDestination getDeathDestination(Player player) {
        if (!plugin.getConfigManager().isDeathLocationCompassEnabled()) {
            return null;
        }
        return playerDeathDestinations.get(player.getUniqueId());
    }

    private String deathLodestoneKey(Player player) {
        return "PLAYER_DEATH:" + player.getUniqueId();
    }

    private record DestinationSelection(String destinationKey, Location fallbackLocation) {
    }

    /** Identifies the destination menu without relying on its translated title. */
    private static final class DestinationMenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record DeathDestination(String playerName, Location location) {
    }
}
