package org.speedrun.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.speedrun.speedrun.events.StructureFoundEvent;
import org.speedrun.speedrun.managers.ConfigManager;
import org.speedrun.speedrun.managers.GameManager;
import org.speedrun.speedrun.utils.PaperCheckUtil;
import java.util.Collections;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for various Bukkit events to drive the speedrun logic.
 * This class handles everything from player joins and deaths to structure detection and task progress.
 * |
 * Прослуховує різноманітні події Bukkit для керування логікою спідрану.
 * Цей клас обробляє все: від входів та смертей гравців до виявлення структур та прогресу завдань.
 */
class GameListener implements Listener {
    private final Speedrun plugin;
    private final GameManager gameManager;

    // Cooldown to prevent spamming bell interactions.
    // Кулдаун для запобігання спаму взаємодіями з дзвоном.
    private final Map<UUID, Long> lastBellInteract = new ConcurrentHashMap<>();
    private static final long BELL_COOLDOWN = 5000;

    // Cached config values for performance.
    // Кешовані значення конфігу для продуктивності.
    private final int NETHER_PORTAL_CHECK_RADIUS; // Radius for checking nearby blocks for portals when lighting
    private final int TELEPORT_PORTAL_SEARCH_RADIUS; // Increased radius for finding portal block after teleportation

    private static final Set<EntityType> FOOD_MOBS = Set.of(
            EntityType.SHEEP,
            EntityType.COW,
            EntityType.CHICKEN,
            EntityType.PIG
    );

    public GameListener(Speedrun plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;

        this.NETHER_PORTAL_CHECK_RADIUS = plugin.getConfigManager().getNetherPortalCheckRadius();
        this.TELEPORT_PORTAL_SEARCH_RADIUS = plugin.getConfigManager().getTeleportPortalSearchRadius();
    }

