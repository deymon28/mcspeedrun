package org.speedrun.speedrun;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameListenerPortalSearchTest {
    @Test
    void defaultRadiusLoadsEachChunkOnceAndWaitsForItsSnapshot() {
        Map<String, CompletableFuture<Chunk>> loads = new LinkedHashMap<>();
        World world = loadingWorld(loads);

        List<CompletableFuture<ChunkSnapshot>> snapshots =
                GameListener.loadPortalChunkSnapshots(world, 0, 0, 90);

        assertEquals(144, loads.size());
        assertEquals(144, snapshots.size());
        assertTrue(snapshots.stream().noneMatch(CompletableFuture::isDone));

        Thread serverThread = Thread.currentThread();
        ChunkSnapshot first = snapshot(-6, -6, Set.of(), -64, 320);
        loads.get("-6,-6").complete(chunk(first, serverThread));

        assertSame(first, snapshots.getFirst().join());
        assertFalse(snapshots.get(1).isDone());
    }

    @Test
    void chunkRequestsCoverBothSidesOfNegativeBoundaries() {
        Map<String, CompletableFuture<Chunk>> loads = new LinkedHashMap<>();

        GameListener.loadPortalChunkSnapshots(loadingWorld(loads), -16, -1, 1);

        assertEquals(Set.of("-2,-1", "-2,0", "-1,-1", "-1,0"), loads.keySet());
    }

    @Test
    void backgroundSnapshotScanPreservesTraversalAcrossNegativeCoordinates() throws Exception {
        GameListener.PortalBlockPosition first = new GameListener.PortalBlockPosition(-1, 2, 0);
        GameListener.PortalBlockPosition later = new GameListener.PortalBlockPosition(0, -2, -1);
        Set<GameListener.PortalBlockPosition> portals = Set.of(first, later);
        List<ChunkSnapshot> snapshots = List.of(
                snapshot(-1, -1, portals, -64, 320), snapshot(-1, 0, portals, -64, 320),
                snapshot(0, -1, portals, -64, 320), snapshot(0, 0, portals, -64, 320));

        GameListener.PortalBlockPosition found = CompletableFuture.supplyAsync(() ->
                GameListener.findPortalBlockInSnapshots(snapshots, -1, 0, -1, 2, -64, 320))
                .get(5, TimeUnit.SECONDS);

        assertEquals(first, found);
    }

    @Test
    void snapshotScanClampsToTheWorldBuildHeight() {
        GameListener.PortalBlockPosition bottom = new GameListener.PortalBlockPosition(8, -64, 8);
        GameListener.PortalBlockPosition top = new GameListener.PortalBlockPosition(8, 319, 8);
        List<ChunkSnapshot> snapshots = List.of(snapshot(0, 0, Set.of(bottom, top), -64, 320));

        assertEquals(bottom,
                GameListener.findPortalBlockInSnapshots(snapshots, 8, -64, 8, 3, -64, 320));
        assertEquals(top,
                GameListener.findPortalBlockInSnapshots(snapshots, 8, 319, 8, 3, -64, 320));
    }

    @Test
    void snapshotScanReturnsNullWhenNoPortalIsPresent() {
        List<ChunkSnapshot> snapshots = List.of(snapshot(0, 0, Set.of(), -64, 320));

        assertNull(GameListener.findPortalBlockInSnapshots(snapshots, 8, 64, 8, 3, -64, 320));
    }

    private static World loadingWorld(Map<String, CompletableFuture<Chunk>> loads) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getChunkAtAsync") && args.length == 2) {
                        String key = args[0] + "," + args[1];
                        CompletableFuture<Chunk> future = new CompletableFuture<>();
                        assertNull(loads.putIfAbsent(key, future), "Each chunk must be requested only once");
                        return future;
                    }
                    throw new AssertionError("Unexpected live world access: " + method.getName());
                });
    }

    private static Chunk chunk(ChunkSnapshot snapshot, Thread serverThread) {
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getChunkSnapshot")) {
                        assertSame(serverThread, Thread.currentThread(),
                                "Capture snapshots inline on Paper's main-thread completion callback");
                        return snapshot;
                    }
                    throw new AssertionError("Unexpected live chunk access: " + method.getName());
                });
    }

    private static ChunkSnapshot snapshot(int chunkX, int chunkZ,
            Set<GameListener.PortalBlockPosition> portals, int minHeight, int maxHeight) {
        return (ChunkSnapshot) Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),
                new Class<?>[]{ChunkSnapshot.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> chunkX;
                    case "getZ" -> chunkZ;
                    case "getBlockType" -> {
                        int x = (int) args[0];
                        int y = (int) args[1];
                        int z = (int) args[2];
                        assertTrue(x >= 0 && x < 16 && z >= 0 && z < 16,
                                "Snapshot coordinates must stay inside the chunk");
                        assertTrue(y >= minHeight && y < maxHeight,
                                "Snapshot access must stay inside the world build height");
                        GameListener.PortalBlockPosition block = new GameListener.PortalBlockPosition(
                                (chunkX << 4) + x, y, (chunkZ << 4) + z);
                        yield portals.contains(block) ? Material.NETHER_PORTAL : Material.AIR;
                    }
                    default -> throw new AssertionError("Unexpected snapshot access: " + method.getName());
                });
    }
}
