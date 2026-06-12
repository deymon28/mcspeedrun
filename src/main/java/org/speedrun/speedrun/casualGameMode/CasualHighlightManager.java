package org.speedrun.speedrun.casualGameMode;

import org.speedrun.speedrun.Speedrun;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.metadata.FixedMetadataValue;
import org.speedrun.speedrun.utils.PaperCheckUtil;
// No need for these specific imports if not using Paper's SearchResult methods
// import org.bukkit.generator.structure.Structure;
// import org.bukkit.generator.structure.StructureType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CasualHighlightManager implements Listener {

    private final Speedrun plugin;
    private BukkitTask highlightUpdateTask;

    // Map to store spawned glowing entities (Shulkers)
    // Key: Location of the gold block, Value: UUID of the spawned shulker
    private final Map<Location, UUID> glowingEntities = new ConcurrentHashMap<>();

    // Store known bastion heuristic bounding boxes
    // Key: Bastion's central location from detection, Value: The estimated BoundingBox
    private final Map<Location, BoundingBox> knownBastionBoundingBoxes = new ConcurrentHashMap<>();

    private final Set<Material> HIGHLIGHT_MATERIALS = Collections.singleton(Material.GOLD_BLOCK);
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    // Define a heuristic size for bastion bounding boxes (e.g., 64x64x64 blocks)
    // This is a cube of side length 64, centered around the detected bastion location.
    private static final double HEURISTIC_BASTION_SIZE_RADIUS = 32.0; // Half of 64, for BoundingBox.of(center, radiusX, radiusY, radiusZ)

    private static final long UPDATE_INTERVAL_TICKS = 20 * 3; // Update every 3 seconds
    private static final String HIGHLIGHT_METADATA_KEY = "SPEEDRUN_HIGHLIGHT_ENTITY";

    public CasualHighlightManager(Speedrun plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin); // Register self as listener
    }

    /**
     * Call this when a Bastion Remnant is detected (e.g., from your StructureFoundEvent).
     * This will create and store a heuristic bounding box for the bastion.
     *
     * @param bastionCenter The center location of the found bastion.
     * @param world The world the bastion is in.
     */
    public void addDetectedBastion(Location bastionCenter, World world) {
        if (!knownBastionBoundingBoxes.containsKey(bastionCenter)) {
            // Create a heuristic bounding box around the detected center.
            // BoundingBox.of(center, sizeX, sizeY, sizeZ) creates a box with given size around center.
            // Or BoundingBox.of(loc1, loc2) for min/max corners.
            // Using BoundingBox.of(center, radiusX, radiusY, radiusZ) is good for centered boxes.
            BoundingBox heuristicBox = BoundingBox.of(bastionCenter, HEURISTIC_BASTION_SIZE_RADIUS, HEURISTIC_BASTION_SIZE_RADIUS, HEURISTIC_BASTION_SIZE_RADIUS);

            knownBastionBoundingBoxes.put(bastionCenter, heuristicBox);
            plugin.getLogger().info("Registered Bastion Remnant (heuristic box) at " + bastionCenter.toVector() + " with estimated bounding box: " + heuristicBox);

            // Trigger an immediate scan for this new bastion's gold blocks
            // This is run on the main thread because updateHighlighting involves Bukkit API calls
            Bukkit.getScheduler().runTask(plugin, this::updateHighlighting);
        }
    }


    /**
     * Starts the periodic task to update highlighting for known bastions.
     */
    public void startHighlightingUpdateTask() {
        if (highlightUpdateTask != null && !highlightUpdateTask.isCancelled()) {
            plugin.getLogger().warning("Highlight update task already running.");
            return;
        }

        plugin.getLogger().info("Starting casual highlight update task.");

        highlightUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateHighlighting();
            }
        }.runTaskTimer(plugin, 0L, UPDATE_INTERVAL_TICKS); // Start immediately, repeat every interval
    }

    private void updateHighlighting() {
        if (PaperCheckUtil.IsPaper()) {
            updateHighlightingAsync();
            return;
        }

        updateHighlightingSync();
    }

    private void updateHighlightingSync() {
        // This set will contain all gold block locations that *should* be glowing in this tick
        Set<Location> goldBlocksToHighlightThisTick = new HashSet<>();

        // Iterate through all known bastions
        for (Map.Entry<Location, BoundingBox> entry : knownBastionBoundingBoxes.entrySet()) {
            Location bastionCenter = entry.getKey();
            BoundingBox heuristicBastionBox = entry.getValue();
            World world = bastionCenter.getWorld();

            if (world == null) continue; // Skip if world is no longer loaded

            // Check if any player is near this bastion's heuristic box
            boolean playerNearOrInBastion = false;
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Check if player's location is within the heuristic bounding box
                if (heuristicBastionBox.contains(player.getLocation().toVector())) {
                    playerNearOrInBastion = true;
                    break; // Found a player, no need to check other players for this bastion
                }
            }

            // Only process this bastion if a player is nearby and its main chunk is loaded
            // Checking one chunk is a simplification; a full check would be needed for larger boxes.
            if (playerNearOrInBastion && world.isChunkLoaded((int)heuristicBastionBox.getMinX() >> 4, (int)heuristicBastionBox.getMinZ() >> 4)) {
                // Iterate blocks within the heuristic bounding box
                for (int x = (int) heuristicBastionBox.getMinX(); x <= (int) heuristicBastionBox.getMaxX(); x++) {
                    for (int y = (int) heuristicBastionBox.getMinY(); y <= (int) heuristicBastionBox.getMaxY(); y++) {
                        for (int z = (int) heuristicBastionBox.getMinZ(); z <= (int) heuristicBastionBox.getMaxZ(); z++) {
                            Location blockLoc = new Location(world, x, y, z);
                            Block block = blockLoc.getBlock();
                            if (HIGHLIGHT_MATERIALS.contains(block.getType())) {
                                goldBlocksToHighlightThisTick.add(blockLoc);
                                if (!glowingEntities.containsKey(blockLoc)) {
                                    // If not already glowing, spawn a shulker
                                    spawnGlowingEntity(blockLoc);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (plugin.getConfigManager().isNetherGoldHighlightEnabled()) {
            addPlayerRelativeNetherGoldBlocks(goldBlocksToHighlightThisTick);
        }

        // Clean up entities for gold blocks that should no longer be highlighted
        // This handles blocks that are broken, or if a player moves away from a bastion
        // and it's no longer being actively scanned this tick.
        glowingEntities.entrySet().removeIf(entry -> {
            Location loc = entry.getKey();
            UUID entityId = entry.getValue();
            org.bukkit.entity.Entity entity = Bukkit.getEntity(entityId);

            // If the block is not in the set of blocks that should be highlighted this tick,
            // or its type changed, or the entity disappeared, remove it.
            if (!goldBlocksToHighlightThisTick.contains(loc) || loc.getBlock().getType() != Material.GOLD_BLOCK || entity == null) {
                if (entity != null) {
                    entity.remove();
                    plugin.getLogger().fine("Removed glowing entity at " + loc.toVector() + " (block broken or no longer in active bastion scan).");
                }
                return true; // Remove from map
            }
            return false; // Keep this one
        });
    }

    private void updateHighlightingAsync() {
        if (!scanInProgress.compareAndSet(false, true)) {
            return;
        }

        Map<World, Set<ChunkSnapshot>> snapshotsByWorld = new ConcurrentHashMap<>();
        Map<Location, BoundingBox> activeBoxes = new ConcurrentHashMap<>();

        for (Map.Entry<Location, BoundingBox> entry : knownBastionBoundingBoxes.entrySet()) {
            Location bastionCenter = entry.getKey();
            BoundingBox heuristicBastionBox = entry.getValue();
            World world = bastionCenter.getWorld();

            if (world == null || !isAnyPlayerInside(heuristicBastionBox)) {
                continue;
            }

            activeBoxes.put(bastionCenter, heuristicBastionBox);
            int minChunkX = ((int) Math.floor(heuristicBastionBox.getMinX())) >> 4;
            int maxChunkX = ((int) Math.floor(heuristicBastionBox.getMaxX())) >> 4;
            int minChunkZ = ((int) Math.floor(heuristicBastionBox.getMinZ())) >> 4;
            int maxChunkZ = ((int) Math.floor(heuristicBastionBox.getMaxZ())) >> 4;

            Set<ChunkSnapshot> snapshots = snapshotsByWorld.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet());
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    snapshots.add(chunk.getChunkSnapshot(false, false, false));
                }
            }
        }

        if (plugin.getConfigManager().isNetherGoldHighlightEnabled()) {
            int radius = plugin.getConfigManager().getNetherGoldHighlightRadius();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().getEnvironment() != World.Environment.NETHER) {
                    continue;
                }

                Location playerLocation = player.getLocation();
                BoundingBox playerBox = BoundingBox.of(playerLocation, radius, radius, radius);
                activeBoxes.put(playerLocation.clone(), playerBox);
                collectLoadedChunkSnapshots(player.getWorld(), playerBox, snapshotsByWorld);
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Set<Location> foundGoldBlocks = scanSnapshotsForGold(activeBoxes, snapshotsByWorld);
                Bukkit.getScheduler().runTask(plugin, () -> applyHighlightedGoldBlocks(foundGoldBlocks));
            } finally {
                scanInProgress.set(false);
            }
        });
    }

    private Set<Location> scanSnapshotsForGold(Map<Location, BoundingBox> activeBoxes, Map<World, Set<ChunkSnapshot>> snapshotsByWorld) {
        Set<Location> result = new HashSet<>();

        for (Map.Entry<Location, BoundingBox> boxEntry : activeBoxes.entrySet()) {
            World world = boxEntry.getKey().getWorld();
            BoundingBox box = boxEntry.getValue();
            if (world == null) {
                continue;
            }

            Set<ChunkSnapshot> snapshots = snapshotsByWorld.getOrDefault(world, Collections.emptySet());
            int minY = Math.max(world.getMinHeight(), (int) Math.floor(box.getMinY()));
            int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(box.getMaxY()));

            for (ChunkSnapshot snapshot : snapshots) {
                int baseX = snapshot.getX() << 4;
                int baseZ = snapshot.getZ() << 4;
                int minX = Math.max((int) Math.floor(box.getMinX()), baseX);
                int maxX = Math.min((int) Math.floor(box.getMaxX()), baseX + 15);
                int minZ = Math.max((int) Math.floor(box.getMinZ()), baseZ);
                int maxZ = Math.min((int) Math.floor(box.getMaxZ()), baseZ + 15);

                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            if (HIGHLIGHT_MATERIALS.contains(snapshot.getBlockType(x & 15, y, z & 15))) {
                                result.add(new Location(world, x, y, z));
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    private void collectLoadedChunkSnapshots(World world, BoundingBox box, Map<World, Set<ChunkSnapshot>> snapshotsByWorld) {
        int minChunkX = ((int) Math.floor(box.getMinX())) >> 4;
        int maxChunkX = ((int) Math.floor(box.getMaxX())) >> 4;
        int minChunkZ = ((int) Math.floor(box.getMinZ())) >> 4;
        int maxChunkZ = ((int) Math.floor(box.getMaxZ())) >> 4;

        Set<ChunkSnapshot> snapshots = snapshotsByWorld.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet());
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                snapshots.add(chunk.getChunkSnapshot(false, false, false));
            }
        }
    }

    private void addPlayerRelativeNetherGoldBlocks(Set<Location> goldBlocksToHighlightThisTick) {
        int radius = plugin.getConfigManager().getNetherGoldHighlightRadius();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() != World.Environment.NETHER) {
                continue;
            }

            Location center = player.getLocation();
            World world = center.getWorld();
            if (world == null) {
                continue;
            }

            int minY = Math.max(world.getMinHeight(), center.getBlockY() - radius);
            int maxY = Math.min(world.getMaxHeight() - 1, center.getBlockY() + radius);

            for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            continue;
                        }
                        Location blockLoc = new Location(world, x, y, z);
                        if (HIGHLIGHT_MATERIALS.contains(blockLoc.getBlock().getType())) {
                            goldBlocksToHighlightThisTick.add(blockLoc);
                            if (!glowingEntities.containsKey(blockLoc)) {
                                spawnGlowingEntity(blockLoc);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isAnyPlayerInside(BoundingBox box) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (box.contains(player.getLocation().toVector())) {
                return true;
            }
        }
        return false;
    }

    private void applyHighlightedGoldBlocks(Set<Location> goldBlocksToHighlightThisTick) {
        for (Location blockLoc : goldBlocksToHighlightThisTick) {
            if (!glowingEntities.containsKey(blockLoc) && blockLoc.getBlock().getType() == Material.GOLD_BLOCK) {
                spawnGlowingEntity(blockLoc);
            }
        }

        glowingEntities.entrySet().removeIf(entry -> {
            Location loc = entry.getKey();
            UUID entityId = entry.getValue();
            org.bukkit.entity.Entity entity = Bukkit.getEntity(entityId);

            if (!goldBlocksToHighlightThisTick.contains(loc) || loc.getBlock().getType() != Material.GOLD_BLOCK || entity == null) {
                if (entity != null) {
                    entity.remove();
                    plugin.getLogger().fine("Removed glowing entity at " + loc.toVector() + " (block broken or no longer in active bastion scan).");
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Spawns an invisible Shulker with the glowing effect at the given location.
     */
    private void spawnGlowingEntity(Location loc) {
        World world = loc.getWorld();
        // Check if the world is valid and the chunk where the block is located is loaded
        if (world == null || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            plugin.getLogger().warning("Cannot spawn glowing entity: world is null or chunk not loaded for location: " + loc);
            return;
        }

        Location spawnLoc = loc.clone().add(0.5, 0, 0.5); // Center the shulker within the block

        try {
            Shulker shulker = (Shulker) world.spawnEntity(spawnLoc, EntityType.SHULKER);
            shulker.setSilent(true);
            shulker.setAI(false);
            shulker.setGravity(false);
            shulker.setInvulnerable(true);
            shulker.setAware(false);
            shulker.setPersistent(false);
            shulker.setNoDamageTicks(Integer.MAX_VALUE);
            shulker.setCollidable(false);
            shulker.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false));
            shulker.setInvisible(true);
            shulker.setMetadata(HIGHLIGHT_METADATA_KEY, new FixedMetadataValue(plugin, true));

            glowingEntities.put(loc, shulker.getUniqueId());
            plugin.getLogger().fine("Spawned glowing Shulker at " + loc.toVector() + " for block highlighting.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to spawn Shulker at " + loc.toVector() + ": " + e.getMessage());
        }
    }

    /**
     * Stops the highlighting task and removes all spawned glowing entities.
     */
    public void stopHighlightingTask() {
        if (highlightUpdateTask != null) {
            highlightUpdateTask.cancel();
            highlightUpdateTask = null;
            plugin.getLogger().info("Stopped casual highlight update task.");
        }

        // Remove all active shulkers
        glowingEntities.values().forEach(uuid -> {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        });
        glowingEntities.clear();
        knownBastionBoundingBoxes.clear(); // Clear known bastions on disable
        plugin.getLogger().info("Removed all temporary glowing entities and cleared known bastions.");
    }
    public void reset() {
        // First, stop the current task and clear data
        stopHighlightingTask();

        // Then, immediately restart the task
        startHighlightingUpdateTask();
        plugin.getLogger().info("CasualHighlightManager reset complete: data cleared and task restarted.");
    }
    /**
     * Listens for block breaks to remove highlighting when gold blocks are mined.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block brokenBlock = event.getBlock();
        if (HIGHLIGHT_MATERIALS.contains(brokenBlock.getType())) {
            Location loc = brokenBlock.getLocation();
            UUID entityId = glowingEntities.remove(loc); // Remove from map
            if (entityId != null) {
                org.bukkit.entity.Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) {
                    entity.remove(); // Remove the shulker
                    plugin.getLogger().fine("Removed glowing entity for broken gold block at " + loc.toVector());
                }
            }
        }
    }
}
