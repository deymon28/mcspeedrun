package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class GameListener implements Listener {
    private final Speedrun plugin;
    private final Map<UUID, Long> lastBellInteract = new ConcurrentHashMap<>();
    private static final long BELL_COOLDOWN = 5000;
    private final int NETHER_PORTAL_CHECK_RADIUS; // Radius for checking nearby blocks for portals when lighting
    private final int TELEPORT_PORTAL_SEARCH_RADIUS; // Increased radius for finding portal block after teleportation\

    public GameListener(Speedrun plugin) {
        this.plugin = plugin;

        this.NETHER_PORTAL_CHECK_RADIUS = plugin.getConfigManager().getNetherPortalCheckRadius();
        this.TELEPORT_PORTAL_SEARCH_RADIUS = plugin.getConfigManager().getTeleportPortalSearchRadius();
    }

    // --- Game State Events ---
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
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
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.getConfigManager().isPlayerScalingEnabled()) {
            int playerCount = Math.max(1, Bukkit.getOnlinePlayers().size() - 1);
            double multiplier = plugin.getConfigManager().getPlayerScalingMultiplier();
            plugin.getTaskManager().getAllTasks().forEach(task -> task.scale(playerCount, multiplier));
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntityType() == EntityType.ENDER_DRAGON) {
            plugin.getGameManager().stopRun(true);
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
        if (!(event.getEntity() instanceof Player)) return;
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE) {
            plugin.getTaskManager().trackItemPickup((Player) event.getEntity(), event.getItem().getItemStack());
            plugin.getTaskManager().updateItemTasks();
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (plugin.getConfigManager().getTrackingMode() == ConfigManager.TrackingMode.CUMULATIVE) {
            plugin.getTaskManager().trackItemCraft((Player) event.getWhoClicked(), event.getCurrentItem());
            plugin.getTaskManager().updateItemTasks();
        }
    }

    // --- Structure Finding Events ---
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Advancement adv = event.getAdvancement();
        String key = adv.getKey().getKey();
        Player player = event.getPlayer();

        if (key.equalsIgnoreCase("nether/find_fortress")) {
            plugin.getStructureManager().structureFound(player, "FORTRESS", player.getLocation());
        } else if (key.equalsIgnoreCase("nether/find_bastion")) {
            plugin.getStructureManager().structureFound(player, "BASTION", player.getLocation());
        } else if (key.equalsIgnoreCase("story/follow_ender_eye")) {
            plugin.getStructureManager().structureFound(player, "END_PORTAL", player.getLocation());
        }
    }

    /**
     * ИЗМЕНЕНО: Обрабатывает телепортацию в обе стороны для определения координат выхода.
     * Теперь пытается найти точный блок портала в месте назначения с увеличенным радиусом поиска.
     */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        World.Environment fromWorld = from.getWorld().getEnvironment();
        World.Environment toWorld = to.getWorld().getEnvironment();

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
}
