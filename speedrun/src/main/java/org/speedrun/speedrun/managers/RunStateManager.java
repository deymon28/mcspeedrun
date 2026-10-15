package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.config.ConfigFileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Keeps an active run across server restarts and crashes ({@code settings.resume-after-restart}).
 *
 * <p>While a run is active its state (timer, stage progress, found locations, splits) is saved to
 * {@code run-state.yml} every {@link #AUTOSAVE_SECONDS} seconds and when the server stops. On the
 * next start the run is restored paused and continues when the first player joins; the downtime
 * is not counted. A saved run is only restored into the same world, game mode, and stage setup.
 * The file is deleted when a run ends, is stopped, or is reset.</p>
 *
 * <p>Snapshots are built on the server thread; the file is written by one background thread in
 * the order the snapshots were taken.</p>
 */
public final class RunStateManager {
    static final String FILE_NAME = "run-state.yml";
    /** Seconds between automatic saves; bounds what a crash can lose. */
    public static final int AUTOSAVE_SECONDS = 30;
    private static final int FORMAT_VERSION = 1;

    private final Speedrun plugin;
    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Speedrun-RunState");
        thread.setDaemon(true);
        return thread;
    });

    public RunStateManager(Speedrun plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve(FILE_NAME);
    }

    private boolean isEnabled() {
        return plugin.getConfigManager().isResumeAfterRestartEnabled();
    }

    /** Saves the active run in the background (autosave). */
    public void saveAsync() {
        if (!isEnabled() || !plugin.getGameManager().isRunning()) {
            return;
        }
        String snapshot = snapshot();
        submit(() -> write(snapshot));
    }

    /** Saves the active run and waits for the file; used when the server stops. */
    public void saveNow() {
        if (!isEnabled() || !plugin.getGameManager().isRunning()) {
            return;
        }
        String snapshot = snapshot();
        submit(() -> write(snapshot));
        flush();
    }

    /** Forgets the saved run (it ended, was stopped, or was reset). */
    public void delete() {
        submit(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException failure) {
                plugin.getLogger().warning("Could not delete " + FILE_NAME + ": " + failure.getMessage());
            }
        });
    }

    /**
     * Restores a saved run during plugin startup.
     *
     * @return true when a run was restored (the caller must then not start a new one)
     */
    public boolean restoreOnStartup() {
        if (!Files.exists(file)) {
            return false;
        }
        if (!isEnabled()) {
            delete();
            return false;
        }
        YamlConfiguration state;
        try {
            state = ConfigFileStore.read(file);
        } catch (IOException | InvalidConfigurationException failure) {
            plugin.getLogger().warning("The saved run could not be read (" + failure.getMessage() + ") and was discarded.");
            delete();
            return false;
        }
        String problem = incompatibility(state);
        if (problem != null) {
            plugin.getLogger().warning("The saved run was not restored: " + problem + ".");
            delete();
            return false;
        }
        plugin.getGameManager().resumeRun(state);
        plugin.getLogger().info("Restored the run saved at " + plugin.getGameManager().getFormattedTime()
                + ". It continues when the first player joins.");
        return true;
    }

    /** Why a saved run does not fit this server anymore, or null when it can be restored. */
    private String incompatibility(YamlConfiguration state) {
        if (state.getInt("format", 0) != FORMAT_VERSION) {
            return "it was saved in an unknown format";
        }
        if (!plugin.getConfigManager().getGameMode().name().equals(state.getString("run.gamemode"))) {
            return "the game mode changed";
        }
        String worldId = mainWorldId();
        if (worldId == null || !worldId.equals(state.getString("run.world"))) {
            return "the world changed";
        }
        if (!plugin.getTaskManager().progressionSignature().equals(state.getString("tasks.signature"))) {
            return "the stages or tasks changed";
        }
        return null;
    }

    private String snapshot() {
        YamlConfiguration state = new YamlConfiguration();
        state.options().setHeader(List.of(
                "Saved state of the active speedrun, restored after a server restart.",
                "Managed by the plugin; do not edit. Deleted when the run ends."));
        state.set("format", FORMAT_VERSION);
        state.set("saved-at", System.currentTimeMillis());
        state.set("plugin-version", plugin.getDescription().getVersion());
        state.set("run.gamemode", plugin.getConfigManager().getGameMode().name());
        state.set("run.world", mainWorldId());
        plugin.getGameManager().writeState(state.createSection("run.timer"));
        plugin.getTaskManager().writeState(state.createSection("tasks"));
        plugin.getStructureManager().writeState(state.createSection("structures"));
        plugin.getRecordsManager().writeState(state.createSection("records"));
        return state.saveToString();
    }

    private static String mainWorldId() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getUID().toString();
    }

    private void write(String snapshot) {
        try {
            ConfigFileStore.writeAtomically(file, snapshot);
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not save " + FILE_NAME + ": " + failure.getMessage());
        }
    }

    private void submit(Runnable task) {
        try {
            writer.execute(task);
        } catch (RejectedExecutionException closed) {
            // Shutting down: the final save already ran.
        }
    }

    private void flush() {
        try {
            writer.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            plugin.getLogger().warning("Timed out while saving " + FILE_NAME + ".");
        }
    }

    /** Waits for pending writes and stops the writer thread. Called on plugin disable. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out while saving " + FILE_NAME + ".");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================================
    // Location encoding shared by the managers
    // =========================================================================================

    /** Encodes a location as {@code world;x;y;z}, or an empty string for null. */
    static String encode(@Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return location.getWorld().getName() + ";" + location.getX() + ";" + location.getY() + ";" + location.getZ();
    }

    /** Decodes {@link #encode(Location)}; null for empty text, malformed text, or a missing world. */
    static @Nullable Location decode(@Nullable String text) {
        double[] coordinates = parseCoordinates(text);
        if (coordinates == null) {
            return null;
        }
        World world = Bukkit.getWorld(text.substring(0, text.indexOf(';')));
        return world == null ? null : new Location(world, coordinates[0], coordinates[1], coordinates[2]);
    }

    /** The x, y, z part of an encoded location, or null when malformed. */
    static double @Nullable [] parseCoordinates(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] parts = text.split(";");
        if (parts.length != 4 || parts[0].isEmpty()) {
            return null;
        }
        try {
            double[] coordinates = {Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
            for (double coordinate : coordinates) {
                if (!Double.isFinite(coordinate)) {
                    return null;
                }
            }
            return coordinates;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** Reads a string list stored by the managers, tolerating a missing key. */
    static List<String> strings(ConfigurationSection section, String path) {
        return section == null ? List.of() : section.getStringList(path);
    }
}
