package org.speedrun.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.speedrun.speedrun.events.StructureFoundEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class GameListener implements Listener {
    private final Speedrun plugin;
    private final Map<UUID, Long> lastBellInteract = new ConcurrentHashMap<>();
    private static final long BELL_COOLDOWN = 5000;
    private final int NETHER_PORTAL_CHECK_RADIUS; // Radius for checking nearby blocks for portals when lighting
    private final int TELEPORT_PORTAL_SEARCH_RADIUS; // Increased radius for finding portal block after teleportation
    private static final Set<EntityType> FOOD_MOBS = Set.of(
            EntityType.SHEEP,
            EntityType.COW,
            EntityType.CHICKEN,
            EntityType.PIG
    );
    private final GameManager gameManager;
    private void increment(String key){
        plugin.getGameManager().incrementCounter(key);
    }

    public GameListener(Speedrun plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;

        this.NETHER_PORTAL_CHECK_RADIUS = plugin.getConfigManager().getNetherPortalCheckRadius();
        this.TELEPORT_PORTAL_SEARCH_RADIUS = plugin.getConfigManager().getTeleportPortalSearchRadius();
    }

    // --- Game State Events ---
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        if (plugin.getConfigManager().isStartOnFirstJoin() && !plugin.getGameManager().isRunning()) {
            if (Bukkit.getOnlinePlayers().size() == 1) {
                plugin.getGameManager().startRun();
            }
        }

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

        if (mobType == EntityType.ENDER_DRAGON) {
            plugin.getGameManager().stopRun(true);
        }

        // mobs kill logger and counter
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
    public void onStructureFound(StructureFoundEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        logger.logStructureFound(
                event.getPlayer(),
                event.getStructureKey(),
                event.getLocation()
        );

        // condition waypoints :) (true in config)
        if (!plugin.getConfigManager().areWaypointsEnabled()) return;

        gameManager.getCasualModeStructureManager().createBeaconStructure(event.getLocation(), event.getStructureKey());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        //later heatmap data log here::to do

        // waypoint protection
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

        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE) {
            plugin.getTaskManager().trackItemPickup(player, item);
            plugin.getTaskManager().updateItemTasks();
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        SpeedrunLogger logger = gameManager.getLogger();
        ItemStack result = event.getRecipe().getResult();

        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE) {
            plugin.getTaskManager().trackItemCraft(player, event.getCurrentItem());
            plugin.getTaskManager().updateItemTasks();
        }

        // craft log
        if (result.getType() == Material.ENDER_EYE) {
            logger.logMilestone(player.getName(), "crafted_eye_of_ender");
        }
    }

    // --- Structure Finding Events ---
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        Advancement adv = event.getAdvancement();
        String key = adv.getKey().getKey();
        Player player = event.getPlayer();
        String playerName = player.getName();

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

    /**
     * ИЗМЕНЕНО: Обрабатывает телепортацию в обе стороны для определения координат выхода.
     * Теперь пытается найти точный блок портала в месте назначения с увеличенным радиусом поиска.
     */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        SpeedrunLogger logger = gameManager.getLogger();

        Location from = event.getFrom();
        Location to = event.getTo();
        World.Environment fromWorld = from.getWorld().getEnvironment();
        World.Environment toWorld = to.getWorld().getEnvironment();

        logger.logPlayerPortalFromTo(event.getPlayer().getName(), fromWorld, toWorld);

        // Если портал еще не был найден, не делаем ничего
        if (!plugin.getStructureManager().isPortalPartiallyFound()) {
            return;
        }

        // Поиск ближайшего блока NETHER_PORTAL в месте назначения с увеличенным радиусом
        Location preciseExitLoc = findNearestNetherPortalBlock(to, TELEPORT_PORTAL_SEARCH_RADIUS);
        if (preciseExitLoc == null) {
            // Если точный блок портала не найден, используем исходное место назначения
            preciseExitLoc = to;
        }

        // Из Верхнего в Нижний
        if (fromWorld == World.Environment.NORMAL && toWorld == World.Environment.NETHER) {
            // Если координаты в Нижнем мире еще не известны, записываем их
            if (plugin.getStructureManager().getNetherPortalLocation() == null) {
                plugin.getStructureManager().portalExitFound(preciseExitLoc);
            }
        }
        // Из Нижнего в Верхний
        else if (fromWorld == World.Environment.NETHER && toWorld == World.Environment.NORMAL) {
            // Если координаты в Верхнем мире еще не известны, записываем их
            if (plugin.getStructureManager().getOverworldPortalLocation() == null) {
                plugin.getStructureManager().portalExitFound(preciseExitLoc);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Village Bell (Manual click)
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.BELL) {
            Player player = event.getPlayer();
            long now = System.currentTimeMillis();
            if (lastBellInteract.getOrDefault(player.getUniqueId(), 0L) + BELL_COOLDOWN > now) return;

            if (plugin.getStructureManager().isVillageSearchActive()) {
                plugin.getStructureManager().structureFound(player, "VILLAGE", event.getClickedBlock().getLocation());
                lastBellInteract.put(player.getUniqueId(), now);
            }
        }

        // ИЗМЕНЕНО: Логика зажигания портала теперь вызывает новый метод в StructureManager
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getItem() != null && event.getItem().getType() == Material.FLINT_AND_STEEL) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null && clickedBlock.getType() == Material.OBSIDIAN) {
                // Небольшая задержка, чтобы портал успел физически появиться в мире
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        for (Block nearby : getNearbyBlocks(clickedBlock, NETHER_PORTAL_CHECK_RADIUS)) {
                            if (nearby.getType() == Material.NETHER_PORTAL) {
                                // Вызываем новый унифицированный метод
                                plugin.getStructureManager().portalLit(event.getPlayer(), nearby.getLocation());
                                cancel();
                                return;
                            }
                        }
                    }
                }.runTaskLater(plugin, 2L); // 2 тика для надежности
            }
        }
    }

    /**
     * Вспомогательный метод для получения блоков в заданном радиусе вокруг начального блока.
     * @param start Начальный блок.
     * @param radius Радиус поиска.
     * @return Список блоков в радиусе.
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
     * Ищет ближайший блок NETHER_PORTAL вокруг заданной локации.
     * @param centerLoc Центральная локация для поиска.
     * @param searchRadius Радиус поиска.
     * @return Локация ближайшего блока NETHER_PORTAL, или null если не найдено.
     */
    private Location findNearestNetherPortalBlock(Location centerLoc, int searchRadius) {
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

    private boolean isStainedGlass(Material material) {
        return material.name().endsWith("_STAINED_GLASS") ||
                material.name().endsWith("_STAINED_GLASS_PANE");
    }
}
