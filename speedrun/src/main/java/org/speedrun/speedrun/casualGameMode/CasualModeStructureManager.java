package org.speedrun.speedrun.casualGameMode;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.utils.RewardUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Manages configurable casual-mode structure waypoints.
 */
public class CasualModeStructureManager {
    private static final String WAYPOINT_METADATA = "indestructible";
    private static final int SAFE_BEACON_SEARCH_RADIUS = 6;
    private static final Set<Material> SAFE_REPLACEABLE_BLOCKS = EnumSet.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.SHORT_GRASS,
            Material.TALL_GRASS,
            Material.FERN,
            Material.LARGE_FERN,
            Material.SNOW
    );

    private final Speedrun plugin;
    private final Map<String, Waypoint> waypoints = new LinkedHashMap<>();
    private BukkitTask refreshTask;
    /** Incremented by clearWaypoints() so waypoint work queued before a stop or reset is dropped. */
    private long generation;

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
        // Light beams are an Overworld feature: in the Nether the highest block is the bedrock
        // roof, so a marker would float above the ceiling where nobody below can see it.
        if (structureLocation.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return;
        }

        long expectedGeneration = generation;
        new BukkitRunnable() {
            @Override
            public void run() {
                placeWhenLoaded(structureLocation, structureKey, expectedGeneration, true);
            }
        }.runTask(plugin);
    }

    /**
     * Places the waypoint once the chunks around its marker are loaded. Missing chunks are
     * requested through the platform queue instead of being loaded on the server thread here.
     */
    private void placeWhenLoaded(Location structureLocation, String structureKey, long expectedGeneration,
                                 boolean mayLoadChunks) {
        if (expectedGeneration != generation || !isRunActive()) {
            return;
        }
        List<long[]> missing = missingChunks(structureLocation);
        if (!missing.isEmpty()) {
            if (!mayLoadChunks || plugin.getPlatform() == null) {
                return;
            }
            World world = structureLocation.getWorld();
            CompletableFuture<?>[] loads = missing.stream()
                    .map(chunk -> plugin.getPlatform().loadChunk(world, (int) chunk[0], (int) chunk[1]))
                    .toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(loads).whenComplete((ignored, error) -> plugin.getPlatform().executeSync(() -> {
                if (error == null) {
                    placeWhenLoaded(structureLocation, structureKey, expectedGeneration, false);
                }
            }));
            return;
        }

        Waypoint oldWaypoint = waypoints.remove(structureKey);
        if (oldWaypoint != null) {
            restoreBlocks(oldWaypoint.blocks());
        }
        waypoints.put(structureKey, createWaypoint(structureLocation, structureKey, true));
        startRefreshTask();
    }

    private boolean isRunActive() {
        return plugin.getGameManager() == null || plugin.getGameManager().isRunning();
    }

    /** Chunks (x, z) around a marker that are not loaded; the search area of beacon footprints included. */
    private List<long[]> missingChunks(Location structureLocation) {
        World world = structureLocation.getWorld();
        int markerX = structureLocation.getBlockX();
        int markerZ = structureLocation.getBlockZ() + plugin.getConfigManager().getWaypointOffsetBlocks();
        int reach = SAFE_BEACON_SEARCH_RADIUS + 1;
        List<long[]> missing = new ArrayList<>();
        for (int chunkX = (markerX - reach) >> 4; chunkX <= (markerX + reach) >> 4; chunkX++) {
            for (int chunkZ = (markerZ - reach) >> 4; chunkZ <= (markerZ + reach) >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    missing.add(new long[]{chunkX, chunkZ});
                }
            }
        }
        return missing;
    }

    /**
     * Removes all active waypoint blocks and stops the refresh task.
     */
    public void clearWaypoints() {
        generation++;
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
                    if (!missingChunks(oldWaypoint.structureLocation()).isEmpty()) {
                        continue; // nobody is near; refresh once the area is loaded again
                    }
                    restoreBlocks(oldWaypoint.blocks());
                    // Refreshes follow terrain changes silently; the sound plays only on creation.
                    waypoints.put(entry.getKey(), createWaypoint(oldWaypoint.structureLocation(), entry.getKey(), false));
                }
            }
        }.runTaskTimer(plugin,
                plugin.getConfigManager().getWaypointUpdateIntervalTicks(),
                plugin.getConfigManager().getWaypointUpdateIntervalTicks());
    }

    private Waypoint createWaypoint(Location structureLocation, String structureKey, boolean announce) {
        Location markerLocation = resolveMarkerLocation(structureLocation);
        List<MarkerBlock> markerBlocks = switch (plugin.getConfigManager().getWaypointType()) {
            case END_GATEWAY -> createEndGatewayWaypoint(markerLocation);
            case BEACON -> createBeaconWaypoint(markerLocation, structureKey);
        };

        if (announce) {
            RewardUtil.playWaypointSound(markerLocation);
        }
        return new Waypoint(structureLocation.clone(), markerBlocks);
    }

    private List<MarkerBlock> createBeaconWaypoint(Location beaconLoc, String structureKey) {
        List<MarkerBlock> placedBlocks = new ArrayList<>();
        Location placementLocation = findSafeBeaconLocation(beaconLoc);
        if (placementLocation == null) {
            plugin.getLogger().warning("No safe beacon waypoint footprint found near "
                    + formatBlockLocation(beaconLoc)
                    + "; placing waypoint with legacy overwrite behavior.");
            placementLocation = beaconLoc;
        }

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                placeMarkerBlock(placementLocation.clone().add(x, -1, z), Material.IRON_BLOCK, placedBlocks);
            }
        }

        Block beaconBlock = placeMarkerBlock(placementLocation, Material.BEACON, placedBlocks);
        placeMarkerBlock(placementLocation.clone().add(0, 1, 0), getBeaconColor(structureKey), placedBlocks);

        if (beaconBlock.getState() instanceof Beacon beacon) {
            beacon.setPrimaryEffect(null);
            beacon.update(true);
        }

        return placedBlocks;
    }

    private Location findSafeBeaconLocation(Location preferredLocation) {
        Location preferredSafeLocation = resolveSafeBeaconLocation(preferredLocation);
        if (isSafeBeaconFootprint(preferredSafeLocation)) {
            return preferredSafeLocation;
        }

        for (int radius = 1; radius <= SAFE_BEACON_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    Location candidate = preferredLocation.clone().add(dx, 0, dz);
                    candidate = resolveSafeBeaconLocation(candidate);
                    if (isSafeBeaconFootprint(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private Location resolveSafeBeaconLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return location;
        }

        Location safeLocation = location.clone();
        // Safe placement floats the 3x3 base above the surface, avoiding terrain/building replacement.
        safeLocation.setY(safeLocation.getWorld().getHighestBlockYAt(safeLocation) + 2);
        return safeLocation;
    }

    private boolean isSafeBeaconFootprint(Location beaconLoc) {
        if (beaconLoc == null || beaconLoc.getWorld() == null) {
            return false;
        }

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (!canReplaceForWaypoint(beaconLoc.clone().add(x, -1, z).getBlock())) {
                    return false;
                }
            }
        }

        return canReplaceForWaypoint(beaconLoc.getBlock())
                && canReplaceForWaypoint(beaconLoc.clone().add(0, 1, 0).getBlock());
    }

    private boolean canReplaceForWaypoint(Block block) {
        // Blocks of another waypoint are never taken over; restoring both would leave debris.
        return !block.hasMetadata(WAYPOINT_METADATA) && SAFE_REPLACEABLE_BLOCKS.contains(block.getType());
    }

    private List<MarkerBlock> createEndGatewayWaypoint(Location markerLoc) {
        List<MarkerBlock> placedBlocks = new ArrayList<>();
        placeMarkerBlock(markerLoc, Material.END_GATEWAY, placedBlocks);
        return placedBlocks;
    }

    private Block placeMarkerBlock(Location location, Material material, List<MarkerBlock> placedBlocks) {
        Block block = location.getBlock();
        if (block.hasMetadata(WAYPOINT_METADATA) || block.getState() instanceof TileState) {
            return block; // belongs to another waypoint, or holds contents that a restore would lose
        }
        placedBlocks.add(new MarkerBlock(block.getLocation(), block.getBlockData()));
        if (plugin.getWorldChangeJournal() != null) {
            plugin.getWorldChangeJournal().record(block, material);
        }
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
            block.setBlockData(markerBlock.previousData(), false);
            if (plugin.getWorldChangeJournal() != null) {
                plugin.getWorldChangeJournal().forget(location);
            }
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

    private String formatBlockLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "unknown";
        }
        return location.getWorld().getName()
                + " "
                + location.getBlockX()
                + ", "
                + location.getBlockY()
                + ", "
                + location.getBlockZ();
    }

    private record Waypoint(Location structureLocation, List<MarkerBlock> blocks) {
    }

    private record MarkerBlock(Location location, BlockData previousData) {
    }
}
