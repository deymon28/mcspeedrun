package org.speedrun.speedrun;

import org.bukkit.Bukkit;
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
    private static final int NETHER_PORTAL_CHECK_RADIUS = 3;

    public GameListener(Speedrun plugin) {
        this.plugin = plugin;
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
     * Handles detecting the Nether-side of a portal after a player travels through it.
     */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        // We only care about the first time someone enters the Nether via a constructed portal
        if (plugin.getStructureManager().getNetherPortalExitLocation() == null &&
                event.getTo().getWorld().getEnvironment() == World.Environment.NETHER &&
                plugin.getStructureManager().isNetherPortalLit()) {

            plugin.getStructureManager().setNetherPortalExitLocation(event.getTo());
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
                plugin.getStructureManager().structureFound(player, "Village", event.getClickedBlock().getLocation());
                lastBellInteract.put(player.getUniqueId(), now);
            }
        }

        // Nether Portal Lighting
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getItem() != null && event.getItem().getType() == Material.FLINT_AND_STEEL) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null && clickedBlock.getType() == Material.OBSIDIAN) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        for (Block nearby : getNearbyBlocks(clickedBlock)) {
                            if (nearby.getType() == Material.NETHER_PORTAL) {
                                plugin.getStructureManager().portalLit(event.getPlayer(), clickedBlock.getLocation());
                                cancel();
                                return;
                            }
                        }
                    }
                }.runTaskLater(plugin, 1L);
            }
        }
    }

    private List<Block> getNearbyBlocks(Block start) {
        List<Block> blocks = new ArrayList<>();
        for (int x = -NETHER_PORTAL_CHECK_RADIUS; x <= NETHER_PORTAL_CHECK_RADIUS; x++) {
            for (int y = -NETHER_PORTAL_CHECK_RADIUS; y <= NETHER_PORTAL_CHECK_RADIUS; y++) {
                for (int z = -NETHER_PORTAL_CHECK_RADIUS; z <= NETHER_PORTAL_CHECK_RADIUS; z++) {
                    blocks.add(start.getRelative(x, y, z));
                }
            }
        }
        return blocks;
    }
}
