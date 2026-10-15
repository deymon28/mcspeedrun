package org.speedrun.speedrun.platform;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Runtime boundary between gameplay code and the server software.
 *
 * <p>Owns the detected {@link PlatformAdapter}, a bounded chunk-loading queue, and a small
 * pool of worker threads for CPU work on immutable {@link ChunkSnapshot}s. Live world access
 * (blocks, entities, inventories, chunk loading) always stays on the server thread; workers
 * only receive snapshots and captured primitive data.</p>
 *
 * <p>On Paper, missing chunks are loaded asynchronously with several requests in flight.
 * On CraftBukkit/Spigot the same requests are served synchronously, one per tick, so a large
 * scan can never stall the server for more than a single chunk load at a time.</p>
 */
public final class ServerPlatform implements AutoCloseable {
    static final int MAX_PENDING_CHUNKS = 512;
    static final int WORKER_QUEUE_CAPACITY = 64;
    private static final int ASYNC_CHUNK_LOADS_IN_FLIGHT = 4;
    private static final int SYNC_CHUNK_LOADS_PER_TICK = 1;
    private static final java.util.regex.Pattern VERSION_PREFIX = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)*)");

    private final Plugin plugin;
    private final PlatformAdapter adapter;
    private final String minecraftVersion;
    private final SnapshotExecutor workers;
    private final int workerThreads;
    private final Queue<ChunkRequest> chunkQueue = new ArrayDeque<>();
    private final Map<ChunkKey, CompletableFuture<Chunk>> pendingChunks = new LinkedHashMap<>();
    private final BukkitTask chunkPump;
    private volatile long generation;
    private volatile boolean closed;
    private int inFlightChunks;

    public ServerPlatform(Plugin plugin, PlatformAdapter adapter) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.minecraftVersion = parseMinecraftVersion(Bukkit.getBukkitVersion());
        this.workerThreads = defaultWorkerThreads(Runtime.getRuntime().availableProcessors());
        this.workers = new SnapshotExecutor(workerThreads, WORKER_QUEUE_CAPACITY);
        this.chunkPump = Bukkit.getScheduler().runTaskTimer(plugin, this::pumpChunks, 1L, 1L);
    }

    static String parseMinecraftVersion(String bukkitVersion) {
        if (bukkitVersion == null) {
            return "unknown";
        }
        // Spigot reports "26.3-R0.1-SNAPSHOT", Paper "26.3.build.157-beta".
        java.util.regex.Matcher matcher = VERSION_PREFIX.matcher(bukkitVersion);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    /** One worker on small machines, two otherwise: scans are bursty and must not compete with the server. */
    static int defaultWorkerThreads(int processors) {
        return processors >= 4 ? 2 : 1;
    }

    public PlatformAdapter adapter() { return adapter; }
    public String getName() { return adapter.name(); }
    public String getMinecraftVersion() { return minecraftVersion; }
    public boolean supportsAsyncChunkLoading() { return adapter.supportsAsyncChunkLoading(); }
    public boolean hasWorkerCapacity() { return !closed && workers.hasCapacity(); }
    public long generation() { return generation; }
    public boolean isClosed() { return closed; }

    public Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("asyncChunkLoading", adapter.supportsAsyncChunkLoading());
        capabilities.put("nativeAdventure", adapter.supportsNativeAdventure());
        capabilities.put("actionBar", adapter.supportsActionBar());
        capabilities.put("hiddenScoreNumbers", adapter.supportsHiddenScoreNumbers());
        capabilities.put("snapshotWorkers", workerThreads);
        capabilities.put("chunkLoadsPerTick", adapter.supportsAsyncChunkLoading()
                ? ASYNC_CHUNK_LOADS_IN_FLIGHT : SYNC_CHUNK_LOADS_PER_TICK);
        return capabilities;
    }

    /** Values safe to read from the web editor thread. */
    public Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("software", adapter.name());
        diagnostics.put("minecraftVersion", minecraftVersion);
        diagnostics.put("serverVersion", adapter.serverVersion());
        diagnostics.put("capabilities", capabilities());
        diagnostics.put("queuedWorkerTasks", workers.queuedTasks());
        diagnostics.put("activeWorkerTasks", workers.activeTasks());
        return diagnostics;
    }

    public String describe() {
        return adapter.name() + " " + minecraftVersion
                + " (chunk loading: " + (adapter.supportsAsyncChunkLoading() ? "async" : "bounded sync")
                + ", snapshot workers: " + workerThreads
                + ", action bar: " + (adapter.supportsActionBar() ? "yes" : "no") + ")";
    }

    /** Runs immediately on the server thread, or schedules it there; dropped after close or invalidation. */
    public void executeSync(Runnable action) {
        if (closed) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        long expectedGeneration = generation;
        runOnServerThread(() -> {
            if (!closed && generation == expectedGeneration) {
                action.run();
            }
        });
    }

    /** Like {@link #executeSync(Runnable)} but survives run invalidation (used for UI output). */
    private void executeUi(Runnable action) {
        if (closed) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            runOnServerThread(() -> {
                if (!closed) {
                    action.run();
                }
            });
        }
    }

    private void runOnServerThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is disabling; pending work must not revive plugin state.
        }
    }

    /**
     * Runs CPU-only work on a snapshot worker. The supplier must not touch live Bukkit state.
     * The future completes on the worker thread; hop back with {@link #executeSync(Runnable)}.
     */
    public <T> CompletableFuture<T> compute(Supplier<T> computation) {
        if (closed) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Platform is closed"));
        }
        return workers.compute(computation);
    }

    /**
     * Queues a chunk load. Must be called on the server thread; the future completes there.
     * Duplicate requests share one future, and the queue is bounded.
     */
    public CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ) {
        requireServerThread();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Platform is closed"));
        }
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return CompletableFuture.completedFuture(world.getChunkAt(chunkX, chunkZ));
        }
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        CompletableFuture<Chunk> existing = pendingChunks.get(key);
        if (existing != null) {
            return existing;
        }
        if (pendingChunks.size() >= MAX_PENDING_CHUNKS) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Chunk queue is full"));
        }
        CompletableFuture<Chunk> result = new CompletableFuture<>();
        pendingChunks.put(key, result);
        chunkQueue.add(new ChunkRequest(world, key, result, generation));
        return result;
    }

    /** Loads a chunk and captures a block snapshot on the server thread. */
    public CompletableFuture<ChunkSnapshot> snapshotChunk(World world, int chunkX, int chunkZ) {
        return loadChunk(world, chunkX, chunkZ).thenApply(chunk -> chunk.getChunkSnapshot(false, false, false));
    }

    public int pendingChunkLoads() {
        return pendingChunks.size();
    }

    private void pumpChunks() {
        boolean async = adapter.supportsAsyncChunkLoading();
        int budget = async ? ASYNC_CHUNK_LOADS_IN_FLIGHT : SYNC_CHUNK_LOADS_PER_TICK;
        int started = 0;
        while (started < budget && (!async || inFlightChunks < budget)) {
            ChunkRequest request = chunkQueue.poll();
            if (request == null) {
                return;
            }
            if (request.result().isDone()) {
                pendingChunks.remove(request.key(), request.result());
                continue;
            }
            started++;
            int x = request.key().x();
            int z = request.key().z();
            try {
                if (!async || request.world().isChunkLoaded(x, z)) {
                    complete(request, request.world().getChunkAt(x, z), null);
                } else {
                    // Count the load only once it was accepted; a synchronous failure must not
                    // leak an in-flight slot and eventually stall the queue.
                    CompletableFuture<Chunk> load = adapter.loadChunkAsync(request.world(), x, z);
                    inFlightChunks++;
                    load.whenComplete((chunk, error) -> executeUi(() -> {
                        inFlightChunks = Math.max(0, inFlightChunks - 1);
                        complete(request, chunk, error);
                    }));
                }
            } catch (RuntimeException error) {
                complete(request, null, error);
            }
        }
    }

    private void complete(ChunkRequest request, Chunk chunk, Throwable error) {
        pendingChunks.remove(request.key(), request.result());
        if (closed || request.generation() != generation) {
            request.result().cancel(false);
            return;
        }
        if (error != null) {
            request.result().completeExceptionally(error);
        } else if (chunk == null) {
            request.result().completeExceptionally(new IllegalStateException("Chunk load returned no chunk"));
        } else {
            request.result().complete(chunk);
        }
    }

    /** Cancels queued chunk loads and worker results that belong to the previous run. */
    public void invalidatePendingWork() {
        requireServerThread();
        generation++;
        chunkQueue.clear();
        List<CompletableFuture<Chunk>> abandoned = new ArrayList<>(pendingChunks.values());
        pendingChunks.clear();
        abandoned.forEach(future -> future.cancel(false));
        workers.invalidate();
    }

    public void sendMessage(CommandSender sender, PlatformText text) {
        if (sender == null) {
            return;
        }
        executeUi(() -> adapter.sendMessage(sender, text));
    }

    public void sendActionBar(Player player, PlatformText text) {
        if (player == null) {
            return;
        }
        executeUi(() -> {
            if (player.isOnline()) {
                adapter.sendActionBar(player, text);
            }
        });
    }

    public void setPlayerListHeaderFooter(Player player, PlatformText header, PlatformText footer) {
        executeUi(() -> {
            if (player.isOnline()) {
                adapter.setPlayerListHeaderFooter(player, header, footer);
            }
        });
    }

    public void setScoreNumbersHidden(Objective objective, boolean hidden) {
        if (adapter.supportsHiddenScoreNumbers()) {
            adapter.setScoreNumbersHidden(objective, hidden);
        }
    }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Live world access requires the server thread");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            invalidatePendingWork();
        }
        closed = true;
        chunkPump.cancel();
        workers.close();
    }

    private record ChunkKey(UUID world, int x, int z) {
    }

    private record ChunkRequest(World world, ChunkKey key, CompletableFuture<Chunk> result, long generation) {
    }
}
