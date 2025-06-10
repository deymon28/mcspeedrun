package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

public class StructureManager {
    private final Speedrun plugin;
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();
    private Location netherPortalExitLocation; // NEW: Store the Nether-side portal location

    public StructureManager(Speedrun plugin) {
        this.plugin = plugin;
        reset();
    }

    public void reset() {
        foundLocations.clear();
        netherPortalExitLocation = null; // Reset Nether portal exit
        // Initialize the map with structures to be tracked.
        foundLocations.put("Lava Pool", null); // New objective
        foundLocations.put("Village", null);
        foundLocations.put("Nether Portal", null);
        foundLocations.put("Fortress", null);
        foundLocations.put("Bastion", null);
        foundLocations.put("End Portal", null);
    }

    public void structureFound(Player player, String key, String displayName, Location loc) {
        if (foundLocations.containsKey(key) && foundLocations.get(key) != null) return;

        foundLocations.put(key, loc);
        String structureNameForMessage = displayName;

        switch (key) {
            case "Village":
                plugin.getTaskManager().onStructureFound("VILLAGE");
                break;
            case "End Portal":
                plugin.getTaskManager().onStructureFound("END_PORTAL");
                break;
            // Nether Portal case is handled by portalLit()
        }

        // Don't broadcast for Nether Portal here; portalLit has its own message.
        if (!key.equals("Nether Portal")) {
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                    "%player%", player.getName(),
                    "%structure%", structureNameForMessage,
                    "%coords%", LocationUtil.format(loc)));
        }
    }

    public void portalLit(Player player, Location loc) {
        if (isNetherPortalLit()) return;
        foundLocations.put("Nether Portal", loc);
        plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD");
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", player.getName()));
    }

    public boolean isVillageSearchActive() {
        return foundLocations.containsKey("Village") && foundLocations.get("Village") == null;
    }

    public boolean isLavaPoolSearchActive() {
        return foundLocations.containsKey("Lava Pool") && foundLocations.get("Lava Pool") == null && !isNetherPortalLit();
    }

    public boolean isNetherPortalLit() {
        return foundLocations.get("Nether Portal") != null;
    }

    public void checkVillageTimeout() {
        if (isVillageSearchActive() && plugin.getGameManager().getVillageTimeRemaining() <= 0) {
            foundLocations.remove("Village"); // Remove from tracking and display
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));
        }
    }

    public Map<String, Location> getFoundStructures() {
        return foundLocations;
    }

    // --- Getters and Setters for Nether Portal ---
    public Location getNetherPortalExitLocation() {
        return netherPortalExitLocation;
    }

    public void setNetherPortalExitLocation(Location netherPortalExitLocation) {
        this.netherPortalExitLocation = netherPortalExitLocation;
    }
}