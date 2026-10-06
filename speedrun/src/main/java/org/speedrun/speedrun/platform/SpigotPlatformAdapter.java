package org.speedrun.speedrun.platform;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;

import java.util.concurrent.CompletableFuture;

/**
 * Spigot implementation. Uses the BungeeCord chat API that Spigot adds to Bukkit for
 * action-bar messages; chat and tab-list text use legacy formatting.
 *
 * <p>This class references {@code Player.Spigot} and must only be loaded after
 * {@link PlatformDetector} confirmed that the Spigot API is present.</p>
 */
class SpigotPlatformAdapter implements PlatformAdapter {
    private final String name;

    SpigotPlatformAdapter(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
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
        return true;
    }

    @Override
    public boolean supportsHiddenScoreNumbers() {
        return false;
    }

    @Override
    public void sendMessage(CommandSender sender, PlatformText text) {
        String url = text.clickUrl();
        if (url != null && sender instanceof Player player) {
            // Legacy text cannot carry click events; attach the link to the whole message instead.
            TextComponent message = new TextComponent(TextComponent.fromLegacy(text.legacy()));
            message.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            player.spigot().sendMessage(message);
            return;
        }
        sender.sendMessage(text.legacy());
    }

    @Override
    public void sendActionBar(Player player, PlatformText text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(text.legacy()));
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
        // Spigot has no API for score number formats.
    }
}
