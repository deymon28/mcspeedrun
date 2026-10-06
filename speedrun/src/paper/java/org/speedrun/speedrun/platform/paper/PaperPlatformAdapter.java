package org.speedrun.speedrun.platform.paper;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.speedrun.speedrun.platform.PlatformAdapter;
import org.speedrun.speedrun.platform.PlatformText;

import java.util.concurrent.CompletableFuture;

/**
 * Paper implementation using native Adventure components, asynchronous chunk loading,
 * and Paper's score number formats.
 *
 * <p>This class lives in the separate {@code paper} source set. It is compiled against the
 * Paper API, packaged without relocation, and instantiated reflectively by
 * {@code PlatformDetector} only after Paper has been detected, so Bukkit/Spigot servers never
 * load it. It must only exchange plain Java and Bukkit types with the common code.</p>
 */
public final class PaperPlatformAdapter implements PlatformAdapter {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final String serverVersion;

    public PaperPlatformAdapter() {
        ServerBuildInfo build = ServerBuildInfo.buildInfo();
        String buildNumber = build.buildNumber().isPresent() ? String.valueOf(build.buildNumber().getAsInt()) : "local";
        this.serverVersion = build.brandName() + " " + build.minecraftVersionId() + " build " + buildNumber;
    }

    @Override
    public String name() {
        return "Paper";
    }

    @Override
    public String serverVersion() {
        return serverVersion;
    }

    @Override
    public boolean supportsAsyncChunkLoading() {
        return true;
    }

    @Override
    public boolean supportsNativeAdventure() {
        return true;
    }

    @Override
    public boolean supportsActionBar() {
        return true;
    }

    @Override
    public boolean supportsHiddenScoreNumbers() {
        return true;
    }

    private Component component(PlatformText text) {
        return miniMessage.deserialize(text.miniMessage());
    }

    @Override
    public void sendMessage(CommandSender sender, PlatformText text) {
        sender.sendMessage(component(text));
    }

    @Override
    public void sendActionBar(Player player, PlatformText text) {
        player.sendActionBar(component(text));
    }

    @Override
    public void setPlayerListHeaderFooter(Player player, PlatformText header, PlatformText footer) {
        player.sendPlayerListHeaderAndFooter(component(header), component(footer));
    }

    @Override
    public CompletableFuture<Chunk> loadChunkAsync(World world, int chunkX, int chunkZ) {
        // Paper completes this future on the server thread.
        return world.getChunkAtAsync(chunkX, chunkZ, true);
    }

    @Override
    public void setScoreNumbersHidden(Objective objective, boolean hidden) {
        objective.numberFormat(hidden ? NumberFormat.blank() : null);
    }
}
