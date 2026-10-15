package org.speedrun.speedrun;

import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.speedrun.speedrun.events.StructureFoundEvent;
import org.speedrun.speedrun.managers.ConfigManager;
import org.speedrun.speedrun.managers.GameManager;
import org.speedrun.speedrun.utils.MessageUtil;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens for various Bukkit events to drive the speedrun logic.
 * This class handles everything from player joins and deaths to structure detection and task progress.
 */
public class GameListener implements Listener {
    private final Speedrun plugin;
    private final GameManager gameManager;

    // Cooldown to prevent spamming bell interactions.
    private final Map<UUID, Long> lastBellInteract = new ConcurrentHashMap<>();
    private final Set<String> loggedBiomeChunks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastLoggedPlayerBlocks = new ConcurrentHashMap<>();
    private static final long BELL_COOLDOWN = 5000;

    private final AtomicLong portalSearchGeneration = new AtomicLong();
    private static final String STRUCTURE_MARKER_METADATA = "indestructible";
    private static final String HIDDEN_LODESTONE_METADATA = "speedrun_hidden_lodestone";
    /** Marks item entities dropped by players so picking them up again is not counted twice. */
    private final NamespacedKey playerDropKey;
    /** Keeps one exit-portal search within the platform's chunk queue capacity (21 x 21 chunks). */
    static final int MAX_PORTAL_SEARCH_RADIUS = 160;

    private static final Set<EntityType> FOOD_MOBS = Set.of(
            EntityType.SHEEP,
            EntityType.COW,
            EntityType.CHICKEN,
            EntityType.PIG
    );

    public GameListener(Speedrun plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;

        this.playerDropKey = new NamespacedKey(plugin, "player_drop");
    }

    /** Structure detection and task progress only happen while a run is active. */
    private boolean isRunActive() {
        return gameManager.isRunning();
    }

    private void increment(String key){
        plugin.getGameManager().incrementCounter(key);
    }

    public void invalidatePendingPortalSearches() {
        portalSearchGeneration.incrementAndGet();
        plugin.getTraceLogger().trace("portal", "pending_portal_searches_invalidated",
                "generation", portalSearchGeneration.get());
    }

