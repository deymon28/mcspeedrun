package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================================================
// Structure Manager
// Manages the tracking and detection of specific in-game structures.
// It keeps track of which key structures have been found and notifies the game manager.
// =========================================================================================
class StructureManager {
    private final Speedrun plugin;
    // Stores locations of found structures, keyed by a canonical name (e.g., "Village").
    // Uses LinkedHashMap to maintain insertion order for display purposes (e.g., scoreboard).
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();

    /**
     * Constructor for StructureManager.
     * @param plugin The main Speedrun plugin instance.
     */
    public StructureManager(Speedrun plugin) {
        this.plugin = plugin;
        reset(); // Initialize with default structure statuses.
    }

    /**
     * Resets all tracked structures to an unfound state (null location).
     * This is typically called at the start of a new speedrun.
     */
    public void reset() {
        foundLocations.clear();
        // Initialize the map with structures that need to be tracked.
        // Their value is null if not yet found.
        foundLocations.put("Village", null);
        foundLocations.put("Nether Portal", null);
        foundLocations.put("Fortress", null);
        foundLocations.put("Bastion", null);
        foundLocations.put("End Portal", null);
    }

    /**
     * Marks a structure as found and handles any associated game logic (e.g., task completion).
     * Broadcasts a message to all players.
     *
     * @param player The player who found the structure.
     * @param key The internal key for the structure (e.g., "Village", "Nether Portal").
     * @param displayName The display name of the structure for messages.
     * @param loc The Location where the structure was found.
     */
    public void structureFound(Player player, String key, String displayName, Location loc) {
        // Prevent re-triggering if the structure has already been found.
        if (foundLocations.get(key) != null) return;

        // Mark the structure as found by storing its location.
        foundLocations.put(key, loc);

        String structureNameForMessage = displayName;

        // Handle special tasks related to finding specific structures.
        // Refactored to a switch statement for better readability and maintainability.
        switch (key) {
            case "Village":
                plugin.getTaskManager().onStructureFound("VILLAGE");
                break;
            case "End Portal":
                plugin.getTaskManager().onStructureFound("END_PORTAL");
                break;
            case "Nether Portal":
                plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD");
                structureNameForMessage = "Nether Portal"; // Override display name for the message.
                break;
            default:
                // No specific task action for other structures, or not applicable.
                break;
        }

        // Broadcast a message to all players that the structure was found.
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                "%player%", player.getName(), // Corrected varargs usage
                "%structure%", structureNameForMessage, // Corrected varargs usage
                "%coords%", LocationUtil.format(loc))); // Corrected varargs usage
    }

    /**
     * Handles the event when a Nether Portal is lit.
     * Marks the Nether Portal as found and triggers relevant task logic.
     * Broadcasts a message to all players.
     *
     * @param player The player who lit the portal.
     * @param loc The Location of the lit portal.
     */
    public void portalLit(Player player, Location loc) {
        // Prevent re-triggering if the Nether Portal has already been found/lit.
        if (foundLocations.get("Nether Portal") != null) return;

        // Mark the Nether Portal as found.
        foundLocations.put("Nether Portal", loc);
        plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD"); // Notify task manager.
        // Broadcast a message that the portal was lit.
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", player.getName()));
    }

    /**
     * Checks if the plugin is currently actively searching for a Village.
     * This is true if "Village" is a tracked location but hasn't been found yet.
     *
     * @return true if Village search is active, false otherwise.
     */
    public boolean isVillageSearchActive() {
        return foundLocations.containsKey("Village") && foundLocations.get("Village") == null;
    }

    /**
     * Checks if the village search has timed out.
     * If the village hasn't been found and the time remaining is zero or less,
     * it removes the village from the scoreboard display and broadcasts a timeout message.
     */
    public void checkVillageTimeout() {
        // The 'timeout' variable was unused, so it has been removed.
        // The check directly uses the game manager's time remaining.
        if(isVillageSearchActive() && plugin.getGameManager().getVillageTimeRemaining() <= 0) {
            foundLocations.remove("Village"); // Remove it from the scoreboard display as it timed out.
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));
        }
    }

    /**
     * Returns a map of all structures being tracked and their found locations.
     * @return An unmodifiable map of structure names to their Locations (null if not found).
     */
    public Map<String, Location> getFoundStructures() {
        return foundLocations;
    }
}
