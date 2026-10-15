package org.speedrun.speedrun.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.speedrun.speedrun.config.ConfigFileStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Remembers every block the plugin temporarily placed in the world (waypoint markers and hidden
 * compass lodestones) in {@code world-changes.yml}.
 *
 * <p>Normally the plugin removes these blocks itself on reset, stop, and disable, and the file
 * ends up empty. After a crash or a killed server process the file still lists them, and the next
 * start restores the original blocks before anything else runs, so no beacons, iron blocks, end
 * gateways, or lodestones are left behind.</p>
 *
 * <p>All methods must be called on the server thread. The file is written by a single background
 * thread from a snapshot taken on the server thread, at most once per tick.</p>
 */
public final class WorldChangeJournal {
    static final String FILE_NAME = "world-changes.yml";

    private final Plugin plugin;
    private final Logger logger;
    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Speedrun-WorldJournal");
        thread.setDaemon(true);
        return thread;
    });
    private boolean flushScheduled;
    private boolean closed;

    /**
     * One placed block: where it is, what the plugin placed, and the block data that was there
     * before (as {@link org.bukkit.block.data.BlockData#getAsString()}).
     */
    record Entry(String world, int x, int y, int z, Material placed, String previous) {
        String key() {
            return key(world, x, y, z);
        }

        static String key(String world, int x, int y, int z) {
            return world + ":" + x + ":" + y + ":" + z;
        }
    }

    public WorldChangeJournal(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.file = plugin.getDataFolder().toPath().resolve(FILE_NAME);
    }

    /**
     * Restores blocks left behind by a previous server session that did not shut down cleanly.
     * A block is only restored while it still is the block the plugin placed, so later changes
     * by players are never overwritten. Must run before the plugin places new blocks.
     *
     * @return number of restored blocks
     */
    public int restoreLeftovers() {
        if (!Files.exists(file)) {
            return 0;
        }
        List<Entry> leftovers;
        try {
            leftovers = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException failure) {
            logger.warning(FILE_NAME + " could not be read (" + failure.getMessage()
                    + "); blocks placed before the last crash may remain in the world.");
            moveAside();
            return 0;
        }
        int restored = restore(leftovers, Bukkit::getWorld);
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            logger.warning("Could not delete " + FILE_NAME + ": " + failure.getMessage());
        }
        if (!leftovers.isEmpty()) {
            logger.info("Removed " + restored + " of " + leftovers.size()
                    + " temporary blocks left in the world by an unclean shutdown.");
        }
        return restored;
    }

    static int restore(List<Entry> leftovers, Function<String, World> worlds) {
        int restored = 0;
        for (Entry entry : leftovers) {
            World world = worlds.apply(entry.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (block.getType() == entry.placed()) {
                block.setBlockData(parseBlockData(entry.previous()), false);
                restored++;
            }
        }
        return restored;
    }

    private static BlockData parseBlockData(String data) {
        try {
            return Bukkit.createBlockData(data);
        } catch (IllegalArgumentException invalid) {
            Material material = Material.matchMaterial(data.replaceFirst("\\[.*$", ""));
            return (material != null && material.isBlock() ? material : Material.AIR).createBlockData();
        }
    }

    /**
     * Records that the plugin is about to replace {@code block} with {@code placed}. When the
     * block is already journaled, its original previous block is kept.
     */
    public void record(Block block, Material placed) {
        String world = block.getWorld().getName();
        String key = Entry.key(world, block.getX(), block.getY(), block.getZ());
        Entry existing = entries.get(key);
        String previous = existing != null ? existing.previous() : block.getBlockData().getAsString();
        entries.put(key, new Entry(world, block.getX(), block.getY(), block.getZ(), placed, previous));
        scheduleFlush();
    }

    /** Forgets a block after the plugin restored it. */
    public void forget(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (entries.remove(Entry.key(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ())) != null) {
            scheduleFlush();
        }
    }

    private void scheduleFlush() {
        if (closed || flushScheduled) {
            return;
        }
        if (!plugin.isEnabled()) {
            flush();
            return;
        }
        flushScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            flushScheduled = false;
            flush();
        });
    }

    private void flush() {
        String snapshot = entries.isEmpty() ? null : serialize(new ArrayList<>(entries.values()));
        writer.execute(() -> write(snapshot));
    }

    private void write(String snapshot) {
        try {
            if (snapshot == null) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, snapshot, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            logger.warning("Could not write " + FILE_NAME + ": " + failure.getMessage());
        }
    }

    /** Writes the final state synchronously and stops the writer thread. Called on disable. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        String snapshot = entries.isEmpty() ? null : serialize(new ArrayList<>(entries.values()));
        writer.execute(() -> write(snapshot));
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("Timed out while writing " + FILE_NAME + ".");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void moveAside() {
        try {
            Files.move(file, file.resolveSibling(FILE_NAME + ".damaged-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // The next write replaces the damaged file anyway.
        }
    }

    static String serialize(List<Entry> entries) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("world", entry.world());
            block.put("x", entry.x());
            block.put("y", entry.y());
            block.put("z", entry.z());
            block.put("placed", entry.placed().name());
            block.put("previous", entry.previous());
            blocks.add(block);
        }
        yaml.set("blocks", blocks);
        return yaml.saveToString();
    }

    static List<Entry> parse(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = ConfigFileStore.parse(text);
        List<Entry> result = new ArrayList<>();
        for (Map<?, ?> block : yaml.getMapList("blocks")) {
            Object world = block.get("world");
            Material placed = Material.matchMaterial(String.valueOf(block.get("placed")));
            Object previous = block.get("previous");
            if (world == null || placed == null || previous == null
                    || !(block.get("x") instanceof Number x)
                    || !(block.get("y") instanceof Number y)
                    || !(block.get("z") instanceof Number z)) {
                continue;
            }
            result.add(new Entry(world.toString(), x.intValue(), y.intValue(), z.intValue(), placed, previous.toString()));
        }
        return result;
    }

    /** Visible for tests: the currently journaled blocks. */
    List<Entry> entries() {
        return List.copyOf(entries.values());
    }
}
