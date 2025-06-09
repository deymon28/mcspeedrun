package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action; // Import for PlayerInteractEvent
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerInteractEvent; // Import for PlayerInteractEvent
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.entity.EntityType;
import org.bukkit.Material; // Import Material for BELL

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpeedrunListener implements Listener {

    private final Speedrun plugin;

    // To prevent spamming structure checks on every move
    private final Map<UUID, Long> lastBellInteract = new ConcurrentHashMap<>(); // Changed name for clarity
    private static final long BELL_INTERACT_COOLDOWN_MS = 5000; // 5 seconds cooldown

    public SpeedrunListener(Speedrun plugin) {
        this.plugin = plugin;
    }

    /**
     * Event that fires when a player gains an advancement.
     * Used for detecting structures.
     *
     * @param event PlayerAdvancementDoneEvent event.
     */
    @EventHandler
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        NamespacedKey advancementKey = event.getAdvancement().getKey();

        // Track achievements related to structures
        if (advancementKey.equals(NamespacedKey.minecraft("nether/find_fortress"))) {
            plugin.structureFound(player, "Fortress", player.getLocation());
        } else if (advancementKey.equals(NamespacedKey.minecraft("nether/find_bastion"))) {
            plugin.structureFound(player, "Bastion", player.getLocation());
        }
        // Using "story/eye_spy" for End Portal detection as "enter_the_end" might be too late
        else if (advancementKey.equals(NamespacedKey.minecraft("story/follow_ender_eye"))) {
            plugin.structureFound(player, "End Portal", player.getLocation());
        }
    }

    /**
     * Event that fires when a player interacts with a block.
     * Used for detecting village via bell hit.
     *
     * @param event PlayerInteractEvent event.
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Ensure a block was clicked and it's a right-click or left-click on a block
        if (event.getClickedBlock() == null || (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK)) {
            return;
        }

        // Check if the interacted block is a bell
        if (event.getClickedBlock().getType() == Material.BELL) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            // Apply a cooldown to prevent spamming bell hits
            if (lastBellInteract.containsKey(playerUUID) && // Changed map name
                    currentTime - lastBellInteract.get(playerUUID) < BELL_INTERACT_COOLDOWN_MS) { // Changed map name
                return; // Cooldown not yet expired
            }

            // Check if the village search is currently active (timer for village is running)
            if (plugin.isVillageSearchActive()) {
                plugin.structureFound(player, "Village", event.getClickedBlock().getLocation());
                lastBellInteract.put(playerUUID, currentTime); // Reset cooldown
            }
        }
    }

    /**
     * Event that fires when an entity dies.
     * Used for detecting Ender Dragon kill.
     *
     * @param event EntityDeathEvent event.
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        // Check if the dead entity is the Ender Dragon
        if (event.getEntityType() == EntityType.ENDER_DRAGON) {
            plugin.dragonKilled(); // Notify the plugin about the dragon kill
        }
    }

    /**
     * Fires when a player uses a portal.
     * Used for tracking Nether Portal coordinates.
     *
     * @param event PlayerPortalEvent event.
     */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        // Check that it is a Nether Portal
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            // If the player is in the overworld, register the overworld portal
            if (event.getFrom().getWorld().getEnvironment() == World.Environment.NORMAL) {
                plugin.structureFound(player, "Nether P(Over)", event.getFrom());
            }
            // If the player is in the nether, register the nether portal
            else if (event.getFrom().getWorld().getEnvironment() == World.Environment.NETHER) {
                plugin.structureFound(player, "Nether P(Neth)", event.getFrom());
            }
        }
    }

    /**
     * Fires when a player closes their inventory.
     * Used to update item collection task progress.
     *
     * @param event InventoryCloseEvent event.
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Ensure the player is closing the inventory
        if (event.getPlayer() instanceof Player) {
            plugin.updateItemTasks(); // Update item task progress
            // Update scoreboard for all players so changes are immediately visible
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.updateScoreboard(player);
            }
        }
    }

}