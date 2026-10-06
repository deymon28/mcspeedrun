package org.speedrun.speedrun.platform;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Plain CraftBukkit implementation (no Spigot or Paper API).
 *
 * <p>The Bukkit API has no action-bar method. When the CraftBukkit internals expected for
 * Minecraft 26.x are present, action-bar text is delivered through them; otherwise the
 * action bar is disabled and the plugin keeps working without the navigation HUD.</p>
 */
class CraftBukkitPlatformAdapter implements PlatformAdapter {
    private final Logger logger;
    private final ActionBarBridge actionBar;
    private boolean actionBarFailureReported;

    CraftBukkitPlatformAdapter(Logger logger) {
        this.logger = logger;
        this.actionBar = ActionBarBridge.tryCreate(Bukkit.getServer().getClass());
        if (actionBar == null) {
            logger.warning("CraftBukkit action-bar internals were not found. The compass distance HUD is disabled; "
                    + "use Spigot or Paper for full functionality.");
        }
    }

    @Override
    public String name() {
        return "CraftBukkit";
    }

    @Override
    public String serverVersion() {
        return Bukkit.getVersion();
    }

    @Override
    public boolean supportsAsyncChunkLoading() {
        return false;
    }

    @Override
    public boolean supportsNativeAdventure() {
        return false;
    }

    @Override
    public boolean supportsActionBar() {
        return actionBar != null;
    }

    @Override
    public boolean supportsHiddenScoreNumbers() {
        return false;
    }

    @Override
    public void sendMessage(CommandSender sender, PlatformText text) {
        sender.sendMessage(text.legacy());
    }

    @Override
    public void sendActionBar(Player player, PlatformText text) {
        if (actionBar == null) {
            return;
        }
        try {
            actionBar.send(player, text.legacy());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!actionBarFailureReported) {
                actionBarFailureReported = true;
                logger.warning("CraftBukkit action-bar delivery failed (" + failure.getClass().getSimpleName()
                        + "). The compass distance HUD may be unavailable on this server build.");
            }
        }
    }

    @Override
    public void setPlayerListHeaderFooter(Player player, PlatformText header, PlatformText footer) {
        player.setPlayerListHeaderFooter(header.legacy(), footer.legacy());
    }

    @Override
    public CompletableFuture<Chunk> loadChunkAsync(World world, int chunkX, int chunkZ) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Async chunk loading requires Paper"));
    }

    @Override
    public void setScoreNumbersHidden(Objective objective, boolean hidden) {
        // Not available in the Bukkit API.
    }

    /** Reflective access to CraftBukkit's system-message overlay, resolved once. */
    private record ActionBarBridge(Method fromJson, Method getHandle, Method sendSystemMessage) {
        static ActionBarBridge tryCreate(Class<?> serverClass) {
            try {
                ClassLoader loader = serverClass.getClassLoader();
                String craftPackage = serverClass.getPackageName();
                Class<?> messages = Class.forName(craftPackage + ".util.CraftChatMessage", false, loader);
                Class<?> craftPlayer = Class.forName(craftPackage + ".entity.CraftPlayer", false, loader);
                Method fromJson = messages.getMethod("fromJSON", String.class);
                Method getHandle = craftPlayer.getMethod("getHandle");
                Method send = getHandle.getReturnType().getMethod("sendSystemMessage", fromJson.getReturnType(), boolean.class);
                return new ActionBarBridge(fromJson, getHandle, send);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
                return null;
            }
        }

        void send(Player player, String legacyText) throws ReflectiveOperationException {
            // The client renders section-sign formatting inside literal text components.
            String json = new JSONObject().put("text", legacyText).toString();
            sendSystemMessage.invoke(getHandle.invoke(player), fromJson.invoke(null, json), true);
        }
    }
}
