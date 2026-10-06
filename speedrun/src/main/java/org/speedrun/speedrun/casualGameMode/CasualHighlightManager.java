package org.speedrun.speedrun.casualGameMode;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.speedrun.speedrun.Speedrun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Casual-mode gold block highlighting in bastions and around players in the Nether.
 *
 * <p>Every few seconds the manager captures snapshots of the loaded chunks inside the active
 * areas on the server thread, searches them for gold blocks on a worker thread, and then
 * spawns or removes glowing block displays on the server thread. Block displays have no
 * hitbox, so highlighted blocks can still be mined and nothing blocks movement. Displays
 * are non-persistent, so they are never saved with the world.</p>
 */
public class CasualHighlightManager implements Listener {
    private static final Material HIGHLIGHT_MATERIAL = Material.GOLD_BLOCK;
    private static final double HEURISTIC_BASTION_SIZE_RADIUS = 32.0;
    private static final long UPDATE_INTERVAL_TICKS = 20 * 3;
    /** Upper bound for simultaneous highlight entities, protecting clients in gold-heavy areas. */
    private static final int MAX_HIGHLIGHTS = 256;
    private static final String HIGHLIGHT_METADATA_KEY = "SPEEDRUN_HIGHLIGHT_ENTITY";
    private static final Color GLOW_COLOR = Color.fromRGB(255, 196, 0);

    private final Speedrun plugin;
    private final Map<BlockKey, UUID> glowingEntities = new HashMap<>();
    private final Map<Location, BoundingBox> knownBastionBoundingBoxes = new HashMap<>();
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private BukkitTask highlightUpdateTask;
    private long scanGeneration;

    public CasualHighlightManager(Speedrun plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Registers a detected Bastion Remnant with a heuristic bounding box around its location
     * and triggers an immediate scan.
     */
    public void addDetectedBastion(Location bastionCenter, World world) {
        if (bastionCenter == null || world == null || knownBastionBoundingBoxes.containsKey(bastionCenter)) {
            return;
        }
        BoundingBox heuristicBox = BoundingBox.of(bastionCenter,
                HEURISTIC_BASTION_SIZE_RADIUS, HEURISTIC_BASTION_SIZE_RADIUS, HEURISTIC_BASTION_SIZE_RADIUS);
        knownBastionBoundingBoxes.put(bastionCenter.clone(), heuristicBox);
        plugin.getTraceLogger().trace("structure", "bastion_highlight_area_registered",
                "center", bastionCenter, "box", heuristicBox.toString());
        Bukkit.getScheduler().runTask(plugin, this::updateHighlighting);
    }

    /** Starts the periodic highlight refresh. */
    public void startHighlightingUpdateTask() {
        if (highlightUpdateTask != null && !highlightUpdateTask.isCancelled()) {
            return;
        }
        highlightUpdateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateHighlighting, 0L, UPDATE_INTERVAL_TICKS);
    }

