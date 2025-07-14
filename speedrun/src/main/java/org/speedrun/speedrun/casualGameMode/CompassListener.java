package org.speedrun.speedrun.casualGameMode; // Changed package to match your provided code

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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class CompassListener implements Listener {

    private final Speedrun plugin;
    private final Map<Player, Location> playerDestinations = new HashMap<>();
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

    private void initializePredefinedDestinations() {
        // --- OVERWORLD DESTINATIONS ---
        Map<String, Location> overworldDestinations = new HashMap<>();
        World overworld = Bukkit.getWorlds().get(0); // Assumes the first world is always the overworld
        if (overworld != null) {
            overworldDestinations.put("Spawn ?", new Location(overworld, 0, 70, 0));
            predefinedDestinationsByWorld.put(overworld, overworldDestinations);
        }

        // --- NETHER DESTINATIONS ---
        World nether = Bukkit.getWorld("world_nether"); // Or whatever your nether world name is
        if (nether == null) {
            // Try to find it if not named "world_nether"
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() == World.Environment.NETHER) {
                    nether = w;
                    break;
                }
            }
        }
        if (nether != null) {
            Map<String, Location> netherDestinations = new HashMap<>();
            netherDestinations.put("Equator ?", new Location(nether, 100, 70, 50));
            predefinedDestinationsByWorld.put(nether, netherDestinations);
        }

        // --- END DESTINATIONS (REMOVED) ---
        // World end = Bukkit.getWorld("world_the_end");
        // ... (removed all End-related logic)
    }

    private void initializeCustomDestinationIcons() {
        // Overworld specific icons
        customDestinationIcons.put("Spawn ?", Material.RED_BED);

        // Nether specific icons
        customDestinationIcons.put("Equator ?", Material.FIRE_CHARGE);

        // new destinations
        customDestinationIcons.put("LAVA_POOL", Material.LAVA_BUCKET);

        // --- Existing Nether specific icons ---
        customDestinationIcons.put("FORTRESS", Material.NETHER_BRICKS);
        customDestinationIcons.put("BASTION", Material.GILDED_BLACKSTONE);

        // --- ADD THESE NEW LINES HERE ---
        // These are icons for structures dynamically found by players or the server.
        // Make sure the key (e.g., "VILLAGE") exactly matches the 'structureKey'
        // string passed in your StructureFoundEvent calls.
        customDestinationIcons.put("VILLAGE", Material.BELL); // Icon for a discovered village
        customDestinationIcons.put("NETHER_PORTAL", Material.OBSIDIAN);

        customDestinationIcons.put("END_PORTAL", Material.END_PORTAL_FRAME);
    }

    private Material getIconForDestinationName(String destinationName) {
        if (customDestinationIcons.containsKey(destinationName)) {
            return customDestinationIcons.get(destinationName);
        }
        return Material.PAPER; // Default fallback
    }

    private ItemStack getFillerItem(World world) {
        Material fillerMaterial = Material.BLACK_STAINED_GLASS_PANE;
        String name = ChatColor.RESET + "";
        if (world.getEnvironment() == World.Environment.NETHER) {
            fillerMaterial = Material.RED_STAINED_GLASS_PANE;
            name = ChatColor.RED + "The Nether";
        } else { // Default to Overworld for any other world (including non-Overworld/Nether worlds if they exist)
            fillerMaterial = Material.LIME_STAINED_GLASS_PANE;
            name = ChatColor.GREEN + "The Overworld";
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
     * Creates a Navigation Compass ItemStack.
     * With no NBT API, this is a standard compass and will spin in Nether/End.
     *
     * @param targetLocation The location the compass should point to. (Primarily for initial player.setCompassTarget).
     * @return The Navigation Compass ItemStack.
     */
    public ItemStack createNavigationCompass(Location targetLocation) { // Made public for external use
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Navigation Compass");
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Right-click to open destinations menu."));
            compass.setItemMeta(meta);
        }
        // No NBT API calls here, so no specific Lodestone NBT is added.
        // The compass will rely solely on player.setCompassTarget() for its behavior.
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
                            meta.getDisplayName().equals(ChatColor.GOLD + "Navigation Compass")) {

                        event.setCancelled(true);
                        openDestinationMenu(player);
                    }
                }
            }
        }
    }

    private void openDestinationMenu(Player player) {
        World playerWorld = player.getWorld();
        Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(playerWorld);

        if (destinationsForWorld == null || destinationsForWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No destinations set for this dimension yet!");
            return;
        }

        int numDestinations = destinationsForWorld.size();
        int rows = (int) Math.ceil(numDestinations / 9.0);
        if (rows == 0) rows = 1;
        int size = rows * 9;
        if (size > 54) size = 54;

        String guiTitle;
        if (playerWorld.getEnvironment() == World.Environment.NETHER) {
            guiTitle = GUI_TITLE_PREFIX + "The Nether";
        } else { // Any other environment will be treated as Overworld for GUI display
            guiTitle = GUI_TITLE_PREFIX + "The Overworld";
        }

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
                meta.setDisplayName(ChatColor.GREEN + name);
                meta.setLore(Arrays.asList(
                        ChatColor.GRAY + "X: " + loc.getBlockX(),
                        ChatColor.GRAY + "Y: " + loc.getBlockY(),
                        ChatColor.GRAY + "Z: " + loc.getBlockZ(),
                        ChatColor.GRAY + "World: " + loc.getWorld().getName(),
                        "",
                        ChatColor.YELLOW + "Click to set compass target"
                ));
                item.setItemMeta(meta);
            }
            menu.setItem(slot++, item);
        }

        player.openInventory(menu);
        playersInMenu.add(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        if (event.getView().getTitle().startsWith(GUI_TITLE_PREFIX)) {
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

            String destinationName = ChatColor.stripColor(clickedItemMeta.getDisplayName());

            World playerWorld = player.getWorld();
            Map<String, Location> destinationsForWorld = predefinedDestinationsByWorld.get(playerWorld);

            if (destinationsForWorld != null && destinationsForWorld.containsKey(destinationName)) {
                setPlayerDestination(player, destinationsForWorld.get(destinationName));
                player.closeInventory();
            } else {
                player.sendMessage(ChatColor.RED + "Error: Destination not found or not available in this dimension.");
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        if (event.getView().getTitle().startsWith(GUI_TITLE_PREFIX)) {
            playersInMenu.remove(player);
        }
    }

    public void setPlayerDestination(Player player, Location targetLocation) {
        if (targetLocation != null) {
            playerDestinations.put(player, targetLocation);
            String destinationName = null;
            if (predefinedDestinationsByWorld.containsKey(targetLocation.getWorld())) {
                destinationName = getKeyByValue(predefinedDestinationsByWorld.get(targetLocation.getWorld()), targetLocation);
            }

            player.sendMessage(ChatColor.GREEN + "Compass now pointing to " + (destinationName != null ? destinationName : "a custom location") + " in " + targetLocation.getWorld().getName() + "!");

            // This is the only method influencing the compass target without NBT API
            player.setCompassTarget(targetLocation);
        } else {
            player.sendMessage(ChatColor.RED + "Could not set compass target. Invalid location.");
        }
    }

    /**
     * Updates the player's current compass target, for use when player respawns etc.
     * Note: Without NBT API, this will not make the compass point correctly in Nether/End.
     * @param player The player whose compass target is to be updated.
     * @param targetLocation The location to set as the compass target.
     */
    public void updatePlayerNavigationCompass(Player player, Location targetLocation) {
        // Without NBT API, we can only rely on player.setCompassTarget()
        player.setCompassTarget(targetLocation);
        // You might want to send a message to the player if the target is in a different dimension
        // so they understand why their compass is spinning.
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
                        if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(ChatColor.GOLD + "Navigation Compass")) {
                            isHoldingCompass = true;
                        }
                    }
                    if (!isHoldingCompass && offHandItem != null && offHandItem.getType() == Material.COMPASS) {
                        ItemMeta meta = offHandItem.getItemMeta();
                        if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(ChatColor.GOLD + "Navigation Compass")) {
                            isHoldingCompass = true;
                        }
                    }

                    if (isHoldingCompass) {
                        Location destination = playerDestinations.get(player);
                        if (destination != null) {
                            player.setCompassTarget(destination); // Always attempt to set target

                            String destinationName = getKeyByValue(predefinedDestinationsByWorld.getOrDefault(destination.getWorld(), Collections.emptyMap()), destination);
                            String targetWorldDisplayName;

                            // Determine the display name for the target world
                            if (destination.getWorld().getEnvironment() == World.Environment.NETHER) {
                                targetWorldDisplayName = "the Nether";
                            } else if (destination.getWorld().getEnvironment() == World.Environment.NORMAL) {
                                targetWorldDisplayName = "the Overworld";
                            } else { // This would catch the End or any other custom world environment
                                targetWorldDisplayName = destination.getWorld().getName(); // Fallback to raw name
                            }

                            // Adjust action bar message to inform about spinning compass in non-overworld dimensions
                            if (!player.getWorld().equals(destination.getWorld())) {
                                player.sendActionBar(ChatColor.RED + "Target (" + (destinationName != null ? ChatColor.AQUA + destinationName + ChatColor.RED : "location") + ") in " + targetWorldDisplayName + "! ");
                            } else if (player.getWorld().getEnvironment() != World.Environment.NORMAL) {
                                // If player is in Nether/End, even if target is in same dimension, it will spin
                                String distanceString = String.format("%.1f", player.getLocation().distance(destination)) + "m";
                                player.sendActionBar(ChatColor.RED + "Target (" + (destinationName != null ? ChatColor.AQUA + destinationName + ChatColor.RED : "location") + ") in " + " (" + distanceString + ") " + ChatColor.DARK_RED + "Compass spins in this dimension.");
                            }
                            else {
                                String distanceString = String.format("%.1f", player.getLocation().distance(destination)) + "m";
                                player.sendActionBar(ChatColor.AQUA + "Target: " + Objects.requireNonNull(destinationName) + " (" + distanceString + ")");
                            }
                        } else {
                            player.sendActionBar(ChatColor.GRAY + "No destination set. Right-click to choose!");
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
            plugin.getLogger().info("Compass update task cancelled.");
        }
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
            player.sendMessage(ChatColor.RED + "Error: Cannot add destination to a null world.");
            return;
        }

        // Add the destination to the map for its respective world.
        // computeIfAbsent ensures the inner map exists for the world before adding.
        predefinedDestinationsByWorld.computeIfAbsent(world, k -> new HashMap<>()).put(name, location);

        // Notify the player
        if(player != null) {
            player.sendMessage(ChatColor.GREEN + "Discovered " + ChatColor.AQUA + name + ChatColor.GREEN + "! Added to your Navigation Compass.");
        }
        // Optionally, immediately set their compass to this new destination.
        // If you enable this, the player's compass will point to this new location right away.
        // setPlayerDestination(player, location);
    }

    /**
     * Returns the current destination set for a specific player.
     * This is useful for other classes (like GameListener) to retrieve a player's last known target.
     * @param player The player whose destination to retrieve.
     * @return The Location of the player's current destination, or null if none is set.
     */
    public Location getPlayerDestination(Player player) {
        return playerDestinations.get(player);
    }

    private <T, E> T getKeyByValue(Map<T, E> map, E value) {
        for (Map.Entry<T, E> entry : map.entrySet()) {
            if (Objects.equals(value, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }
}