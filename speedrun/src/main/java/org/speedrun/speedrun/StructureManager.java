package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

public class StructureManager {
    private final Speedrun plugin;
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();
    private Location netherPortalExitLocation;
    private Location predictedEndPortalLocation;

    public StructureManager(Speedrun plugin) {
        this.plugin = plugin;
        reset();
    }

    public void reset() {
        foundLocations.clear();
        netherPortalExitLocation = null;
        predictedEndPortalLocation = null;
        foundLocations.put("LAVA_POOL", null);
        foundLocations.put("VILLAGE", null);
        foundLocations.put("NETHER_PORTAL", null);
        foundLocations.put("FORTRESS", null);
        foundLocations.put("BASTION", null);
        foundLocations.put("END_PORTAL", null);
    }

    public void structureFound(Player player, String key, Location loc) {
        // Prevent re-registration, unless it is a portal update.
        if (foundLocations.containsKey(key) && foundLocations.get(key) != null && !key.equals("NETHER_PORTAL") && !plugin.getConfigManager().isReassigningLocations()) {
            return;
        }

        foundLocations.put(key, loc);
        plugin.getTaskManager().onStructureFound(key, player);
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);

        String langKey = "structures." + key;
        String displayName = plugin.getConfigManager().getLangString(langKey, key);

        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                "%player%", player.getName(),
                "%structure%", displayName,
                "%coords%", LocationUtil.format(loc)));
    }

    public void portalLit(Player player, Location loc) {
        if (isNetherPortalLit()) return;

        foundLocations.put("NETHER_PORTAL", loc);
        plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD", player);
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", player.getName()));
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);
    }

    /**
     * FIXED: Correctly updates the coordinates of an existing structure.
     * @param naturalName The name of the structure as entered by the player (e.g., ‘Nether Portal’).
     * @param newLocation New coordinates.
     * @param player The player who executed the command.
     * @return true if the structure is found and updated.
     */
    public boolean updateStructureLocation(String naturalName, Location newLocation, Player player) {
        // Convert ‘Nether Portal’ to ‘NETHER_PORTAL’ for internal use
        String key = naturalName.replace(' ', '_').toUpperCase();

        if (!foundLocations.containsKey(key)) {
            return false;
        }

        // Special logic for the portal in Nezer
        if (key.equals("NETHER_PORTAL")) {
            // Reset the exit coordinates in Nezere so that they are redefined
            // when passing through the new portal for the first time.
            this.netherPortalExitLocation = null;
        }

        // Use the standard method to update coordinates and call events.
        structureFound(player, key, newLocation);
        return true;
    }

    public String getLocalizedStructureName(String key) {
        return plugin.getConfigManager().getLangString("structures." + key, key);
    }

    public void checkVillageTimeout() {
        if (isVillageSearchActive() && plugin.getGameManager().getVillageTimeRemaining() <= 0) {
            foundLocations.remove("VILLAGE");
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));
        }
    }

    public boolean isVillageSearchActive() { return foundLocations.containsKey("VILLAGE") && foundLocations.get("VILLAGE") == null; }
    public boolean isLavaPoolSearchActive() { return foundLocations.containsKey("LAVA_POOL") && foundLocations.get("LAVA_POOL") == null && !isNetherPortalLit(); }
    public boolean isNetherPortalLit() { return foundLocations.get("NETHER_PORTAL") != null; }
    public Map<String, Location> getFoundStructures() { return foundLocations; }
    public Location getNetherPortalExitLocation() { return netherPortalExitLocation; }
    public void setNetherPortalExitLocation(Location netherPortalExitLocation) { this.netherPortalExitLocation = netherPortalExitLocation; }
    public Location getPredictedEndPortalLocation() { return predictedEndPortalLocation; }
    public void setPredictedEndPortalLocation(Location location) { this.predictedEndPortalLocation = location; }
}