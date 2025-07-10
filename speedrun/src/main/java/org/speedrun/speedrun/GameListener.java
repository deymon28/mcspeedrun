package org.speedrun.speedrun;

import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
     * Поиск блока портала в другом мире в радисе с конфига для точного позицуионирования координат
     */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!plugin.getStructureManager().isPortalPartiallyFound()) {
            return;
        }

        Location to = event.getTo();

        boolean isPaper = PaperCheckUtil.getIsPaper();

        if (isPaper) {
            // ПУТЬ ДЛЯ PAPER: Асинхронный и безопасный
            findPortalBlockAsync(to, TELEPORT_PORTAL_SEARCH_RADIUS)
                    .thenAccept(preciseExitLoc -> {
                        // Этот код выполнится быстро, как только чанки для игрока сгенерируются
                        Location finalLocation = (preciseExitLoc != null) ? preciseExitLoc : to;

                        // Используем Bukkit.getScheduler().runTask() для вызова вашей логики в основном потоке
                        plugin.getServer().getScheduler().runTask(plugin, () -> handlePortalLogic(event, finalLocation));
                    });
        } else {
            // ПУТЬ ДЛЯ BUKKIT/SPIGOT: Синхронный, может вызвать лаг
            Location preciseExitLoc = findPortalBlockSync(to, TELEPORT_PORTAL_SEARCH_RADIUS);
            Location finalLocation = (preciseExitLoc != null) ? preciseExitLoc : to;
            handlePortalLogic(event, finalLocation);
        }
    }

    /**
     * Вспомогательный метод, чтобы избежать дублирования кода
     */
    private void handlePortalLogic(PlayerPortalEvent event, Location finalLocation) {
        World.Environment fromWorld = event.getFrom().getWorld().getEnvironment();
        World.Environment toWorld = event.getTo().getWorld().getEnvironment();

        // Из Верхнего в Нижний
        if (fromWorld == World.Environment.NORMAL && toWorld == World.Environment.NETHER) {
            if (plugin.getStructureManager().getNetherPortalLocation() == null) {
                plugin.getStructureManager().portalExitFound(finalLocation);
            }
        }
        // Из Нижнего в Верхний
        else if (fromWorld == World.Environment.NETHER && toWorld == World.Environment.NORMAL) {
            if (plugin.getStructureManager().getOverworldPortalLocation() == null) {
                plugin.getStructureManager().portalExitFound(finalLocation);
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
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getItem() != null
                && (event.getItem().getType() == Material.FLINT_AND_STEEL
                || event.getItem().getType() == Material.FIRE_CHARGE)) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null) {
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

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        // Если огонь появился из-за распространения (не от игрока)
        if (event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD
                || event.getCause() == BlockIgniteEvent.IgniteCause.LAVA) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Block nearby : getNearbyBlocks(event.getBlock(), 3)) {
                    if (nearby.getType() == Material.NETHER_PORTAL) {
                        plugin.getStructureManager().portalLit(null, nearby.getLocation());
                        break;
                    }
                }
            }, 2L);
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
     * Синхронно ищет блок портала. Может вызывать лаги на основном потоке.
     * Используется как запасной вариант для Bukkit/Spigot.
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
     * Оптимизированно и асинхронно ищет ближайший блок портала.
     * Сначала загружает все необходимые чанки параллельно, а затем ищет блок.
     * Требует Paper API.
     *
     * @param centerLoc      Центральная локация для поиска (из event.getTo()).
     * @param searchRadius   Радиус поиска в блоках.
     * @return CompletableFuture с локацией блока портала или null, если не найден.
     */
    public CompletableFuture<Location> findPortalBlockAsync(Location centerLoc, int searchRadius) {
        if (centerLoc == null || centerLoc.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }

        World world = centerLoc.getWorld();
        int centerX = centerLoc.getBlockX();
        int centerY = centerLoc.getBlockY();
        int centerZ = centerLoc.getBlockZ();

        // Шаг 1: Определить все уникальные чанки в радиусе поиска
        Set<CompletableFuture<Chunk>> chunkFutures = new HashSet<>();
        for (int x = centerX - searchRadius; x <= centerX + searchRadius; x++) {
            for (int z = centerZ - searchRadius; z <= centerZ + searchRadius; z++) {
                // Асинхронно запрашиваем чанк. Запрос добавляется в `Set` для уникальности.
                chunkFutures.add(world.getChunkAtAsync(x >> 4, z >> 4));
            }
        }

        // Шаг 2: Создать один CompletableFuture, который завершится, когда ВСЕ чанки будут загружены
        return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                .thenApplyAsync(v -> {
                    // Шаг 3: Теперь, когда все чанки загружены, безопасно и быстро ищем портал
                    for (int x = -searchRadius; x <= searchRadius; x++) {
                        for (int y = -searchRadius; y <= searchRadius; y++) {
                            for (int z = -searchRadius; z <= searchRadius; z++) {
                                int checkX = centerX + x;
                                int checkY = centerY + y;
                                int checkZ = centerZ + z;

                                // Проверка getBlockAt теперь будет мгновенной, так как чанк уже в памяти
                                if (world.getBlockAt(checkX, checkY, checkZ).getType() == Material.NETHER_PORTAL) {
                                    return new Location(world, checkX, checkY, checkZ); // Найден!
                                }
                            }
                        }
                    }
                    return null; // Не найден
                });
    }
}
