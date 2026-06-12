package org.speedrun.speedrun.casualGameMode;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.utils.RewardUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages configurable casual-mode structure waypoints.
 */
public class CasualModeStructureManager {
    private static final String WAYPOINT_METADATA = "indestructible";

    private final Speedrun plugin;
    private final Map<String, Waypoint> waypoints = new LinkedHashMap<>();
    private BukkitTask refreshTask;

    public CasualModeStructureManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    /**
     * Backward-compatible entry point for older callers.
     */
    public void createBeaconStructure(Location location, String structureKey) {
        trackStructureWaypoint(location, structureKey);
    }

    /**
     * Registers or updates a waypoint and starts periodic position refreshes.
     */
    public void trackStructureWaypoint(Location structureLocation, String structureKey) {
        if (structureLocation == null || structureLocation.getWorld() == null) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                Waypoint oldWaypoint = waypoints.remove(structureKey);
                if (oldWaypoint != null) {
                    restoreBlocks(oldWaypoint.blocks());
                }

                Waypoint waypoint = createWaypoint(structureLocation, structureKey);
                waypoints.put(structureKey, waypoint);
                startRefreshTask();
            }
        }.runTask(plugin);
    }

    /**
     * Removes all active waypoint blocks and stops the refresh task.
     */
    public void clearWaypoints() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }

        for (Waypoint waypoint : new ArrayList<>(waypoints.values())) {
            restoreBlocks(waypoint.blocks());
        }
        waypoints.clear();
    }

    private void startRefreshTask() {
        if (refreshTask != null && !refreshTask.isCancelled()) {
            return;
        }

        refreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (waypoints.isEmpty()) {
                    cancel();
                    refreshTask = null;
                    return;
                }

                for (Map.Entry<String, Waypoint> entry : new ArrayList<>(waypoints.entrySet())) {
                    Waypoint oldWaypoint = entry.getValue();
                    restoreBlocks(oldWaypoint.blocks());
                    waypoints.put(entry.getKey(), createWaypoint(oldWaypoint.structureLocation(), entry.getKey()));
                }
            }
        }.runTaskTimer(plugin,
                plugin.getConfigManager().getWaypointUpdateIntervalTicks(),
                plugin.getConfigManager().getWaypointUpdateIntervalTicks());
    }

    private Waypoint createWaypoint(Location structureLocation, String structureKey) {
        Location markerLocation = resolveMarkerLocation(structureLocation);
        List<MarkerBlock> markerBlocks = switch (plugin.getConfigManager().getWaypointType()) {
            case END_GATEWAY -> createEndGatewayWaypoint(markerLocation);
            case BEACON -> createBeaconWaypoint(markerLocation, structureKey);
        };

        RewardUtil.playWaypointSound(markerLocation);
        return new Waypoint(structureLocation.clone(), markerBlocks);
    }

    private List<MarkerBlock> createBeaconWaypoint(Location beaconLoc, String structureKey) {
        List<MarkerBlock> placedBlocks = new ArrayList<>();

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                placeMarkerBlock(beaconLoc.clone().add(x, -1, z), Material.IRON_BLOCK, placedBlocks);
            }
        }

        Block beaconBlock = placeMarkerBlock(beaconLoc, Material.BEACON, placedBlocks);
        placeMarkerBlock(beaconLoc.clone().add(0, 1, 0), getBeaconColor(structureKey), placedBlocks);

        if (beaconBlock.getState() instanceof Beacon beacon) {
            beacon.setPrimaryEffect(null);
            beacon.update(true);
        }

        return placedBlocks;
    }

    private List<MarkerBlock> createEndGatewayWaypoint(Location markerLoc) {
        List<MarkerBlock> placedBlocks = new ArrayList<>();
        placeMarkerBlock(markerLoc, Material.END_GATEWAY, placedBlocks);
        return placedBlocks;
    }

    private Block placeMarkerBlock(Location location, Material material, List<MarkerBlock> placedBlocks) {
        Block block = location.getBlock();
        placedBlocks.add(new MarkerBlock(block.getLocation(), block.getType()));
        block.setType(material, false);
        block.setMetadata(WAYPOINT_METADATA, new FixedMetadataValue(plugin, true));
        return block;
    }

    private void restoreBlocks(List<MarkerBlock> markerBlocks) {
        for (MarkerBlock markerBlock : markerBlocks) {
            Location location = markerBlock.location();
            if (location.getWorld() == null) {
                continue;
            }

            Block block = location.getBlock();
            if (block.hasMetadata(WAYPOINT_METADATA)) {
                block.removeMetadata(WAYPOINT_METADATA, plugin);
            }
            block.setType(markerBlock.previousType(), false);
        }
    }

    private Location resolveMarkerLocation(Location structureLocation) {
        int offset = plugin.getConfigManager().getWaypointOffsetBlocks();
        Location markerLoc = structureLocation.clone().add(0.5, 0, 0.5 + offset);
        markerLoc.setY(markerLoc.getWorld().getHighestBlockYAt(markerLoc) + 1);
        return markerLoc;
    }

    private Material getBeaconColor(String structureKey) {
        return switch (structureKey) {
            case "VILLAGE" -> Material.GREEN_STAINED_GLASS;
            case "LAVA_POOL" -> Material.RED_STAINED_GLASS;
            case "FORTRESS" -> Material.PURPLE_STAINED_GLASS;
            case "BASTION" -> Material.YELLOW_STAINED_GLASS;
            case "END_PORTAL" -> Material.LIGHT_BLUE_STAINED_GLASS;
            default -> Material.ORANGE_STAINED_GLASS;
        };
    }

    private record Waypoint(Location structureLocation, List<MarkerBlock> blocks) {
    }

    private record MarkerBlock(Location location, Material previousType) {
    }
}
