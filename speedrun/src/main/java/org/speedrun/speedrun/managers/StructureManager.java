package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.StructureSearchResult;
import org.speedrun.speedrun.utils.LocationUtil;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.utils.PaperCheckUtil;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.events.StructureFoundEvent;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Manages the detection, storage, and state of key structures.
 * This includes handling complex logic for Nether Portals, which have linked locations in two dimensions.
 * |
 * Керує виявленням, зберіганням та станом ключових структур.
 * Включає обробку складної логіки для порталів у Незер, які мають пов'язані локації у двох вимірах.
 */
public class StructureManager {
    private static final String HIDDEN_LODESTONE_METADATA = "speedrun_hidden_lodestone";
    private final Speedrun plugin;
    // Stores locations of all tracked structures. Using LinkedHashMap to maintain insertion order.
    // Зберігає локації всіх відстежуваних структур. Використовується LinkedHashMap для збереження порядку додавання.
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();
    private final Map<String, Location> hiddenLodestones = new LinkedHashMap<>();
    private final Map<String, Material> hiddenLodestonePreviousBlocks = new HashMap<>();
    private final Set<String> disabledSearches = new HashSet<>();
    private final Set<String> liveScannedChunks = new HashSet<>();
    private final Set<String> approximateStructures = new HashSet<>();
    private final Queue<SearchChunk> backgroundScanQueue = new ArrayDeque<>();
    private final Queue<LocateRequest> locateQueue = new ArrayDeque<>();
    private final Set<String> queuedBackgroundChunks = new HashSet<>();
    private final Set<String> completedBackgroundChunks = new HashSet<>();
    private BukkitTask preScanTask;
    private BukkitTask backgroundScanTask;
    private BukkitTask locateTask;
    private long preScanGeneration = 0;

    public boolean villageSearchFailed = false;

    // Separate locations for each side of the portal pair.
    // Окремі локації для кожної сторони пари порталів.
    private Location overworldPortalLocation;
    private Location netherPortalLocation;

    private Location predictedEndPortalLocation;
    private boolean predictedEndPortalApproximate;

    private final Set<String> hiddenStructures = new HashSet<>();

    public StructureManager(Speedrun plugin) {
        this.plugin = plugin;
        reset();
    }

    /**
     * Resets all found structure locations to their initial (null) state.
     * Called at the start of a new speedrun.
     * |
     * Скидає всі знайдені локації структур до початкового (null) стану.
     * Викликається на початку нового спідрану.
     */
    public void reset() {
        cancelPreScan();
        clearAllHiddenLodestones();
        foundLocations.clear();
        hiddenStructures.clear();
        disabledSearches.clear();
        liveScannedChunks.clear();
        approximateStructures.clear();
        backgroundScanQueue.clear();
        locateQueue.clear();
        queuedBackgroundChunks.clear();
        completedBackgroundChunks.clear();
        predictedEndPortalLocation = null;
        predictedEndPortalApproximate = false;
        overworldPortalLocation = null;
        netherPortalLocation = null;

        villageSearchFailed = false;

        // Initialize the map with all structures that need to be tracked.
        // Ініціалізуємо мапу всіма структурами, які потрібно відстежувати.
        foundLocations.put("LAVA_POOL", null);
        foundLocations.put("VILLAGE", null);
        foundLocations.put("NETHER_PORTAL", null); // This key is a placeholder for display purposes. / Цей ключ є плейсхолдером для відображення.
        foundLocations.put("FORTRESS", null);
        foundLocations.put("BASTION", null);
        foundLocations.put("END_PORTAL", null);
    }