    private void increment(String key){
        plugin.getGameManager().incrementCounter(key);
    }
    /**
     * Utility method to create a consistent Navigation Compass.
     * Placed here because GameListener needs it for join/death events.
     */
    private ItemStack createNavigationCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Navigation Compass");
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Right-click to open destinations menu."));
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
            player.sendMessage(ChatColor.GREEN + "Welcome! You received a " + ChatColor.GOLD + "Navigation Compass" + ChatColor.GREEN + ".");
            player.sendMessage(ChatColor.GREEN + "Right-click it to find important locations!");
            // Optional: Automatically set their compass target to spawn or a default location
            // player.setCompassTarget(player.getWorld().getSpawnLocation());
        }


        // If configured, start the run when the first player joins.
        // Якщо налаштовано, починаємо гру, коли приєднується перший гравець.
        if (plugin.getConfigManager().isStartOnFirstJoin() && !plugin.getGameManager().isRunning()) {
            if (Bukkit.getOnlinePlayers().size() == 1) {
                plugin.getGameManager().startRun();
            }
        }

        // Rescale tasks if player scaling is enabled.
        // Перемасштабуємо завдання, якщо увімкнено масштабування від гравців.
        if (plugin.getConfigManager().isPlayerScalingEnabled()) {
            int playerCount = Bukkit.getOnlinePlayers().size();
            double multiplier = plugin.getConfigManager().getPlayerScalingMultiplier();
            plugin.getTaskManager().getAllTasks().forEach(task -> task.scale(playerCount, multiplier));
        }

        plugin.getScoreboardManager().updateScoreboard(event.getPlayer());

        logger.logPlayerJoinOrQuit(event.getPlayer().getName(), "join");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        // Rescale tasks for the new player count.
        // Перемасштабуємо завдання для нової кількості гравців.
        if (plugin.getConfigManager().isPlayerScalingEnabled()) {
            int playerCount = Math.max(1, Bukkit.getOnlinePlayers().size() - 1);
            double multiplier = plugin.getConfigManager().getPlayerScalingMultiplier();
            plugin.getTaskManager().getAllTasks().forEach(task -> task.scale(playerCount, multiplier));
        }

        logger.logPlayerJoinOrQuit(event.getPlayer().getName(), "quit");
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        EntityType mobType = event.getEntityType();
        String mob = mobType.name();

        // Check if the Ender Dragon was killed to end the run.
        // Перевіряємо, чи був убитий Дракон Краю, щоб завершити гру.
        if (mobType == EntityType.ENDER_DRAGON) {
            plugin.getGameManager().stopRun(true);
        }

        // Log mob kills for statistics.
        // Логуємо вбивства мобів для статистики.
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

        increment(player.getName() + "_deaths");
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if(plugin.getCasualGameModeManager().isCasualModeActive()){
            Player player = event.getPlayer();
            player.getInventory().addItem(createNavigationCompass());
            player.sendMessage(ChatColor.GREEN + "You received a Navigation Compass!");
        }
    }

    // =========================================================================================
    // Structure and Task Events
    // =========================================================================================

    @EventHandler
    public void onStructureFound(StructureFoundEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        logger.logStructureFound(
                event.getPlayer(),
                event.getStructureKey(),
                event.getLocation()
        );

        if(plugin.getCasualGameModeManager().isCasualModeActive()){
            // Get CompassListener from GameManager to add the dynamic destination
            gameManager.getCompassListener().addDynamicDestination(event.getPlayer(), event.getStructureKey(), event.getLocation());

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
            // Якщо увімкнено, створюємо тимчасовий вейпоінт-маяк на місці структури.
            if (plugin.getConfigManager().areWaypointsEnabled()) {
                if(Objects.equals(event.getStructureKey(), "NETHER_PORTAL")){ return; }
                gameManager.getCasualModeStructureManager().createBeaconStructure(event.getLocation(), event.getStructureKey());
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        //later heatmap data log here::to do

        // Protect the beacon waypoints from being destroyed.
        // Захищаємо вейпоінти-маяки від руйнування.
        if ((block.getType() == Material.BEACON ||
                block.getType() == Material.IRON_BLOCK ||
                isStainedGlass(type)) &&
                block.hasMetadata("indestructible")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text("This is part of a structure marker and cannot be destroyed.", NamedTextColor.RED)
            );
        }
    }

    // --- Task & Progress Events ---
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Update tasks when in INVENTORY tracking mode.
        // Оновлюємо завдання в режимі відстеження INVENTORY.
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.INVENTORY) {
            plugin.getTaskManager().updateItemTasks();
        }
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        SpeedrunLogger logger = gameManager.getLogger();
        ItemStack item = event.getItem().getItemStack();

        if (item.getType() == Material.FLINT) {
            logger.logMilestone(player.getName(), "flint_pickup");
        }

        // Update tasks when in CUMULATIVE tracking mode.
        // Оновлюємо завдання в режимі відстеження CUMULATIVE.
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE) {
            plugin.getTaskManager().trackItemPickup(player, item);
            plugin.getTaskManager().updateItemTasks();
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Update tasks when in CUMULATIVE tracking mode.
        // Оновлюємо завдання в режимі відстеження CUMULATIVE.
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE) {
            plugin.getTaskManager().trackItemCraft(player, event.getCurrentItem());
            plugin.getTaskManager().updateItemTasks();
        }

        // craft log
        if (event.getRecipe().getResult().getType() == Material.ENDER_EYE) {
            gameManager.getLogger().logMilestone(player.getName(), "crafted_eye_of_ender");
        }
    }

    // --- Structure Finding Events ---
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        Advancement adv = event.getAdvancement();

        // Advancements are namespaced, we only care about the key itself.
        // Досягнення мають простори імен, нас цікавить лише сам ключ.
        String key = adv.getKey().getKey();
        Player player = event.getPlayer();

        String playerName = player.getName();

        // Use advancements to automatically detect certain structures or milestones.
        // Використовуємо досягнення для автоматичного виявлення певних структур або етапів.
        // Log all relevant advancements.
        // Логуємо всі релевантні досягнення.
        final Map<String, Runnable> handlers = new HashMap<>() {{
            put("story/mine_stone", () -> logger.logMilestone(playerName, "spawned"));
            put("story/mine_iron", () -> logger.logMilestone(playerName, "first_iron"));
            put("story/smelt_iron", () -> logger.logMilestone(playerName, "first_iron_smelted"));
            put("story/enter_the_nether", () -> logger.logMilestone(playerName, "enter_the_nether"));
            put("nether/find_fortress", () -> {
                logger.logMilestone(playerName, "find_fortress");
                plugin.getStructureManager().structureFound(player, "FORTRESS", player.getLocation());
            });
            put("nether/find_bastion", () -> {
                logger.logMilestone(playerName, "find_bastion");
                plugin.getStructureManager().structureFound(player, "BASTION", player.getLocation());
            });
            put("nether/obtain_blaze_rod", () -> logger.logMilestone(playerName, "first_blaze_rod"));
            put("story/follow_ender_eye", () -> {
                logger.logMilestone(playerName, "first_stronghold_enter");
                plugin.getStructureManager().structureFound(player, "END_PORTAL", player.getLocation());
            });
            put("story/enter_the_end", () -> logger.logMilestone(playerName, "first_end_enter"));
        }};

        Runnable handler = handlers.get(key);
        if (handler != null) {
            handler.run();
        }
    }

    // =========================================================================================
    // Portal Detection Logic
    // =========================================================================================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Manual village detection by right-clicking a bell.
        // Ручне виявлення села через правий клік по дзвону.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.BELL) {

            Player player = event.getPlayer();
            long now = System.currentTimeMillis();
            if (lastBellInteract.getOrDefault(player.getUniqueId(), 0L) + BELL_COOLDOWN > now) return;

            if (!plugin.getStructureManager().getFoundStructures().containsKey("VILLAGE")
                    || plugin.getStructureManager().getFoundStructures().get("VILLAGE") == null) {
                plugin.getStructureManager().villageSearchFailed = false;  // reset the failed flag
                plugin.getStructureManager().structureFound(player, "VILLAGE", event.getClickedBlock().getLocation());
                lastBellInteract.put(player.getUniqueId(), now);
            }
        }

        // Detect when a player lights a portal.
        // Виявляємо, коли гравець запалює портал.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getItem() != null
                && (event.getItem().getType() == Material.FLINT_AND_STEEL
                || event.getItem().getType() == Material.FIRE_CHARGE)) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null) {
                // A short delay is needed to allow the portal block to physically appear in the world.
                // Потрібна невелика затримка, щоб блок порталу фізично з'явився у світі.
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        for (Block nearby : getNearbyBlocks(clickedBlock, NETHER_PORTAL_CHECK_RADIUS)) {
                            if (nearby.getType() == Material.NETHER_PORTAL) {
                                plugin.getStructureManager().portalLit(event.getPlayer(), nearby.getLocation());
                                cancel();
                                return;
                            }
                        }
                    }
                }.runTaskLater(plugin, 2L); // 2 ticks should be enough. / 2 тіків має вистачити.
            }
        }
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        // Detects portals lit by the environment (e.g., lava spread).
        // Виявляє портали, запалені оточенням (напр., розповсюдженням лави).
        if (event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD
                || event.getCause() == BlockIgniteEvent.IgniteCause.LAVA) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Block nearby : getNearbyBlocks(event.getBlock(), 2)) {
                        if (nearby.getType() == Material.NETHER_PORTAL) {
                            plugin.getStructureManager().portalLit(null, nearby.getLocation());
                            cancel();
                            return;
                        }
                    }
                }
            }.runTaskLater(plugin, 2L);
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        // This logic is for finding the *exit* of a portal to get precise, linked coordinates.
        // Ця логіка призначена для пошуку *виходу* з порталу для отримання точних, пов'язаних координат.
        if (!plugin.getStructureManager().isPortalPartiallyFound()) {
            return;
        }

        Location to = event.getTo();

        AtomicBoolean handled = new AtomicBoolean(false);
        long timeout = 20L * plugin.getConfigManager().getPortalSearchTimeout();

        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (handled.compareAndSet(false, true)) {
                plugin.getLogger().warning("Portal search timeout, using approximate location");
                handlePortalLogic(event, to);
            }
        }, timeout);

        // Use an async, non-blocking method on Paper servers for better performance.
        // Використовуємо асинхронний, неблокуючий метод на серверах Paper для кращої продуктивності.
        if (PaperCheckUtil.IsPaper()) {
            findPortalBlockAsync(to, TELEPORT_PORTAL_SEARCH_RADIUS)
                    .orTimeout(plugin.getConfigManager().getPortalSearchTimeout(), java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> null) // on timeout -> use fallback
                    .thenAccept(preciseExitLoc -> {
                        if (handled.compareAndSet(false, true)) {
                            timeoutTask.cancel();
                            Location finalLocation = (preciseExitLoc != null) ? preciseExitLoc : to;
                            plugin.getServer().getScheduler().runTask(plugin,
                                    () -> handlePortalLogic(event, finalLocation));
                        }
                    });
        } else {
            // Fallback to a synchronous (potentially laggy) method for Spigot/Bukkit.
            // Резервний синхронний (потенційно лагаючий) метод для Spigot/Bukkit.
            Location preciseExitLoc = findPortalBlockSync(to, TELEPORT_PORTAL_SEARCH_RADIUS);
            Location finalLocation = (preciseExitLoc != null) ? preciseExitLoc : to;
            handlePortalLogic(event, finalLocation);
        }
    }

    /**
     * Handles the logic after a player has teleported and the precise exit location has been found.
     * Обробляє логіку після телепортації гравця та знаходження точної локації виходу.
     */
    private void handlePortalLogic(PlayerPortalEvent event, Location finalLocation) {

        World.Environment fromWorld = event.getFrom().getWorld().getEnvironment();
        World.Environment toWorld = event.getTo().getWorld().getEnvironment();

        gameManager.getLogger().logPlayerPortalFromTo(event.getPlayer().getName(), fromWorld, toWorld);

        // If we teleported to a dimension where the portal location is unknown, record it.
        // Якщо ми телепортувалися у вимір, де локація порталу невідома, записуємо її.
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

    // =========================================================================================
    // Helper Methods
    // =========================================================================================

    /**
     * Helper method for obtaining blocks within a specified radius around the initial block.
     * Допоміжний метод для отримання блоків у заданому радіусі навколо початкового блоку.
     * @param start Initial block / Початковий блок.
     * @param radius Search radius / Радіус пошуку.
     * @return List of blocks within the radius / Список блоків у радіусі.
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
     * Синхронно сканує блок порталу. Може викликати лаги сервера.
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

    /**
     * Asynchronously finds the nearest portal block using Paper's API.
     * It first loads all necessary chunks in parallel, then scans them.
     * |
     * Асинхронно знаходить найближчий блок порталу за допомогою Paper API.
     * Спочатку паралельно завантажує всі необхідні чанки, а потім сканує їх.
     *
     * @param centerLoc      Central location for search (from event.getTo()).
     * @param searchRadius   Search radius in blocks.
     * @return CompletableFuture with the location of the portal block or null if not found.
     */
    public CompletableFuture<Location> findPortalBlockAsync(Location centerLoc, int searchRadius) {
        if (centerLoc == null || centerLoc.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }

        World world = centerLoc.getWorld();
        int centerX = centerLoc.getBlockX();
        int centerY = centerLoc.getBlockY();
        int centerZ = centerLoc.getBlockZ();

        // Step 1: Collect futures for all unique chunks in the search radius.
        // Крок 1: Збираємо ф'ючерси для всіх унікальних чанків у радіусі пошуку.
        Set<CompletableFuture<Chunk>> chunkFutures = new HashSet<>();
        for (int x = centerX - searchRadius; x <= centerX + searchRadius; x++) {
            for (int z = centerZ - searchRadius; z <= centerZ + searchRadius; z++) {
                chunkFutures.add(world.getChunkAtAsync(x >> 4, z >> 4));
            }
        }

        // Step 2: When all chunks are loaded, proceed to scan them.
        // Крок 2: Коли всі чанки завантажені, переходимо до їх сканування.
        return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                .thenApplyAsync(v -> {
                    // Step 3: Now that chunks are in memory, this scan is fast and safe.
                    // Крок 3: Тепер, коли чанки в пам'яті, це сканування є швидким і безпечним.
                    for (int x = -searchRadius; x <= searchRadius; x++) {
                        for (int y = -searchRadius; y <= searchRadius; y++) {
                            for (int z = -searchRadius; z <= searchRadius; z++) {
                                int checkX = centerX + x;
                                int checkY = centerY + y;
                                int checkZ = centerZ + z;

                                // The getBlockAt check will now be instantaneous, as the chunk is already in memory.
                                if (world.getBlockAt(checkX, checkY, checkZ).getType() == Material.NETHER_PORTAL) {
                                    return new Location(world, checkX, checkY, checkZ); // Found / знайдено.
                                }
                            }
                        }
                    }
                    return null; // Not found / Не знайдено.
                });
    }

    private boolean isStainedGlass(Material material) {
        return material.name().endsWith("_STAINED_GLASS") ||
                material.name().endsWith("_STAINED_GLASS_PANE");
    }
}
