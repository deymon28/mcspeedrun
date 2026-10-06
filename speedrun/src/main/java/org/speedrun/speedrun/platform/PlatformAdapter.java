package org.speedrun.speedrun.platform;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;

import java.util.concurrent.CompletableFuture;

/**
 * Server-software specific operations.
 *
 * <p>The common plugin code only depends on the Bukkit API. Anything that differs between
 * CraftBukkit, Spigot, and Paper is routed through one implementation of this interface,
 * chosen once at startup by {@link PlatformDetector}. Signatures must not mention Adventure
 * types: the Paper implementation is compiled against native Adventure while the common code
 * uses a relocated copy.</p>
 *
 * <p>All methods are called on the server thread unless stated otherwise.</p>
 */
public interface PlatformAdapter {

    /** Short server software name, for example {@code Paper}. */
    String name();

    /** Human-readable server build description for diagnostics. */
    String serverVersion();

    /** True when chunks can be loaded or generated without blocking the server thread. */
    boolean supportsAsyncChunkLoading();

    /** True when chat components are rendered by the server's native Adventure implementation. */
    boolean supportsNativeAdventure();

    /** True when action-bar messages can be shown. */
    boolean supportsActionBar();

    /** True when the red sidebar score numbers can be hidden. */
    boolean supportsHiddenScoreNumbers();

    void sendMessage(CommandSender sender, PlatformText text);

    void sendActionBar(Player player, PlatformText text);

    void setPlayerListHeaderFooter(Player player, PlatformText header, PlatformText footer);

    /**
     * Loads (and generates if needed) a chunk without blocking the server thread.
     * Only called when {@link #supportsAsyncChunkLoading()} is true. The returned future
     * must complete on the server thread.
     */
    CompletableFuture<Chunk> loadChunkAsync(World world, int chunkX, int chunkZ);

    /** Hides or restores sidebar score numbers when supported; otherwise does nothing. */
    void setScoreNumbersHidden(Objective objective, boolean hidden);
}
