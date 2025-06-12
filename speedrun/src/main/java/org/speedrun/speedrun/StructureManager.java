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

    public StructureManager(Speedrun plugin) {
        this.plugin = plugin;
        reset();
    }

    public void reset() {
        foundLocations.clear();
        netherPortalExitLocation = null;
        // Key is the internal key, value is the location (null if not found)
        foundLocations.put("LAVA_POOL", null);
        foundLocations.put("VILLAGE", null);
        foundLocations.put("NETHER_PORTAL", null);
        foundLocations.put("FORTRESS", null);
        foundLocations.put("BASTION", null);
        foundLocations.put("END_PORTAL", null);
    }

    public void structureFound(Player player, String key, Location loc) {
        if (foundLocations.containsKey(key) && foundLocations.get(key) != null) return;

        foundLocations.put(key, loc);
        plugin.getTaskManager().onStructureFound(key, player);

        // FIX: Get localized display name for broadcast message
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
    }

    public String getLocalizedStructureName(String key) {
        return plugin.getConfigManager().getLangString("structures." + key, key);
    }

    public boolean isVillageSearchActive() { return foundLocations.containsKey("VILLAGE") && foundLocations.get("VILLAGE") == null; }
    public boolean isLavaPoolSearchActive() { return foundLocations.containsKey("LAVA_POOL") && foundLocations.get("LAVA_POOL") == null && !isNetherPortalLit(); }
    public boolean isNetherPortalLit() { return foundLocations.get("NETHER_PORTAL") != null; }

    public void checkVillageTimeout() {
        if (isVillageSearchActive() && plugin.getGameManager().getVillageTimeRemaining() <= 0) {
            foundLocations.remove("VILLAGE");
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));
        }
    }

    public Map<String, Location> getFoundStructures() { return foundLocations; }
    public Location getNetherPortalExitLocation() { return netherPortalExitLocation; }
    public void setNetherPortalExitLocation(Location netherPortalExitLocation) { this.netherPortalExitLocation = netherPortalExitLocation; }
}