    public void preScanRequiredStructures() {
        cancelPreScan();
        ConfigManager.StartPreScanMode mode = plugin.getConfigManager().getStartPreScanMode();
        plugin.getLogger().info("Casual start pre-scan mode=" + mode
                + (mode == ConfigManager.StartPreScanMode.LOCATE
                ? "; bounded locate API calls are enabled with loaded-chunk fallback."
                : "; blocking structure locate calls are disabled."));
        plugin.getTraceLogger().trace("scanner", "pre_scan_started",
                "mode", mode,
                "loaded_chunk_radius", plugin.getConfigManager().getLoadedChunkScanRadius(),
                "radius_chunks", plugin.getConfigManager().getStartPreScanRadiusChunks(),
                "chunks_per_run", plugin.getConfigManager().getStartPreScanChunksPerRun(),
                "load_missing_chunks", plugin.getConfigManager().shouldStartPreScanLoadMissingChunks(),
                "scan_spawn", plugin.getConfigManager().shouldStartPreScanQueueSpawn(),
                "scan_players", plugin.getConfigManager().shouldStartPreScanQueuePlayers(),
                "include_nether", plugin.getConfigManager().shouldStartPreScanIncludeNether());
        if (mode == ConfigManager.StartPreScanMode.LOCATE) {
            seedLocateQueue();
            startLocateTask();
        } else if (mode != ConfigManager.StartPreScanMode.SAFE) {
            seedBackgroundScanCenters();
            startBackgroundScanTask();
        }
        preScanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getGameManager().isRunning() || plugin.getGameManager().isPaused()) {
                return;
            }
            Bukkit.getOnlinePlayers().forEach(player -> {
                scanLoadedPlayerChunk(player);
                if (plugin.getConfigManager().shouldStartPreScanQueuePlayers()) {
                    queueBackgroundScanAround(player.getWorld(), player.getLocation(), true);
                }
            });
        }, 1L, 40L);
    }

    private void seedBackgroundScanCenters() {
        if (plugin.getConfigManager().shouldStartPreScanQueuePlayers()) {
            Bukkit.getOnlinePlayers().forEach(player -> queueBackgroundScanAround(player.getWorld(), player.getLocation(), true));
        }
        if (plugin.getConfigManager().shouldStartPreScanQueueSpawn()) {
            for (World world : Bukkit.getWorlds()) {
                if (shouldBackgroundScanWorld(world, false)) {
                    queueBackgroundScanAround(world, world.getSpawnLocation(), false);
                }
            }
        }
    }

    private void seedLocateQueue() {
        locateQueue.clear();
        World overworld = findWorldByEnvironment(World.Environment.NORMAL);
        if (overworld != null) {
            Location origin = overworld.getSpawnLocation();
            if (plugin.getConfigManager().shouldStartPreScanLocateVillages()) {
                locateQueue.add(new LocateRequest(overworld, origin, "VILLAGE", Structure.VILLAGE_PLAINS));
                locateQueue.add(new LocateRequest(overworld, origin, "VILLAGE", Structure.VILLAGE_DESERT));
                locateQueue.add(new LocateRequest(overworld, origin, "VILLAGE", Structure.VILLAGE_SAVANNA));
                locateQueue.add(new LocateRequest(overworld, origin, "VILLAGE", Structure.VILLAGE_SNOWY));
                locateQueue.add(new LocateRequest(overworld, origin, "VILLAGE", Structure.VILLAGE_TAIGA));
            }
            if (plugin.getConfigManager().shouldStartPreScanLocateStronghold()) {
                locateQueue.add(new LocateRequest(overworld, origin, "END_PORTAL", Structure.STRONGHOLD));
            }
        }

        World nether = findWorldByEnvironment(World.Environment.NETHER);
        if (nether != null && plugin.getConfigManager().shouldStartPreScanLocateNetherStructures()) {
            Location origin = nether.getSpawnLocation();
            locateQueue.add(new LocateRequest(nether, origin, "FORTRESS", Structure.FORTRESS));
            locateQueue.add(new LocateRequest(nether, origin, "BASTION", Structure.BASTION_REMNANT));
        }

        plugin.getTraceLogger().trace("scanner", "locate_queue_seeded",
                "queued_requests", locateQueue.size(),
                "radius_chunks", plugin.getConfigManager().getStartPreScanLocateRadius(),
                "find_unexplored", plugin.getConfigManager().shouldStartPreScanLocateFindUnexplored(),
                "period_ticks", plugin.getConfigManager().getStartPreScanLocatePeriodTicks(),
                "calls_per_run", plugin.getConfigManager().getStartPreScanLocateCallsPerRun());
    }

    private void startLocateTask() {
        if (locateTask != null && !locateTask.isCancelled()) {
            return;
        }

        locateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getGameManager().isRunning() || plugin.getGameManager().isPaused()) {
                return;
            }
            int budget = plugin.getConfigManager().getStartPreScanLocateCallsPerRun();
            for (int calls = 0; calls < budget; calls++) {
                LocateRequest request = locateQueue.poll();
                if (request == null) {
                    locateTask.cancel();
                    locateTask = null;
                    plugin.getTraceLogger().trace("scanner", "locate_queue_finished");
                    return;
                }
                processLocateRequest(request);
            }
        }, 1L, plugin.getConfigManager().getStartPreScanLocatePeriodTicks());
    }

    private void processLocateRequest(LocateRequest request) {
        if (request.world() == null || request.origin() == null || request.structure() == null) {
            return;
        }
        if (!isStructureSearchActive(request.structureKey())) {
            plugin.getTraceLogger().trace("scanner", "locate_request_skipped",
                    "structure_key", request.structureKey(),
                    "minecraft_structure", request.structure(),
                    "world", request.world(),
                    "reason", "search_inactive");
            return;
        }

        int radius = plugin.getConfigManager().getStartPreScanLocateRadius();
        boolean findUnexplored = plugin.getConfigManager().shouldStartPreScanLocateFindUnexplored();
        long started = System.currentTimeMillis();
        plugin.getTraceLogger().trace("scanner", "locate_request_started",
                "structure_key", request.structureKey(),
                "minecraft_structure", request.structure(),
                "world", request.world(),
                "origin", request.origin(),
                "radius_chunks", radius,
                "find_unexplored", findUnexplored);
        try {
            StructureSearchResult result = request.world().locateNearestStructure(
                    request.origin(), request.structure(), radius, findUnexplored);
            long duration = System.currentTimeMillis() - started;
            if (result == null) {
                plugin.getTraceLogger().trace("scanner", "locate_request_not_found",
                        "structure_key", request.structureKey(),
                        "minecraft_structure", request.structure(),
                        "world", request.world(),
                        "duration_ms", duration);
                return;
            }

            Location location = result.getLocation();
            plugin.getTraceLogger().trace("scanner", "locate_request_found",
                    "structure_key", request.structureKey(),
                    "minecraft_structure", request.structure(),
                    "world", request.world(),
                    "location", location,
                    "duration_ms", duration);
            structureFound(null, request.structureKey(), location, true);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Locate pre-scan failed for " + request.structureKey()
                    + " (" + request.structure() + "): " + ex.getMessage());
            plugin.getTraceLogger().trace("scanner", "locate_request_failed",
                    "structure_key", request.structureKey(),
                    "minecraft_structure", request.structure(),
                    "world", request.world(),
                    "duration_ms", System.currentTimeMillis() - started,
                    "error", ex.toString());
        }
    }

    private World findWorldByEnvironment(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return world;
            }
        }
        return null;
    }

    private boolean shouldBackgroundScanWorld(World world, boolean playerDriven) {
        if (world == null) {
            return false;
        }
        World.Environment environment = world.getEnvironment();
        if (environment == World.Environment.NORMAL) {
            return true;
        }
        return environment == World.Environment.NETHER
                && (playerDriven || plugin.getConfigManager().shouldStartPreScanIncludeNether());
    }

    private void queueBackgroundScanAround(World world, Location center, boolean playerDriven) {
        if (!shouldBackgroundScanWorld(world, playerDriven) || center == null) {
            plugin.getTraceLogger().trace("scanner", "queue_center_skipped",
                    "world", world,
                    "center", center,
                    "player_driven", playerDriven,
                    "reason", center == null ? "null_center" : "world_not_allowed");
            return;
        }

        int radiusChunks = plugin.getConfigManager().getStartPreScanRadiusChunks();
        int maxQueuedChunks = plugin.getConfigManager().getStartPreScanMaxQueuedChunks();
        if (maxQueuedChunks <= 0 || completedBackgroundChunks.size() + backgroundScanQueue.size() >= maxQueuedChunks) {
            plugin.getTraceLogger().trace("scanner", "queue_center_skipped",
                    "world", world,
                    "center", center,
                    "player_driven", playerDriven,
                    "reason", "queue_budget_full_or_disabled",
                    "max_queued_chunks", maxQueuedChunks,
                    "completed_chunks", completedBackgroundChunks.size(),
                    "queued_chunks", backgroundScanQueue.size());
            return;
        }

        int centerChunkX = center.getBlockX() >> 4;
        int centerChunkZ = center.getBlockZ() >> 4;
        int queued = 0;
        for (int radius = 0; radius <= radiusChunks; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    if (completedBackgroundChunks.size() + backgroundScanQueue.size() >= maxQueuedChunks) {
                        return;
                    }
                    SearchChunk chunk = new SearchChunk(world, centerChunkX + dx, centerChunkZ + dz, playerDriven);
                    String key = chunk.key();
                    if (completedBackgroundChunks.contains(key) || !queuedBackgroundChunks.add(key)) {
                        continue;
                    }
                    backgroundScanQueue.add(chunk);
                    queued++;
                }
            }
        }
        plugin.getTraceLogger().trace("scanner", "queue_center_added",
                "world", world,
                "center", center,
                "player_driven", playerDriven,
                "center_chunk_x", centerChunkX,
                "center_chunk_z", centerChunkZ,
                "queued_added", queued,
                "queued_total", backgroundScanQueue.size(),
                "completed_chunks", completedBackgroundChunks.size());
    }

    private void startBackgroundScanTask() {
        if (backgroundScanTask != null && !backgroundScanTask.isCancelled()) {
            return;
        }

        backgroundScanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getGameManager().isRunning() || plugin.getGameManager().isPaused()) {
                return;
            }
            int budget = plugin.getConfigManager().getStartPreScanChunksPerRun();
            for (int scanned = 0; scanned < budget; scanned++) {
                SearchChunk chunk = backgroundScanQueue.poll();
                if (chunk == null) {
                    return;
                }
                queuedBackgroundChunks.remove(chunk.key());
                processBackgroundChunk(chunk, preScanGeneration);
            }
        }, 1L, plugin.getConfigManager().getStartPreScanPeriodTicks());
    }

    private void processBackgroundChunk(SearchChunk searchChunk, long generation) {
        if (!completedBackgroundChunks.add(searchChunk.key())) {
            return;
        }

        World world = searchChunk.world();
        int chunkX = searchChunk.chunkX();
        int chunkZ = searchChunk.chunkZ();
        if (world == null || !shouldBackgroundScanWorld(world, searchChunk.playerDriven())) {
            plugin.getTraceLogger().trace("scanner", "background_chunk_skipped",
                    "chunk_key", searchChunk.key(),
                    "world", world,
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "player_driven", searchChunk.playerDriven(),
                    "reason", world == null ? "null_world" : "world_not_allowed");
            return;
        }

        if (world.isChunkLoaded(chunkX, chunkZ)) {
            plugin.getTraceLogger().trace("scanner", "background_chunk_scan_loaded",
                    "world", world,
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "player_driven", searchChunk.playerDriven());
            scanLoadedChunk(null, world, chunkX, chunkZ);
            return;
        }

        if (!plugin.getConfigManager().shouldStartPreScanLoadMissingChunks() || !PaperCheckUtil.IsPaper()) {
            plugin.getTraceLogger().trace("scanner", "background_chunk_skipped",
                    "world", world,
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "player_driven", searchChunk.playerDriven(),
                    "reason", "missing_chunk_load_disabled_or_not_paper");
            return;
        }

        plugin.getTraceLogger().trace("scanner", "background_chunk_load_requested",
                "world", world,
                "chunk_x", chunkX,
                "chunk_z", chunkZ,
                "player_driven", searchChunk.playerDriven());
        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (generation != preScanGeneration || !plugin.getGameManager().isRunning()) {
                        plugin.getTraceLogger().trace("scanner", "background_chunk_load_ignored",
                                "world", chunk.getWorld(),
                                "chunk_x", chunk.getX(),
                                "chunk_z", chunk.getZ(),
                                "reason", generation != preScanGeneration ? "stale_generation" : "run_not_active");
                        return;
                    }
                    plugin.getTraceLogger().trace("scanner", "background_chunk_scan_after_load",
                            "world", chunk.getWorld(),
                            "chunk_x", chunk.getX(),
                            "chunk_z", chunk.getZ());
                    scanLoadedChunk(null, chunk.getWorld(), chunk.getX(), chunk.getZ());
                }));
    }

    public void scanLoadedPlayerChunk(Player player) {
        if (player == null || player.getWorld() == null || plugin.getConfigManager().isHardcoreModeEnabled()) {
            return;
        }
        World world = player.getWorld();
        int centerChunkX = player.getLocation().getBlockX() >> 4;
        int centerChunkZ = player.getLocation().getBlockZ() >> 4;
        int chunkRadius = plugin.getConfigManager().getLoadedChunkScanRadius();
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                scanLoadedChunk(player, world, chunkX, chunkZ);
            }
        }
    }

    private void scanLoadedChunk(Player player, World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            plugin.getTraceLogger().trace("scanner", "loaded_chunk_skipped",
                    "world", world,
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "reason", "chunk_not_loaded");
            return;
        }
        String key = world.getUID() + ":" + chunkX + ":" + chunkZ;
        if (!liveScannedChunks.add(key)) {
            plugin.getTraceLogger().trace("scanner", "loaded_chunk_skipped",
                    "world", world,
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "reason", "already_scanned");
            return;
        }

        plugin.getTraceLogger().trace("scanner", "loaded_chunk_scanning",
                "player", player,
                "world", world,
                "environment", world.getEnvironment(),
                "chunk_x", chunkX,
                "chunk_z", chunkZ);
        scanGeneratedStructuresInChunk(player, world, chunkX, chunkZ);

        if (world.getEnvironment() != World.Environment.NORMAL) {
            plugin.getTraceLogger().trace("scanner", "block_snapshot_scan_skipped",
                    "world", world,
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "reason", "not_overworld",
                    "environment", world.getEnvironment());
            return;
        }

        if (PaperCheckUtil.IsPaper()) {
            scanLoadedChunkSnapshotAsync(player, world, chunkX, chunkZ);
            return;
        }

        scanLoadedChunkSynchronously(player, world, chunkX, chunkZ);
    }

    private void scanGeneratedStructuresInChunk(Player player, World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isGenerated()) {
            return;
        }

        for (GeneratedStructure generatedStructure : chunk.getStructures()) {
            String key = structureKeyFor(generatedStructure.getStructure(), world.getEnvironment());
            if (key == null || !isStructureSearchActive(key)) {
                plugin.getTraceLogger().trace("scanner", "generated_structure_skipped",
                        "world", world,
                        "environment", world.getEnvironment(),
                        "chunk_x", chunkX,
                        "chunk_z", chunkZ,
                        "minecraft_structure", String.valueOf(generatedStructure.getStructure()),
                        "mapped_key", key,
                        "reason", key == null ? "not_tracked_in_environment" : "search_inactive");
                continue;
            }
            Location anchor = loadedAnchorOf(generatedStructure, world, chunkX, chunkZ);
            plugin.getTraceLogger().trace("scanner", "generated_structure_matched",
                    "world", world,
                    "environment", world.getEnvironment(),
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "minecraft_structure", String.valueOf(generatedStructure.getStructure()),
                    "mapped_key", key,
                    "anchor", anchor);
            structureFound(player, key, anchor, true);
        }
    }

    private String structureKeyFor(Structure structure, World.Environment environment) {
        if (environment == World.Environment.NETHER) {
            if (structure == Structure.FORTRESS) {
                return "FORTRESS";
            }
            if (structure == Structure.BASTION_REMNANT) {
                return "BASTION";
            }
            return null;
        }

        if (environment == World.Environment.NORMAL) {
            if (structure == Structure.STRONGHOLD) {
                return "END_PORTAL";
            }
            if (structure == Structure.VILLAGE_PLAINS
                    || structure == Structure.VILLAGE_DESERT
                    || structure == Structure.VILLAGE_SAVANNA
                    || structure == Structure.VILLAGE_SNOWY
                    || structure == Structure.VILLAGE_TAIGA) {
                return "VILLAGE";
            }
        }
        return null;
    }

    private boolean isStructureSearchActive(String key) {
        return foundLocations.containsKey(key)
                && foundLocations.get(key) == null
                && !disabledSearches.contains(key);
    }

    private Location centerOf(GeneratedStructure structure, World world) {
        BoundingBox box = structure.getBoundingBox();
        return new Location(world,
                (box.getMinX() + box.getMaxX()) / 2.0,
                (box.getMinY() + box.getMaxY()) / 2.0,
                (box.getMinZ() + box.getMaxZ()) / 2.0);
    }

    private Location loadedAnchorOf(GeneratedStructure structure, World world, int chunkX, int chunkZ) {
        Location center = centerOf(structure, world);
        int centerChunkX = center.getBlockX() >> 4;
        int centerChunkZ = center.getBlockZ() >> 4;
        if (world.isChunkLoaded(centerChunkX, centerChunkZ)) {
            return center;
        }

        double y = Math.max(world.getMinHeight() + 1,
                Math.min(world.getMaxHeight() - 2, center.getY()));
        return new Location(world, (chunkX << 4) + 8.0, y, (chunkZ << 4) + 8.0);
    }

    private void scanLoadedChunkSynchronously(Player player, World world, int chunkX, int chunkZ) {
        Location origin = scanOrigin(player, world, chunkX, chunkZ);
        if (isLavaPoolSearchActive() && plugin.getConfigManager().isStartPreScanLavaEnabled()) {
            Location lavaPool = findLavaClusterInChunk(world, chunkX, chunkZ, origin,
                    plugin.getConfigManager().getStartPreScanRadius(),
                    plugin.getConfigManager().getLavaPoolRequiredSources(),
                    plugin.getConfigManager().getStartPreScanLavaScanBelowBlocks(),
                    plugin.getConfigManager().getStartPreScanLavaScanAboveBlocks());
            if (lavaPool != null) {
                structureFound(player, "LAVA_POOL", lavaPool);
            }
        }
        if (isVillageSearchActive()) {
            Location bell = findBlockInChunk(world, chunkX, chunkZ, Material.BELL);
            if (bell != null) {
                structureFound(player, "VILLAGE", bell);
            }
        }
    }

    private void scanLoadedChunkSnapshotAsync(Player player, World world, int chunkX, int chunkZ) {
        ChunkSnapshot snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, false, false);
        Location origin = scanOrigin(player, world, chunkX, chunkZ);
        boolean scanLava = isLavaPoolSearchActive() && plugin.getConfigManager().isStartPreScanLavaEnabled();
        boolean scanVillage = isVillageSearchActive();
        int radius = plugin.getConfigManager().getStartPreScanRadius();
        int requiredSources = plugin.getConfigManager().getLavaPoolRequiredSources();
        int lavaScanBelowBlocks = plugin.getConfigManager().getStartPreScanLavaScanBelowBlocks();
        int lavaScanAboveBlocks = plugin.getConfigManager().getStartPreScanLavaScanAboveBlocks();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Location lavaPool = null;
            Location bell = null;

            if (scanLava) {
                lavaPool = findLavaClusterInSnapshot(snapshot, world, origin, radius, requiredSources,
                        lavaScanBelowBlocks, lavaScanAboveBlocks);
            }
            if (scanVillage) {
                bell = findBlockInSnapshot(snapshot, world, Material.BELL);
            }

            Location finalLavaPool = lavaPool;
            Location finalBell = bell;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.getGameManager().isRunning()) {
                    return;
                }
                if (finalLavaPool != null && isLavaPoolSearchActive()) {
                    structureFound(player, "LAVA_POOL", finalLavaPool);
                }
                if (finalBell != null && isVillageSearchActive()) {
                    structureFound(player, "VILLAGE", finalBell);
                }
            });
        });
    }

    private Location scanOrigin(Player player, World world, int chunkX, int chunkZ) {
        if (player != null) {
            return player.getLocation().clone();
        }

        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        int y = Math.max(world.getMinHeight() + 1,
                Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(x, z)));
        return new Location(world, x, y, z);
    }

    private Location findLavaClusterInChunk(World world, int chunkX, int chunkZ, Location origin, int radiusBlocks,
                                            int requiredSources, int scanBelowBlocks, int scanAboveBlocks) {
        int minY = startPreScanLavaMinY(origin.getBlockY(), world.getMinHeight(), scanBelowBlocks);
        int maxY = startPreScanLavaMaxY(origin.getBlockY(), world.getMaxHeight(), scanAboveBlocks);
        int count = 0;
        Location first = null;

        for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
            for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                if (Math.abs(x - origin.getBlockX()) > radiusBlocks || Math.abs(z - origin.getBlockZ()) > radiusBlocks) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.LAVA) {
                        if (first == null) {
                            first = block.getLocation();
                        }
                        if (++count >= requiredSources) {
                            return first;
                        }
                    }
                }
            }
        }
        return null;
    }

    private Location findLavaClusterInSnapshot(ChunkSnapshot snapshot, World world, Location origin, int radiusBlocks,
                                               int requiredSources, int scanBelowBlocks, int scanAboveBlocks) {
        int minY = startPreScanLavaMinY(origin.getBlockY(), world.getMinHeight(), scanBelowBlocks);
        int maxY = startPreScanLavaMaxY(origin.getBlockY(), world.getMaxHeight(), scanAboveBlocks);
        int chunkBlockX = snapshot.getX() << 4;
        int chunkBlockZ = snapshot.getZ() << 4;
        int count = 0;
        Location first = null;

        for (int x = 0; x < 16; x++) {
            int worldX = chunkBlockX + x;
            if (Math.abs(worldX - origin.getBlockX()) > radiusBlocks) {
                continue;
            }
            for (int z = 0; z < 16; z++) {
                int worldZ = chunkBlockZ + z;
                if (Math.abs(worldZ - origin.getBlockZ()) > radiusBlocks) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    if (snapshot.getBlockType(x, y, z) == Material.LAVA) {
                        if (first == null) {
                            first = new Location(world, worldX, y, worldZ);
                        }
                        if (++count >= requiredSources) {
                            return first;
                        }
                    }
                }
            }
        }
        return null;
    }

    static int startPreScanLavaMinY(int originY, int worldMinHeight, int scanBelowBlocks) {
        return Math.max(worldMinHeight, originY - Math.max(0, scanBelowBlocks));
    }

    static int startPreScanLavaMaxY(int originY, int worldMaxHeight, int scanAboveBlocks) {
        return Math.min(worldMaxHeight - 1, originY + Math.max(0, scanAboveBlocks));
    }

    private Location findBlockInChunk(World world, int chunkX, int chunkZ, Material material) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
            for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == material) {
                        return block.getLocation();
                    }
                }
            }
        }
        return null;
    }

    private Location findBlockInSnapshot(ChunkSnapshot snapshot, World world, Material material) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int chunkBlockX = snapshot.getX() << 4;
        int chunkBlockZ = snapshot.getZ() << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (snapshot.getBlockType(x, y, z) == material) {
                        return new Location(world, chunkBlockX + x, y, chunkBlockZ + z);
                    }
                }
            }
        }
        return null;
    }

    public void cancelPreScan() {
        preScanGeneration++;
        locateQueue.clear();
        backgroundScanQueue.clear();
        queuedBackgroundChunks.clear();
        if (preScanTask != null) {
            preScanTask.cancel();
            preScanTask = null;
        }
        if (backgroundScanTask != null) {
            backgroundScanTask.cancel();
            backgroundScanTask = null;
        }
        if (locateTask != null) {
            locateTask.cancel();
            locateTask = null;
        }
    }

    /**
     * Registers a found structure (except for Nether Portals, which have special handling).
     * Fires a StructureFoundEvent and broadcasts a message.
     * |
     * Реєструє знайдену структуру (окрім порталів у Незер, які мають спеціальну обробку).
     * Викликає подію StructureFoundEvent та розсилає повідомлення.
     *
     * @param player The player who found the structure. / Гравець, який знайшов структуру.
     * @param key The unique key of the structure (e.g., "VILLAGE"). / Унікальний ключ структури (напр., "VILLAGE").
     * @param loc The location of the structure. / Місцезнаходження структури.
     */
    public void structureFound(Player player, String key, Location loc) {
        structureFound(player, key, loc, false);
    }

    public void structureFound(Player player, String key, Location loc, boolean approximate) {
        // Nether portals are handled by portalLit() and portalExitFound().
        // Портали в Незер обробляються методами portalLit() та portalExitFound().
        if (key.equals("NETHER_PORTAL")) {
            return;
        }

        Location previousLocation = foundLocations.get(key);
        boolean confirmsApproximateLocation = previousLocation != null
                && !approximate
                && approximateStructures.contains(key);

        if (key.equals("VILLAGE")) {
            villageSearchFailed = false;
            plugin.getGameManager().villageTimeElapsed = 0;
        }
        if (key.equals("END_PORTAL")) {
            predictedEndPortalLocation = null;
            predictedEndPortalApproximate = false;
        }

        foundLocations.put(key, loc);
        if (approximate) {
            approximateStructures.add(key);
        } else {
            approximateStructures.remove(key);
        }
        disabledSearches.remove(key);
        registerHiddenLodestone(key, loc);
        String playerName = player != null ? player.getName() : "SERVER";
        plugin.getGameManager().getLogger().info("Structure '" + key + "' found/updated by " + playerName + " at " + LocationUtil.format(loc));
        plugin.getTraceLogger().trace("structure", "structure_found",
                "key", key,
                "player", player,
                "location", loc,
                "approximate", approximate,
                "confirmed_approximate", confirmsApproximateLocation,
                "hidden_lodestone", getHiddenLodestone(key, loc));

        Bukkit.getPluginManager().callEvent(new StructureFoundEvent(player, key, loc));
        if (!confirmsApproximateLocation) {
            boolean completedActiveStructureTask = plugin.getTaskManager().onStructureFound(key, player);
            if (!completedActiveStructureTask) {
                plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
            }
        }

        String displayName = getLocalizedStructureName(key);
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                "%player%", playerName,
                "%structure%", displayName,
                "%coords%", LocationUtil.format(loc)));
    }

    // =========================================================================================
    // Nether Portal Specific Methods
    // =========================================================================================

    /**
     * Registers a newly lit Nether Portal. This becomes the new "main" portal pair.
     * It resets any previously found portal locations.
     * |
     * Реєструє щойно запалений портал у Незер. Він стає новою "основною" парою порталів.
     * Скидає будь-які раніше знайдені локації порталів.
     *
     * @param player The player who lit the portal, or null if lit by environment. / Гравець, що запалив портал, або null, якщо запалено оточенням.
     * @param loc The location of a portal block. / Місцезнаходження блоку порталу.
     */
    public void portalLit(Player player, Location loc) {
        // Ignore duplicate detections around the same physical portal frame.
        for (Location knownLoc : Arrays.asList(overworldPortalLocation, netherPortalLocation)) {
            if (knownLoc != null
                    && knownLoc.getWorld().equals(loc.getWorld())
                    && knownLoc.distance(loc) < 4.0) {
                return; // same portal
            }
        }


        if (isPortalFullyFound() && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            if (player != null) {
                MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.reassigning-disabled"));
            }
            plugin.getLogger().info("Blocked portal reassignment: portal reassignment is disabled in the config.");
            return;
        }

        // Determine which dimension the portal was lit in.
        // Визначаємо, в якому вимірі було запалено портал.
        World.Environment world = (player != null) ? player.getWorld().getEnvironment() : loc.getWorld().getEnvironment();

        // Reset both locations to establish a new primary portal pair.
        // Скидаємо обидві локації, щоб створити нову основну пару порталів.
        this.overworldPortalLocation = null;
        this.netherPortalLocation = null;

        if (world == World.Environment.NORMAL) {
            this.overworldPortalLocation = loc;
        } else if (world == World.Environment.NETHER) {
            this.netherPortalLocation = loc;
        } else {
            return; // Portals in other dimensions are not tracked. / Портали в інших вимірах не відстежуються.
        }

        clearHiddenLodestone("NETHER_PORTAL");

        // Update the placeholder for scoreboard display.
        // Оновлюємо плейсхолдер для відображення на скорборді.
        foundLocations.put("NETHER_PORTAL", loc);
        approximateStructures.remove("NETHER_PORTAL");
        disabledSearches.remove("NETHER_PORTAL");
        registerHiddenLodestone("NETHER_PORTAL", loc);
        Bukkit.getPluginManager().callEvent(new StructureFoundEvent(player, "NETHER_PORTAL", loc));
        plugin.getTraceLogger().trace("portal", "portal_lit",
                "player", player,
                "environment", world,
                "location", loc,
                "overworld_portal", overworldPortalLocation,
                "nether_portal", netherPortalLocation,
                "hidden_lodestone", getHiddenLodestone("NETHER_PORTAL", loc));

        String playerName = (player != null) ? player.getName() : "GAME_WORLD";
        plugin.getGameManager().getLogger().info("Nether Portal lit by " + playerName + " in " + world.name() + " at " + LocationUtil.format(loc));

        boolean completedActiveStructureTask = plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD", player);
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", playerName));
        if (!completedActiveStructureTask) {
            plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
        }

        // Update scoreboards for all players to reflect the new portal location.
        // Оновлюємо скорборд для всіх гравців, щоб відобразити нову локацію порталу.
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    /**
     * Registers the location of a portal on the destination side after teleportation.
     * This only sets the location if it hasn't been discovered yet.
     * |
     * Реєструє місцезнаходження порталу на стороні призначення після телепортації.
     * Встановлює локацію, тільки якщо її ще не було виявлено.
     *
     * @param exitLoc The precise location of the exit portal. / Точне місцезнаходження вихідного порталу.
     */
    public void portalExitFound(Location exitLoc) {
        World.Environment exitWorld = exitLoc.getWorld().getEnvironment();
        if (exitWorld == World.Environment.NETHER && this.netherPortalLocation == null) {
            this.netherPortalLocation = exitLoc;
            approximateStructures.remove("NETHER_PORTAL");
            registerHiddenLodestone("NETHER_PORTAL", exitLoc);
            Bukkit.getPluginManager().callEvent(new StructureFoundEvent(null, "NETHER_PORTAL", exitLoc));
            plugin.getGameManager().getLogger().info("Nether Portal exit (Nether-side) found at " + LocationUtil.format(exitLoc));
            plugin.getTraceLogger().trace("portal", "portal_exit_found",
                    "environment", exitWorld,
                    "location", exitLoc,
                    "overworld_portal", overworldPortalLocation,
                    "nether_portal", netherPortalLocation,
                    "hidden_lodestone", getHiddenLodestone("NETHER_PORTAL", exitLoc));
        } else if (exitWorld == World.Environment.NORMAL && this.overworldPortalLocation == null) {
            this.overworldPortalLocation = exitLoc;
            approximateStructures.remove("NETHER_PORTAL");
            registerHiddenLodestone("NETHER_PORTAL", exitLoc);
            Bukkit.getPluginManager().callEvent(new StructureFoundEvent(null, "NETHER_PORTAL", exitLoc));
            plugin.getGameManager().getLogger().info("Nether Portal exit (Overworld-side) found at " + LocationUtil.format(exitLoc));
            plugin.getTraceLogger().trace("portal", "portal_exit_found",
                    "environment", exitWorld,
                    "location", exitLoc,
                    "overworld_portal", overworldPortalLocation,
                    "nether_portal", netherPortalLocation,
                    "hidden_lodestone", getHiddenLodestone("NETHER_PORTAL", exitLoc));
        }
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    /**
     * Handles the logic for the `/run new nether portal` command.
     * |
     * Обробляє логіку команди `/run new nether portal`.
     *
     * @param player The player executing the command. / Гравець, що виконує команду.
     * @return True if the portal was successfully reassigned. / True, якщо портал було успішно перепризначено.
     */
    public boolean reassignNetherPortal(Player player) {
        if (!plugin.getConfigManager().isReassigningLocationsEnabled()) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.reassigning-disabled"));
            return false;
        }
        portalLit(player, player.getLocation());
        MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.portal-reassigned"));
        return true;
    }

    /**
     * Updates a structure's location via an admin command.
     * |
     * Оновлює локацію структури через команду адміністратора.
     *
     * @param naturalName The user-friendly name of the structure (e.g., "Bastion Remnant"). / Зрозуміла назва структури (напр., "Bastion Remnant").
     * @param newLocation The new location to set. / Нова локація для встановлення.
     * @param player The player who issued the command. / Гравець, що видав команду.
     * @return True on success. / True у разі успіху.
     */
    public boolean updateStructureLocation(String naturalName, Location newLocation, Player player) {
        String key = naturalName.replace(' ', '_').toUpperCase();

        if (!foundLocations.containsKey(key)) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.unknown-structure-name", "%name%", naturalName));
            return false;
        }

        if (foundLocations.get(key) != null && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.reassigning-disabled"));
            return false;
        }

        if (key.equals("NETHER_PORTAL")) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.use-run-new-nether-portal"));
            return false;
        }
        // Use the standard structureFound method to ensure all logic (events, messages) is triggered.
        // Використовуємо стандартний метод structureFound, щоб гарантувати спрацювання всієї логіки (події, повідомлення).
        structureFound(player, key, newLocation);
        return true;
    }

    public void restoreStructure(String key) {
        if (!foundLocations.containsKey(key)) return;

        hiddenStructures.remove(key);
        disabledSearches.remove(key);
        approximateStructures.remove(key);
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    public boolean removeStructure(String key) {
        if (!foundLocations.containsKey(key)) return false;

        if ("NETHER_PORTAL".equals(key)) {
            overworldPortalLocation = null;
            netherPortalLocation = null;
        }

        foundLocations.put(key, null);
        approximateStructures.remove(key);
        disabledSearches.add(key);
        clearHiddenLodestone(key);
        hiddenStructures.add(key);
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
        return true;
    }


    // =========================================================================================
    // Getters & State Checks
    // =========================================================================================

    public boolean isStructureHidden(String key) {
        return hiddenStructures.contains(key);
    }

    /** @return The location of the Overworld-side portal, or null if not found. / Локація порталу у Звичайному світі, або null, якщо не знайдено. */
    public Location getOverworldPortalLocation() { return overworldPortalLocation; }

    /** @return The location of the Nether-side portal, or null if not found. / Локація порталу в Незері, або null, якщо не знайдено. */
    public Location getNetherPortalLocation() { return netherPortalLocation; }

    /**
     * Gets the appropriate portal location to display on the scoreboard based on the player's current dimension.
     * Отримує відповідну локацію порталу для відображення на скорборді, залежно від поточного виміру гравця.
     */
    public Location getPortalLocationForWorld(World.Environment world) {
        if (world == World.Environment.NORMAL) return overworldPortalLocation;
        if (world == World.Environment.NETHER) return netherPortalLocation;
        return null;
    }

    /** @return True if at least one side of the Nether Portal pair has been found. / True, якщо знайдено хоча б одну сторону пари порталів у Незер. */
    public boolean isPortalPartiallyFound() {
        return overworldPortalLocation != null || netherPortalLocation != null;
    }

    /** @return True if both sides of the Nether Portal pair have been found. / True, якщо знайдено обидві сторони пари порталів у Незер. */
    public boolean isPortalFullyFound() {
        return overworldPortalLocation != null && netherPortalLocation != null;
    }

    /** @return A map of all tracked structures and their locations. / Мапа всіх відстежуваних структур та їхніх локацій. */
    public Map<String, Location> getFoundStructures() {
        return foundLocations;
    }

    public boolean isApproximateStructure(String key) {
        return approximateStructures.contains(key);
    }

    /** Gets the localized, user-friendly name for a structure key. / Отримує локалізовану, зрозумілу назву для ключа структури. */
    public String getLocalizedStructureName(String key) {
        return plugin.getConfigManager().getLangString("structures." + key, key);
    }

    /** Checks if the village search timer has expired. / Перевіряє, чи не сплив час таймера пошуку села. */
    public void checkVillageTimeout() {
        if (foundLocations.get("VILLAGE") != null) return;
        if (disabledSearches.contains("VILLAGE")
                || villageSearchFailed
                || plugin.getGameManager().getVillageTimeRemaining() > 0) {
            return;
        }

        villageSearchFailed = true;
        foundLocations.put("VILLAGE", null);
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    /** @return True if the plugin is currently actively searching for a village. / True, якщо плагін наразі активно шукає село. */
    public boolean isVillageSearchActive() {
        Location villageLocation = foundLocations.get("VILLAGE");
        boolean needsExactBellLocation = villageLocation == null || approximateStructures.contains("VILLAGE");
        return needsExactBellLocation
                && !disabledSearches.contains("VILLAGE")
                && plugin.getGameManager().getVillageTimeElapsed() < plugin.getConfigManager().getVillageTimeout()
                && !villageSearchFailed;
    }

    public boolean isVillageSearchFailed() {
        return villageSearchFailed;
    }

    /** @return True if the plugin is actively searching for a lava pool. / True, якщо плагін активно шукає озеро лави. */
    public boolean isLavaPoolSearchActive() {
        // Search for lava only if a portal hasn't been found yet.
        // Шукаємо лаву, тільки якщо портал ще не знайдено.
        return foundLocations.containsKey("LAVA_POOL")
                && foundLocations.get("LAVA_POOL") == null
                && !disabledSearches.contains("LAVA_POOL")
                && !isPortalPartiallyFound();
    }

    /** @return The predicted location of the End Portal from triangulation, or null. / Передбачена локація порталу в Край з тріангуляції, або null. */
    public Location getPredictedEndPortalLocation() {
        return predictedEndPortalLocation;
    }

    /** @return True while the End Portal location is only a triangulated estimate. / True, якщо локація порталу Краю є лише приблизною. */
    public boolean isPredictedEndPortalApproximate() {
        return predictedEndPortalApproximate;
    }

    /** Sets the predicted location of the End Portal. / Встановлює передбачену локацію порталу в Край. */
    public void setPredictedEndPortalLocation(Location location) {
        this.predictedEndPortalLocation = location;
        this.predictedEndPortalApproximate = location != null;
    }

    public Location getHiddenLodestone(String key) {
        return hiddenLodestones.get(key);
    }

    public Location getHiddenLodestone(String key, Location targetLocation) {
        return hiddenLodestones.get(hiddenLodestoneStorageKey(key, targetLocation));
    }

    public Map<String, Location> getHiddenLodestones() {
        return Collections.unmodifiableMap(hiddenLodestones);
    }

    public Location ensureHiddenLodestone(String key, Location structureLocation) {
        return registerHiddenLodestone(key, structureLocation);
    }

    public CompletableFuture<Location> ensureHiddenLodestoneAsync(String key, Location structureLocation) {
        if (structureLocation == null || structureLocation.getWorld() == null) {
            plugin.getTraceLogger().trace("lodestone", "ensure_async_rejected",
                    "key", key,
                    "reason", "null_location_or_world");
            return CompletableFuture.completedFuture(null);
        }

        World world = structureLocation.getWorld();
        int chunkX = structureLocation.getBlockX() >> 4;
        int chunkZ = structureLocation.getBlockZ() >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return CompletableFuture.completedFuture(registerHiddenLodestone(key, structureLocation));
        }

        CompletableFuture<Location> result = new CompletableFuture<>();
        plugin.getTraceLogger().trace("lodestone", "chunk_load_requested",
                "key", key,
                "target", structureLocation,
                "chunk_x", chunkX,
                "chunk_z", chunkZ,
                "paper_async", PaperCheckUtil.IsPaper());

        if (PaperCheckUtil.IsPaper()) {
            world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            plugin.getTraceLogger().trace("lodestone", "chunk_load_failed",
                                    "key", key,
                                    "target", structureLocation,
                                    "chunk_x", chunkX,
                                    "chunk_z", chunkZ,
                                    "error", error.toString());
                            result.complete(null);
                            return;
                        }
                        result.complete(registerHiddenLodestone(key, structureLocation));
                    }));
            return result;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean loaded = world.loadChunk(chunkX, chunkZ, true);
            plugin.getTraceLogger().trace("lodestone", "sync_chunk_load_finished",
                    "key", key,
                    "target", structureLocation,
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "loaded", loaded);
            result.complete(loaded ? registerHiddenLodestone(key, structureLocation) : null);
        });
        return result;
    }

    public void clearHiddenLodestoneForKey(String key) {
        clearHiddenLodestone(key);
    }

    private Location registerHiddenLodestone(String key, Location structureLocation) {
        if (structureLocation == null || structureLocation.getWorld() == null) {
            plugin.getTraceLogger().trace("lodestone", "placement_rejected",
                    "key", key,
                    "reason", "null_location_or_world");
            return null;
        }

        String storageKey = hiddenLodestoneStorageKey(key, structureLocation);
        Location lodestoneLocation = resolveHiddenLodestoneLocation(structureLocation);
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            plugin.getTraceLogger().trace("lodestone", "placement_rejected",
                    "key", key,
                    "storage_key", storageKey,
                    "reason", "null_lodestone_world");
            return null;
        }

        if (!world.isChunkLoaded(lodestoneLocation.getBlockX() >> 4, lodestoneLocation.getBlockZ() >> 4)) {
            Location existing = hiddenLodestones.get(storageKey);
            boolean existingMatchesTarget = existing != null
                    && existing.getWorld() != null
                    && existing.getWorld().equals(world)
                    && existing.getBlockX() == structureLocation.getBlockX()
                    && existing.getBlockZ() == structureLocation.getBlockZ();
            plugin.getTraceLogger().trace("lodestone", "placement_deferred_chunk_unloaded",
                    "key", key,
                    "storage_key", storageKey,
                    "target", structureLocation,
                    "lodestone", lodestoneLocation,
                    "existing_reused", existingMatchesTarget);
            return existingMatchesTarget ? existing : null;
        }

        Location existing = hiddenLodestones.get(storageKey);
        if (isExistingHiddenLodestoneValid(existing, lodestoneLocation)) {
            plugin.getTraceLogger().trace("lodestone", "placement_reused",
                    "key", key,
                    "storage_key", storageKey,
                    "target", structureLocation,
                    "lodestone", existing);
            return existing;
        }

        if (hiddenLodestones.containsKey(storageKey) && !clearHiddenLodestoneStorageKey(storageKey)) {
            plugin.getTraceLogger().trace("lodestone", "placement_skipped_clear_failed",
                    "key", key,
                    "storage_key", storageKey,
                    "target", structureLocation,
                    "lodestone", lodestoneLocation);
            return null;
        }
        Block block = lodestoneLocation.getBlock();
        hiddenLodestonePreviousBlocks.put(storageKey, block.getType());
        block.setType(Material.LODESTONE, false);
        block.setMetadata(HIDDEN_LODESTONE_METADATA, new FixedMetadataValue(plugin, true));
        hiddenLodestones.put(storageKey, lodestoneLocation);
        plugin.getTraceLogger().trace("lodestone", "placement_succeeded",
                "key", key,
                "storage_key", storageKey,
                "target", structureLocation,
                "lodestone", lodestoneLocation,
                "previous_block", hiddenLodestonePreviousBlocks.get(storageKey));
        return lodestoneLocation;
    }

    private void clearHiddenLodestone(String key) {
        if ("NETHER_PORTAL".equals(key)) {
            for (String storageKey : new ArrayList<>(hiddenLodestones.keySet())) {
                if (storageKey.equals(key) || storageKey.startsWith(key + ":")) {
                    clearHiddenLodestoneStorageKey(storageKey);
                }
            }
            return;
        }
        clearHiddenLodestoneStorageKey(key);
    }

    private boolean clearHiddenLodestoneStorageKey(String storageKey) {
        Location lodestoneLocation = hiddenLodestones.get(storageKey);
        Material previousType = hiddenLodestonePreviousBlocks.get(storageKey);
        if (lodestoneLocation == null || lodestoneLocation.getWorld() == null) {
            plugin.getTraceLogger().trace("lodestone", "clear_skipped",
                    "storage_key", storageKey,
                    "reason", "not_registered");
            return true;
        }

        if (!lodestoneLocation.getWorld().isChunkLoaded(lodestoneLocation.getBlockX() >> 4, lodestoneLocation.getBlockZ() >> 4)) {
            plugin.getTraceLogger().trace("lodestone", "clear_skipped",
                    "storage_key", storageKey,
                    "lodestone", lodestoneLocation,
                    "reason", "chunk_unloaded");
            return false;
        }

        hiddenLodestones.remove(storageKey);
        hiddenLodestonePreviousBlocks.remove(storageKey);
        Block block = lodestoneLocation.getBlock();
        if (block.hasMetadata(HIDDEN_LODESTONE_METADATA)) {
            block.removeMetadata(HIDDEN_LODESTONE_METADATA, plugin);
        }

        if (previousType != null) {
            block.setType(previousType, false);
        }
        plugin.getTraceLogger().trace("lodestone", "cleared",
                "storage_key", storageKey,
                "lodestone", lodestoneLocation,
                "restored_block", previousType);
        return true;
    }

    private void clearAllHiddenLodestones() {
        for (String key : new ArrayList<>(hiddenLodestones.keySet())) {
            clearHiddenLodestoneStorageKey(key);
        }
    }

    private String hiddenLodestoneStorageKey(String key, Location structureLocation) {
        UUID worldId = structureLocation != null && structureLocation.getWorld() != null
                ? structureLocation.getWorld().getUID()
                : null;
        return hiddenLodestoneStorageKey(key, worldId);
    }

    static String hiddenLodestoneStorageKey(String key, UUID worldId) {
        if ("NETHER_PORTAL".equals(key) && worldId != null) {
            return key + ":" + worldId;
        }
        return key;
    }

    private Location resolveHiddenLodestoneLocation(Location structureLocation) {
        World world = structureLocation.getWorld();
        int y = resolveHiddenLodestoneY(structureLocation.getBlockY(), world.getMinHeight(), world.getMaxHeight());
        return new Location(world, structureLocation.getBlockX(), y, structureLocation.getBlockZ());
    }

    static int resolveHiddenLodestoneY(int targetY, int minHeight, int maxHeight) {
        int minSafe = minHeight + 1;
        int maxSafe = maxHeight - 2;
        if (maxSafe < minSafe) {
            return Math.max(minHeight, Math.min(maxHeight - 1, targetY));
        }
        int preferredDeepY = minHeight + 8;
        return Math.max(minSafe, Math.min(maxSafe, preferredDeepY));
    }

    private boolean isExistingHiddenLodestoneValid(Location existing, Location expected) {
        if (existing == null || expected == null || existing.getWorld() == null || expected.getWorld() == null) {
            return false;
        }
        if (!existing.getWorld().equals(expected.getWorld())
                || existing.getBlockX() != expected.getBlockX()
                || existing.getBlockY() != expected.getBlockY()
                || existing.getBlockZ() != expected.getBlockZ()) {
            return false;
        }
        if (!existing.getWorld().isChunkLoaded(existing.getBlockX() >> 4, existing.getBlockZ() >> 4)) {
            return false;
        }
        Block block = existing.getBlock();
        return block.getType() == Material.LODESTONE && block.hasMetadata(HIDDEN_LODESTONE_METADATA);
    }

    private record SearchChunk(World world, int chunkX, int chunkZ, boolean playerDriven) {
        private String key() {
            return world.getUID() + ":" + chunkX + ":" + chunkZ;
        }
    }

    private record LocateRequest(World world, Location origin, String structureKey, Structure structure) {
    }
}