    private void updateHighlighting() {
        if (!scanInProgress.compareAndSet(false, true)) {
            return;
        }

        List<ScanArea> areas = new ArrayList<>();
        Map<UUID, Map<Long, ChunkSnapshot>> snapshotsByWorld = new HashMap<>();

        for (Map.Entry<Location, BoundingBox> entry : knownBastionBoundingBoxes.entrySet()) {
            World world = entry.getKey().getWorld();
            BoundingBox box = entry.getValue();
            if (world != null && isAnyPlayerInside(world, box)) {
                addArea(world, box, areas, snapshotsByWorld);
            }
        }

        if (plugin.getConfigManager().isNetherGoldHighlightEnabled()) {
            int radius = plugin.getConfigManager().getNetherGoldHighlightRadius();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().getEnvironment() == World.Environment.NETHER) {
                    addArea(player.getWorld(), BoundingBox.of(player.getLocation(), radius, radius, radius),
                            areas, snapshotsByWorld);
                }
            }
        }

        if (areas.isEmpty()) {
            applyHighlightedGoldBlocks(Set.of());
            scanInProgress.set(false);
            return;
        }

        long generation = scanGeneration;
        plugin.getPlatform().compute(() -> scanSnapshotsForGold(areas, snapshotsByWorld))
                .whenComplete((found, error) -> {
                    scanInProgress.set(false);
                    if (error != null || found == null) {
                        return;
                    }
                    plugin.getPlatform().executeSync(() -> {
                        if (generation == scanGeneration && highlightUpdateTask != null) {
                            applyHighlightedGoldBlocks(found);
                        }
                    });
                });
    }

    private void addArea(World world, BoundingBox box, List<ScanArea> areas,
                         Map<UUID, Map<Long, ChunkSnapshot>> snapshotsByWorld) {
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(box.getMinY()));
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(box.getMaxY()));
        areas.add(new ScanArea(world.getUID(), (int) Math.floor(box.getMinX()), minY, (int) Math.floor(box.getMinZ()),
                (int) Math.floor(box.getMaxX()), maxY, (int) Math.floor(box.getMaxZ())));

        Map<Long, ChunkSnapshot> snapshots = snapshotsByWorld.computeIfAbsent(world.getUID(), ignored -> new HashMap<>());
        int minChunkX = ((int) Math.floor(box.getMinX())) >> 4;
        int maxChunkX = ((int) Math.floor(box.getMaxX())) >> 4;
        int minChunkZ = ((int) Math.floor(box.getMinZ())) >> 4;
        int maxChunkZ = ((int) Math.floor(box.getMaxZ())) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = chunkKey(chunkX, chunkZ);
                if (!snapshots.containsKey(key) && world.isChunkLoaded(chunkX, chunkZ)) {
                    snapshots.put(key, world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false));
                }
            }
        }
    }

    /** Worker-thread search over immutable snapshots. */
    static Set<BlockKey> scanSnapshotsForGold(List<ScanArea> areas, Map<UUID, Map<Long, ChunkSnapshot>> snapshotsByWorld) {
        Set<BlockKey> result = new HashSet<>();
        for (ScanArea area : areas) {
            Map<Long, ChunkSnapshot> snapshots = snapshotsByWorld.getOrDefault(area.world(), Map.of());
            for (int chunkX = area.minX() >> 4; chunkX <= area.maxX() >> 4; chunkX++) {
                for (int chunkZ = area.minZ() >> 4; chunkZ <= area.maxZ() >> 4; chunkZ++) {
                    ChunkSnapshot snapshot = snapshots.get(chunkKey(chunkX, chunkZ));
                    if (snapshot == null) {
                        continue;
                    }
                    int minX = Math.max(area.minX(), chunkX << 4);
                    int maxX = Math.min(area.maxX(), (chunkX << 4) + 15);
                    int minZ = Math.max(area.minZ(), chunkZ << 4);
                    int maxZ = Math.min(area.maxZ(), (chunkZ << 4) + 15);
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            for (int y = area.minY(); y <= area.maxY(); y++) {
                                if (snapshot.getBlockType(x & 15, y, z & 15) == HIGHLIGHT_MATERIAL) {
                                    result.add(new BlockKey(area.world(), x, y, z));
                                    if (result.size() >= MAX_HIGHLIGHTS) {
                                        return result;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean isAnyPlayerInside(World world, BoundingBox box) {
        for (Player player : world.getPlayers()) {
            if (box.contains(player.getLocation().toVector())) {
                return true;
            }
        }
        return false;
    }

    private void applyHighlightedGoldBlocks(Set<BlockKey> wanted) {
        Iterator<Map.Entry<BlockKey, UUID>> iterator = glowingEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, UUID> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getValue());
            Block block = entry.getKey().block();
            if (entity == null || block == null || !wanted.contains(entry.getKey()) || block.getType() != HIGHLIGHT_MATERIAL) {
                if (entity != null) {
                    entity.remove();
                }
                iterator.remove();
            }
        }

        for (BlockKey key : wanted) {
            if (glowingEntities.size() >= MAX_HIGHLIGHTS) {
                break;
            }
            if (!glowingEntities.containsKey(key)) {
                Block block = key.block();
                if (block != null && block.getType() == HIGHLIGHT_MATERIAL) {
                    spawnGlowingEntity(key, block);
                }
            }
        }
    }

    private void spawnGlowingEntity(BlockKey key, Block block) {
        World world = block.getWorld();
        if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        try {
            BlockDisplay display = world.spawn(block.getLocation(), BlockDisplay.class, entity -> {
                entity.setBlock(HIGHLIGHT_MATERIAL.createBlockData());
                entity.setPersistent(false);
                entity.setGlowing(true);
                entity.setGlowColorOverride(GLOW_COLOR);
                entity.setInvulnerable(true);
                entity.setSilent(true);
                entity.setGravity(false);
                // Slightly larger than the block to avoid z-fighting with its faces.
                entity.setTransformation(new Transformation(
                        new Vector3f(-0.002f, -0.002f, -0.002f), new AxisAngle4f(),
                        new Vector3f(1.004f, 1.004f, 1.004f), new AxisAngle4f()));
                entity.setMetadata(HIGHLIGHT_METADATA_KEY, new FixedMetadataValue(plugin, true));
            });
            glowingEntities.put(key, display.getUniqueId());
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Failed to spawn a gold highlight at " + block.getX() + " " + block.getY() + " "
                    + block.getZ() + ": " + failure.getClass().getSimpleName());
        }
    }

    /** Stops the refresh task and removes all highlight entities and known bastion areas. */
    public void stopHighlightingTask() {
        if (highlightUpdateTask != null) {
            highlightUpdateTask.cancel();
            highlightUpdateTask = null;
        }
        scanGeneration++;
        for (UUID uuid : glowingEntities.values()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        }
        glowingEntities.clear();
        knownBastionBoundingBoxes.clear();
    }

    public void reset() {
        stopHighlightingTask();
        startHighlightingUpdateTask();
    }

    /** Removes the highlight immediately when a highlighted gold block is mined. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block brokenBlock = event.getBlock();
        if (brokenBlock.getType() != HIGHLIGHT_MATERIAL) {
            return;
        }
        UUID entityId = glowingEntities.remove(BlockKey.of(brokenBlock));
        if (entityId != null) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    record ScanArea(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        /** The live block, or null when its world or chunk is not loaded (never loads chunks). */
        Block block() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null || !loaded.isChunkLoaded(x >> 4, z >> 4) ? null : loaded.getBlockAt(x, y, z);
        }
    }
}