    public void resetRuntimeCaches() {
        invalidatePendingPortalSearches();
        loggedBiomeChunks.clear();
        lastLoggedPlayerBlocks.clear();
    }
    /**
     * Utility method to create a consistent Navigation Compass.
     * Placed here because GameListener needs it for join/death events.
     */
    private ItemStack createNavigationCompass() {
        if (plugin.getCasualGameModeManager() != null
                && plugin.getCasualGameModeManager().getCompassListener() != null) {
            return plugin.getCasualGameModeManager().getCompassListener().createNavigationCompass(null, null);
        }

        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getConfigManager().getFormattedText("items.navigation-compass.name"));
            meta.setLore(plugin.getConfigManager().getFormattedTextList("items.navigation-compass.lore"));
            compass.setItemMeta(meta);
        }
        return compass;
    }
    // =========================================================================================
    // Game State Events
    // =========================================================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        SpeedrunLogger logger = gameManager.getLogger();

        if (plugin.getCasualGameModeManager().isCasualModeActive() && !player.hasPlayedBefore()) {
            player.getInventory().addItem(createNavigationCompass());
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.welcome-received"));
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("compass.welcome-hint"));
        }


        // If configured, start the run when the first player joins.
        if (plugin.getConfigManager().isStartOnFirstJoin() && plugin.getGameManager().isAutoStartAllowed()) {
            if (Bukkit.getOnlinePlayers().size() == 1) {
                plugin.getGameManager().startRun();
                if (plugin.getConfigManager().isResetTimeOnJoinEnabled()) {
                    resetDayTimeForClockWorlds();
                }
            }
        }

        // Rescale tasks if player scaling is enabled.
        if (plugin.getConfigManager().isPlayerScalingEnabled()) {
            plugin.getTaskManager().rescaleTasksForOnlinePlayers();
        }

        plugin.getGameManager().handlePlayerReturn();
        plugin.getScoreboardManager().updateScoreboard(event.getPlayer());
        plugin.getAdminNotices().onJoin(player);

        logger.logPlayerJoinOrQuit(event.getPlayer().getName(), "join");
    }

    private void resetDayTimeForClockWorlds() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            try {
                world.setTime(1000L);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipped time reset for world '" + world.getName()
                        + "': " + ex.getMessage());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        logger.logPlayerJoinOrQuit(event.getPlayer().getName(), "quit");
        plugin.getScoreboardManager().removePlayer(event.getPlayer().getUniqueId());
        lastBellInteract.remove(event.getPlayer().getUniqueId());
        lastLoggedPlayerBlocks.remove(event.getPlayer().getUniqueId());
        if (gameManager.getCompassListener() != null) {
            gameManager.getCompassListener().forgetPlayer(event.getPlayer());
        }
        // The quitting player still counts as online during this event, so react one tick later.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.getConfigManager().isPlayerScalingEnabled()) {
                plugin.getTaskManager().rescaleTasksForOnlinePlayers();
            }
            plugin.getGameManager().handleServerEmptyCheck();
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getGameManager().isRunning() || event.getTo() == null) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        logPlayerBlockMove(event.getPlayer(), from, to);
        handlePortalBlockMove(event, from, to);

        if (!plugin.getConfigManager().isChunkBiomeLoggingEnabled()
                || to.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return;
        }

        if (from.getWorld().equals(to.getWorld())
                && from.getBlockX() >> 4 == to.getBlockX() >> 4
                && from.getBlockZ() >> 4 == to.getBlockZ() >> 4) {
            return;
        }

        int chunkX = to.getBlockX() >> 4;
        int chunkZ = to.getBlockZ() >> 4;
        String key = to.getWorld().getUID() + ":" + chunkX + ":" + chunkZ;
        if (!loggedBiomeChunks.add(key)) {
            return;
        }

        int sampleX = (chunkX << 4) + 8;
        int sampleZ = (chunkZ << 4) + 8;
        int sampleY = to.getWorld().getHighestBlockYAt(sampleX, sampleZ);
        String biome = to.getWorld().getBlockAt(sampleX, sampleY, sampleZ).getBiome().toString();
        gameManager.getLogger().logChunkBiome(event.getPlayer(), chunkX, chunkZ, biome);
    }

    private void logPlayerBlockMove(Player player, Location from, Location to) {
        if (!plugin.getConfigManager().isPlayerBlockLoggingEnabled()) {
            return;
        }
        if (from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        String key = to.getWorld().getUID() + ":" + to.getBlockX() + ":" + to.getBlockY() + ":" + to.getBlockZ();
        if (key.equals(lastLoggedPlayerBlocks.put(player.getUniqueId(), key))) {
            return;
        }
        gameManager.getLogger().logPlayerBlockPosition(player, to);
    }

    private void handlePortalBlockMove(PlayerMoveEvent event, Location from, Location to) {
        if (from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Block destinationBlock = to.getBlock();
        if (destinationBlock.getType() == Material.END_GATEWAY
                && destinationBlock.hasMetadata(STRUCTURE_MARKER_METADATA)) {
            event.setCancelled(true);
            return;
        }

        if (destinationBlock.getType() == Material.END_PORTAL) {
            registerEndPortalEntry(event.getPlayer(), destinationBlock.getLocation());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        EntityType mobType = event.getEntityType();
        String mob = mobType.name();

        // Check if the Ender Dragon was killed to end the run.
        if (mobType == EntityType.ENDER_DRAGON) {
            plugin.getGameManager().stopRun(true);
        }

        // Log mob kills for statistics.
        if(event.getEntity().getKiller() != null){
            logger.logMobKill(event.getEntity().getKiller(), mob, event.getEntity().getLocation());

            if(FOOD_MOBS.contains(mobType)){
                increment("food_mobs_killed");
            }

            // mob kill counter
            if(mob.equals("BLAZE")) increment("blazes_killed");
            else if(mob.equals("ENDERMAN")) increment("endermans_killed");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event){
        SpeedrunLogger logger = gameManager.getLogger();

        Player player = event.getEntity();
        String deathCause = "unknown";

        if(player.getLastDamageCause() != null){
            EntityDamageEvent.DamageCause cause = player.getLastDamageCause().getCause();
            deathCause = cause.name();
        }

        logger.logPlayerDeath(player, deathCause, player.getLocation());
        if (plugin.getCasualGameModeManager().isCasualModeActive() && gameManager.getCompassListener() != null) {
            // The respawn handler hands out a fresh compass; a dropped one would be a duplicate.
            event.getDrops().removeIf(gameManager.getCompassListener()::isNavigationCompass);
        }
        if (plugin.getCasualGameModeManager().isCasualModeActive()
                && plugin.getConfigManager().isDeathLocationCompassEnabled()
                && gameManager.getCompassListener() != null) {
            gameManager.getCompassListener().recordPlayerDeathLocation(player, player.getLocation());
        }

        increment(player.getName() + "_deaths");
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if(plugin.getCasualGameModeManager().isCasualModeActive()){
            Player player = event.getPlayer();
            if (hasNavigationCompass(player)) {
                return; // kept inventory: do not hand out a second compass
            }
            player.getInventory().addItem(createNavigationCompass());
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("commands.givecompass.received"));
        }
    }

    // =========================================================================================
    // Structure and Task Events
    // =========================================================================================

    @EventHandler
    public void onStructureFound(StructureFoundEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled()) {
            return;
        }
        plugin.getTraceLogger().trace("structure", "structure_found_event",
                "player", event.getPlayer(),
                "key", event.getStructureKey(),
                "location", event.getLocation());

        plugin.getSpeedrunLogger().logStructureFound(
                event.getPlayer(),
                event.getStructureKey(),
                event.getLocation()
        );

        if(plugin.getCasualGameModeManager().isCasualModeActive()){
            // Get CompassListener from GameManager to add the dynamic destination
            if (gameManager.getCompassListener() != null) {
                gameManager.getCompassListener().addDynamicDestination(event.getPlayer(), event.getStructureKey(), event.getLocation());
            }

            if (event.getStructureKey().equals("BASTION")) {
                plugin.getLogger().info("Bastion Remnant found by DedicatedGameListener at: " + event.getLocation().toVector() + ". Notifying highlight manager.");
                // Get the CasualHighlightManager instance and notify it
                if (plugin.getCasualGameModeManager().getCasualHighlightManager() != null) {
                    plugin.getCasualGameModeManager().getCasualHighlightManager().addDetectedBastion(event.getLocation(), event.getLocation().getWorld());
                } else {
                    plugin.getLogger().warning("CasualHighlightManager is null when a Bastion Remnant was detected!");
                }
            }

            // If enabled, create a temporary beacon waypoint at the structure's location.
            if (plugin.getConfigManager().areWaypointsEnabled()) {
                if(Objects.equals(event.getStructureKey(), "NETHER_PORTAL")){ return; }
                gameManager.getCasualModeStructureManager().trackStructureWaypoint(event.getLocation(), event.getStructureKey());
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        // Protect the beacon waypoints from being destroyed.
        if ((block.getType() == Material.BEACON ||
                block.getType() == Material.END_GATEWAY ||
                block.getType() == Material.IRON_BLOCK ||
                isStainedGlass(type)) &&
                block.hasMetadata(STRUCTURE_MARKER_METADATA)) {
            event.setCancelled(true);
            MessageUtil.send(event.getPlayer(), plugin.getConfigManager().getFormatted("protection.structure-marker"));
            return;
        }

        if (block.getType() == Material.LODESTONE && block.hasMetadata(HIDDEN_LODESTONE_METADATA)) {
            event.setCancelled(true);
            MessageUtil.send(event.getPlayer(), plugin.getConfigManager().getFormatted("protection.hidden-lodestone"));
        }
    }

    /** Waypoint markers and hidden lodestones must survive explosions (and must not drop as loot). */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(GameListener::isPluginBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(GameListener::isPluginBlock);
    }

    /** Pistons would move marker blocks away from their metadata, making them minable. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(GameListener::isPluginBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(GameListener::isPluginBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getStructureManager().onChunkLoad(event.getChunk());
    }

    private static boolean isPluginBlock(Block block) {
        return block.hasMetadata(STRUCTURE_MARKER_METADATA) || block.hasMetadata(HIDDEN_LODESTONE_METADATA);
    }

    // --- Task & Progress Events ---
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled() || !isRunActive()) {
            return;
        }
        // Update tasks when in INVENTORY tracking mode.
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.INVENTORY) {
            plugin.getTaskManager().updateItemTasks();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        event.getItemDrop().getPersistentDataContainer().set(playerDropKey, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled() || !isRunActive()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) return;

        SpeedrunLogger logger = gameManager.getLogger();
        ItemStack item = event.getItem().getItemStack();

        if (item.getType() == Material.FLINT) {
            logger.logMilestone(player.getName(), "flint_pickup");
        }

        // Update tasks when in CUMULATIVE tracking mode. Items a player dropped were already
        // counted when they were first collected.
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE
                && !event.getItem().getPersistentDataContainer().has(playerDropKey, PersistentDataType.BYTE)) {
            int pickedUp = item.getAmount() - Math.max(0, event.getRemaining());
            plugin.getTaskManager().trackItemCollected(player, item.getType(), pickedUp);
            plugin.getTaskManager().updateItemTasks();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled() || !isRunActive()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Update tasks when in CUMULATIVE tracking mode.
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE) {
            ItemStack result = event.getRecipe().getResult();
            if (event.isShiftClick()) {
                // Shift-click crafts as many items as fit; the event only shows one result, so
                // count the difference in the player's inventory after the craft is applied.
                Material type = result.getType();
                int before = countItems(player, type);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || !isRunActive()) {
                        return;
                    }
                    int crafted = countItems(player, type) - before;
                    if (crafted > 0) {
                        plugin.getTaskManager().trackItemCollected(player, type, crafted);
                        plugin.getTaskManager().updateItemTasks();
                    }
                });
            } else {
                plugin.getTaskManager().trackItemCollected(player, result.getType(), result.getAmount());
                plugin.getTaskManager().updateItemTasks();
            }
        }

        // craft log
        if (event.getRecipe().getResult().getType() == Material.ENDER_EYE) {
            gameManager.getLogger().logMilestone(player.getName(), "crafted_eye_of_ender");
        }
    }

    // --- Structure Finding Events ---
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled() || !isRunActive()) {
            return;
        }
        SpeedrunLogger logger = gameManager.getLogger();

        Advancement adv = event.getAdvancement();

        // Advancements are namespaced, we only care about the key itself.
        String key = adv.getKey().getKey();
        Player player = event.getPlayer();

        String playerName = player.getName();

        // Use advancements to automatically detect certain structures or milestones.
        // Log all relevant advancements.
        final Map<String, Runnable> handlers = new HashMap<>() {{
            put("story/mine_stone", () -> logger.logMilestone(playerName, "spawned"));
            put("story/mine_iron", () -> logger.logMilestone(playerName, "first_iron"));
            put("story/smelt_iron", () -> logger.logMilestone(playerName, "first_iron_smelted"));
            put("story/enter_the_nether", () -> logger.logMilestone(playerName, "enter_the_nether"));
            put("nether/find_fortress", () -> {
                logger.logMilestone(playerName, "find_fortress");
                reportAdvancementStructure(player, "FORTRESS");
            });
            put("nether/find_bastion", () -> {
                logger.logMilestone(playerName, "find_bastion");
                reportAdvancementStructure(player, "BASTION");
            });
            put("nether/obtain_blaze_rod", () -> logger.logMilestone(playerName, "first_blaze_rod"));
            put("story/follow_ender_eye", () -> {
                logger.logMilestone(playerName, "first_stronghold_enter");
                reportAdvancementStructure(player, "END_PORTAL");
            });
            put("story/enter_the_end", () -> logger.logMilestone(playerName, "first_end_enter"));
        }};

        Runnable handler = handlers.get(key);
        if (handler != null) {
            handler.run();
        }
    }

    /**
     * Advancements are earned once per player, so in multiplayer later players would move an
     * exactly known location to wherever they stand. Only empty or approximate locations are set.
     */
    private void reportAdvancementStructure(Player player, String key) {
        if (plugin.getStructureManager().getFoundStructures().get(key) != null
                && !plugin.getStructureManager().isApproximateStructure(key)) {
            return;
        }
        plugin.getStructureManager().structureFound(player, key, player.getLocation());
    }

    // =========================================================================================
    // Portal Detection Logic
    // =========================================================================================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled() || !isRunActive()) {
            return;
        }
        // Manual village detection by interacting with a bell.
        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK)
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.BELL) {

            Player player = event.getPlayer();
            long now = System.currentTimeMillis();
            if (lastBellInteract.getOrDefault(player.getUniqueId(), 0L) + BELL_COOLDOWN > now) return;

            Location villageLocation = plugin.getStructureManager().getFoundStructures().get("VILLAGE");
            if (!plugin.getStructureManager().getFoundStructures().containsKey("VILLAGE")
                    || villageLocation == null
                    || plugin.getStructureManager().isApproximateStructure("VILLAGE")) {
                plugin.getStructureManager().villageSearchFailed = false;  // reset the failed flag
                plugin.getStructureManager().structureFound(player, "VILLAGE", event.getClickedBlock().getLocation());
                lastBellInteract.put(player.getUniqueId(), now);
            }
        }

        // Detect when a player lights a portal.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getItem() != null
                && (event.getItem().getType() == Material.FLINT_AND_STEEL
                || event.getItem().getType() == Material.FIRE_CHARGE)) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null) {
                // A short delay is needed to allow the portal block to physically appear in the world.
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        for (Block nearby : getNearbyBlocks(clickedBlock, plugin.getConfigManager().getNetherPortalCheckRadius())) {
                            if (nearby.getType() == Material.NETHER_PORTAL) {
                                plugin.getStructureManager().portalLit(event.getPlayer(), nearby.getLocation());
                                cancel();
                                return;
                            }
                        }
                    }
                }.runTaskLater(plugin, 2L); // 2 ticks should be enough.
            }
        }
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled() || !isRunActive()) {
            return;
        }
        BlockIgniteEvent.IgniteCause cause = event.getCause();
        if (cause != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                && cause != BlockIgniteEvent.IgniteCause.FIREBALL
                && cause != BlockIgniteEvent.IgniteCause.LAVA
                && cause != BlockIgniteEvent.IgniteCause.SPREAD) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Block nearby : getNearbyBlocks(event.getBlock(), plugin.getConfigManager().getNetherPortalCheckRadius())) {
                    if (nearby.getType() == Material.NETHER_PORTAL) {
                        plugin.getStructureManager().portalLit(event.getPlayer(), nearby.getLocation());
                        cancel();
                        return;
                    }
                }
            }
        }.runTaskLater(plugin, 2L);
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (plugin.getConfigManager().isHardcoreModeEnabled() || !isRunActive()) {
            return;
        }
        if (event.getTo() == null) {
            plugin.getTraceLogger().trace("portal", "player_portal_skipped",
                    "player", event.getPlayer(),
                    "from", event.getFrom(),
                    "reason", "null_destination");
            return;
        }

        World.Environment fromWorld = event.getFrom().getWorld().getEnvironment();
        World.Environment toWorld = event.getTo().getWorld().getEnvironment();
        plugin.getTraceLogger().trace("portal", "player_portal_event",
                "player", event.getPlayer(),
                "from", event.getFrom(),
                "to", event.getTo(),
                "from_environment", fromWorld,
                "to_environment", toWorld);
        if (fromWorld == World.Environment.NORMAL && toWorld == World.Environment.THE_END) {
            gameManager.getLogger().logPlayerPortalFromTo(event.getPlayer().getName(), fromWorld, toWorld);
            Location portalBlock = findNearbyBlockOfType(event.getFrom(), 2, Material.END_PORTAL);
            registerEndPortalEntry(event.getPlayer(), portalBlock != null ? portalBlock : event.getFrom());
            return;
        }

        seedPortalEntry(event);
        Location to = event.getTo();
        // Searches of different players run side by side; only reset/remove invalidates them.
        long searchGeneration = portalSearchGeneration.get();

        AtomicBoolean handled = new AtomicBoolean(false);
        long timeout = 20L * plugin.getConfigManager().getPortalSearchTimeout();

        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (portalSearchGeneration.get() != searchGeneration) {
                return;
            }
            if (handled.compareAndSet(false, true)) {
                plugin.getLogger().warning("Portal search timeout, using approximate location");
                plugin.getTraceLogger().trace("portal", "portal_search_timeout",
                        "player", event.getPlayer(),
                        "fallback", to,
                        "generation", searchGeneration);
                handlePortalLogic(event, to);
            }
        }, timeout);

        // Chunks are loaded through the platform queue (async on Paper, one per tick elsewhere)
        // and scanned as snapshots on a worker thread, so no platform blocks the server thread.
        findPortalBlockAsync(to, Math.min(MAX_PORTAL_SEARCH_RADIUS, plugin.getConfigManager().getTeleportPortalSearchRadius()))
                .orTimeout(plugin.getConfigManager().getPortalSearchTimeout(), java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ex -> null) // on timeout or failure -> use fallback
                .thenAccept(preciseExitLoc -> plugin.getPlatform().executeSync(() -> {
                    // Reset/stop can invalidate the search while this callback is queued.
                    if (portalSearchGeneration.get() != searchGeneration) {
                        return;
                    }
                    if (handled.compareAndSet(false, true)) {
                        timeoutTask.cancel();
                        Location finalLocation = (preciseExitLoc != null) ? preciseExitLoc : to;
                        plugin.getTraceLogger().trace("portal", "portal_search_completed",
                                "player", event.getPlayer(),
                                "precise_exit", preciseExitLoc,
                                "final_location", finalLocation,
                                "generation", searchGeneration);
                        handlePortalLogic(event, finalLocation);
                    }
                }));
    }

    /**
     * Handles the logic after a player has teleported and the precise exit location has been found.
     */
    private void handlePortalLogic(PlayerPortalEvent event, Location finalLocation) {

        World.Environment fromWorld = event.getFrom().getWorld().getEnvironment();
        World.Environment toWorld = event.getTo().getWorld().getEnvironment();

        gameManager.getLogger().logPlayerPortalFromTo(event.getPlayer().getName(), fromWorld, toWorld);
        plugin.getTraceLogger().trace("portal", "portal_logic",
                "player", event.getPlayer(),
                "from_environment", fromWorld,
                "to_environment", toWorld,
                "final_location", finalLocation,
                "known_overworld_portal", plugin.getStructureManager().getOverworldPortalLocation(),
                "known_nether_portal", plugin.getStructureManager().getNetherPortalLocation());

        // If we teleported to a dimension where the portal location is unknown, record it.
        if (fromWorld == World.Environment.NORMAL && toWorld == World.Environment.NETHER) {
            if (plugin.getStructureManager().getNetherPortalLocation() == null) {
                plugin.getStructureManager().portalExitFound(finalLocation);
            }
        }
        else if (fromWorld == World.Environment.NETHER && toWorld == World.Environment.NORMAL) {
            if (plugin.getStructureManager().getOverworldPortalLocation() == null) {
                plugin.getStructureManager().portalExitFound(finalLocation);
            }
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            return;
        }

        Block fromBlock = event.getFrom().getBlock();
        if (fromBlock.getType() == Material.END_GATEWAY
                && fromBlock.hasMetadata(STRUCTURE_MARKER_METADATA)) {
            event.setCancelled(true);
        }
    }

    private void registerEndPortalEntry(Player player, Location location) {
        if (plugin.getConfigManager().isHardcoreModeEnabled()) {
            return;
        }
        if (plugin.getStructureManager().getFoundStructures().get("END_PORTAL") != null) {
            return;
        }
        plugin.getStructureManager().structureFound(player, "END_PORTAL", location);
    }

    private void seedPortalEntry(PlayerPortalEvent event) {
        World.Environment fromWorld = event.getFrom().getWorld().getEnvironment();
        if (fromWorld != World.Environment.NORMAL && fromWorld != World.Environment.NETHER) {
            return;
        }

        Location known = fromWorld == World.Environment.NORMAL
                ? plugin.getStructureManager().getOverworldPortalLocation()
                : plugin.getStructureManager().getNetherPortalLocation();
        if (known != null) {
            return;
        }

        Location preciseEntry = findPortalBlockSync(event.getFrom(), plugin.getConfigManager().getNetherPortalCheckRadius());
        Location entryLocation = preciseEntry != null ? preciseEntry : event.getFrom();
        plugin.getTraceLogger().trace("portal", "portal_entry_seeded",
                "player", event.getPlayer(),
                "from", event.getFrom(),
                "precise_entry", preciseEntry,
                "entry_location", entryLocation);
        plugin.getStructureManager().portalLit(event.getPlayer(), entryLocation);
    }

    // =========================================================================================
    // Helper Methods
    // =========================================================================================

    /**
     * Helper method for obtaining blocks within a specified radius around the initial block.
     * @param start Initial block
     * @param radius Search radius
     * @return List of blocks within the radius
     */
    private List<Block> getNearbyBlocks(Block start, int radius) {
        List<Block> blocks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    blocks.add(start.getRelative(x, y, z));
                }
            }
        }
        return blocks;
    }

    /**
     * Synchronously scans for a portal block. Can cause server lag.
     */
    private Location findPortalBlockSync(Location centerLoc, int searchRadius) {
        if (centerLoc == null || centerLoc.getWorld() == null) {
            return null;
        }
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= searchRadius; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    Block block = centerLoc.clone().add(x, y, z).getBlock();
                    if (block.getType() == Material.NETHER_PORTAL) {
                        return block.getLocation();
                    }
                }
            }
        }
        return null;
    }

    private Location findNearbyBlockOfType(Location centerLoc, int searchRadius, Material material) {
        if (centerLoc == null || centerLoc.getWorld() == null) {
            return null;
        }
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= searchRadius; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    Block block = centerLoc.clone().add(x, y, z).getBlock();
                    if (block.getType() == material) {
                        return block.getLocation();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Loads each required chunk once through the platform queue, captures its snapshot on the
     * server thread, and scans only immutable snapshots on a worker thread.
     * Must be called from the server thread.
     *
     * @param centerLoc central search location from the portal event
     * @param searchRadius search radius in blocks
     * @return the first portal block in the existing traversal order, or null
     */
    public CompletableFuture<Location> findPortalBlockAsync(Location centerLoc, int searchRadius) {
        if (centerLoc == null || centerLoc.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }

        World world = centerLoc.getWorld();
        int centerX = centerLoc.getBlockX();
        int centerY = centerLoc.getBlockY();
        int centerZ = centerLoc.getBlockZ();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        List<CompletableFuture<ChunkSnapshot>> snapshotFutures = loadPortalChunkSnapshots(
                (chunkX, chunkZ) -> plugin.getPlatform().snapshotChunk(world, chunkX, chunkZ),
                centerX, centerZ, searchRadius);

        return CompletableFuture.allOf(snapshotFutures.toArray(new CompletableFuture<?>[0]))
                .thenCompose(ignored -> {
                    List<ChunkSnapshot> snapshots = snapshotFutures.stream()
                            .map(future -> future.getNow(null))
                            .filter(Objects::nonNull)
                            .toList();
                    return plugin.getPlatform().compute(() -> {
                        PortalBlockPosition found = findPortalBlockInSnapshots(snapshots,
                                centerX, centerY, centerZ, searchRadius, minHeight, maxHeight);
                        return found == null ? null : new Location(world, found.x(), found.y(), found.z());
                    });
                });
    }

    /** Supplies a chunk snapshot future for chunk coordinates. */
    @FunctionalInterface
    interface ChunkSnapshotLoader {
        CompletableFuture<ChunkSnapshot> load(int chunkX, int chunkZ);
    }

    /**
     * Requests every chunk overlapping the square search area exactly once. Individual load
     * failures resolve to null so one unavailable chunk does not abort the whole search.
     */
    static List<CompletableFuture<ChunkSnapshot>> loadPortalChunkSnapshots(
            ChunkSnapshotLoader loader, int centerX, int centerZ, int searchRadius) {
        List<CompletableFuture<ChunkSnapshot>> snapshots = new ArrayList<>();
        int minChunkX = (centerX - searchRadius) >> 4;
        int maxChunkX = (centerX + searchRadius) >> 4;
        int minChunkZ = (centerZ - searchRadius) >> 4;
        int maxChunkZ = (centerZ + searchRadius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                snapshots.add(loader.load(chunkX, chunkZ).exceptionally(ignored -> null));
            }
        }
        return snapshots;
    }

    static PortalBlockPosition findPortalBlockInSnapshots(Collection<ChunkSnapshot> snapshots,
            int centerX, int centerY, int centerZ, int searchRadius, int minHeight, int maxHeight) {
        Map<Long, ChunkSnapshot> byChunk = new HashMap<>();
        for (ChunkSnapshot snapshot : snapshots) {
            byChunk.put(portalChunkKey(snapshot.getX(), snapshot.getZ()), snapshot);
        }
        int minY = Math.max(minHeight, centerY - searchRadius);
        int maxY = Math.min(maxHeight - 1, centerY + searchRadius);
        for (int x = centerX - searchRadius; x <= centerX + searchRadius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = centerZ - searchRadius; z <= centerZ + searchRadius; z++) {
                    ChunkSnapshot snapshot = byChunk.get(portalChunkKey(x >> 4, z >> 4));
                    if (snapshot != null && snapshot.getBlockType(x & 15, y, z & 15) == Material.NETHER_PORTAL) {
                        return new PortalBlockPosition(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    private static long portalChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    record PortalBlockPosition(int x, int y, int z) { }

    private static int countItems(Player player, Material type) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == type) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private boolean hasNavigationCompass(Player player) {
        if (gameManager.getCompassListener() == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && gameManager.getCompassListener().isNavigationCompass(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStainedGlass(Material material) {
        return material.name().endsWith("_STAINED_GLASS") ||
                material.name().endsWith("_STAINED_GLASS_PANE");
    }
}
