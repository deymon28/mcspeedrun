package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
        foundLocations.put(key, loc);
        plugin.getGameManager().getLogger().info("Structure '" + key + "' found/updated by " + player.getName() + " at " + LocationUtil.format(loc));
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
        // Only called when ignited in the Overworld
        if (isNetherPortalLit() && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage("§cRe-assigning the Nether Portal is disabled in the config.");
            return;
        }

        foundLocations.put("NETHER_PORTAL", loc);
        this.netherPortalExitLocation = null; // reset the output because created a new portal.
        plugin.getGameManager().getLogger().info("Nether Portal lit by " + player.getName() + " at " + LocationUtil.format(loc));
        plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD", player);
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", player.getName()));
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);
    }

    public boolean updateStructureLocation(String naturalName, Location newLocation, Player player) {
        String key = naturalName.replace(' ', '_').toUpperCase();
        if (!foundLocations.containsKey(key)) {
            player.sendMessage("§cUnknown structure name: " + naturalName);
            return false;
        }

        boolean isAlreadyFound = foundLocations.get(key) != null;

        //Checking whether coordinates can be changed
        if (isAlreadyFound && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage("§cRe-assigning locations is disabled in the config.");
            return false;
        }

        // Correct processing of the portal depending on the world
        if (key.equals("NETHER_PORTAL")) {
            if (player.getWorld().getEnvironment() == World.Environment.NETHER) {
                // Player in Nether -> only update exit coordinates
                setNetherPortalExitLocation(newLocation);
                plugin.getGameManager().getLogger().info("Nether Portal (Nether side) location updated via command by " + player.getName() + " to " + LocationUtil.format(newLocation));
                Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
                player.sendMessage("§aNether-side portal location updated.");
                return true;
            } else {
                // Player in Overworld -> reset output
                this.netherPortalExitLocation = null;
            }
        }

        structureFound(player, key, newLocation);
        return true;
    }

    public String getLocalizedStructureName(String key) { return plugin.getConfigManager().getLangString("structures." + key, key); }
    public void checkVillageTimeout() { if (isVillageSearchActive() && plugin.getGameManager().getVillageTimeRemaining() <= 0) { foundLocations.remove("VILLAGE"); Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout")); } }
    public boolean isVillageSearchActive() { return foundLocations.containsKey("VILLAGE") && foundLocations.get("VILLAGE") == null; }
    public boolean isLavaPoolSearchActive() { return foundLocations.containsKey("LAVA_POOL") && foundLocations.get("LAVA_POOL") == null && !isNetherPortalLit(); }
    public boolean isNetherPortalLit() { return foundLocations.get("NETHER_PORTAL") != null; }
    public Map<String, Location> getFoundStructures() { return foundLocations; }
    public Location getNetherPortalExitLocation() { return netherPortalExitLocation; }
    public void setNetherPortalExitLocation(Location netherPortalExitLocation) { this.netherPortalExitLocation = netherPortalExitLocation; }
    public Location getPredictedEndPortalLocation() { return predictedEndPortalLocation; }
    public void setPredictedEndPortalLocation(Location location) { this.predictedEndPortalLocation = location; }
}