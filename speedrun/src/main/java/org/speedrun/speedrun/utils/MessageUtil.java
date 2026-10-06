package org.speedrun.speedrun.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.speedrun.speedrun.platform.PlatformText;
import org.speedrun.speedrun.platform.ServerPlatform;

import java.util.regex.Pattern;

/**
 * Centralized message helper for legacy color codes, MiniMessage strings, and
 * send/broadcast/action-bar delivery.
 *
 * <p>Delivery goes through the active {@link ServerPlatform}, which renders text natively on
 * Paper and as legacy text on Bukkit/Spigot. Without a platform (unit tests, early startup)
 * messages fall back to plain Bukkit legacy text.</p>
 */
public final class MessageUtil {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    /** Recognizes MiniMessage tags such as {@code <green>}, {@code <#ff8800>}, or {@code <bold>}. */
    private static final Pattern MINI_MESSAGE_TAG = Pattern.compile(
            "</?(?:#[0-9a-fA-F]{6}|[a-z_]+(?::[^<>]*)?)>");

    private static volatile ServerPlatform platform;

    private MessageUtil() {
    }

    /** Installs the delivery platform; pass null on disable. */
    public static void setPlatform(ServerPlatform serverPlatform) {
        platform = serverPlatform;
    }

    /**
     * Parses a raw message into a component. Legacy {@code &} and {@code §} color codes are
     * supported for existing configs; strings containing MiniMessage tags are parsed through
     * MiniMessage after legacy codes were converted.
     *
     * @param rawMessage raw config or code-provided message
     * @return parsed component
     */
    public static Component parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return Component.empty();
        }

        if (looksLikeMiniMessage(rawMessage)) {
            try {
                // Convert legacy codes first so mixed formats keep working.
                String converted = MINI_MESSAGE.serialize(LEGACY_AMPERSAND.deserialize(normalizeSection(rawMessage)));
                return MINI_MESSAGE.deserialize(unescapeTags(converted));
            } catch (RuntimeException ignored) {
                // Fall through to legacy parsing.
            }
        }

        return LEGACY_AMPERSAND.deserialize(normalizeSection(rawMessage));
    }

    /** Renders a raw message into legacy section-sign text (scoreboards, item names, lore). */
    public static String toLegacy(String rawMessage) {
        return LEGACY_SECTION.serialize(parse(rawMessage));
    }

    /** Renders a component into legacy section-sign text. */
    public static String toLegacy(Component component) {
        return component == null ? "" : LEGACY_SECTION.serialize(component);
    }

    /** Removes all formatting and returns the visible text. */
    public static String stripFormatting(String rawMessage) {
        StringBuilder plain = new StringBuilder();
        String legacy = toLegacy(rawMessage);
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == LegacyComponentSerializer.SECTION_CHAR && i + 1 < legacy.length()) {
                i++;
                continue;
            }
            plain.append(c);
        }
        return plain.toString();
    }

    public static void send(CommandSender sender, String rawMessage) {
        send(sender, parse(rawMessage));
    }

    public static void send(CommandSender sender, Component message) {
        if (sender == null || message == null) {
            return;
        }
        PlatformText text = PlatformText.of(message);
        ServerPlatform current = platform;
        if (current != null) {
            current.sendMessage(sender, text);
        } else {
            sender.sendMessage(text.legacy());
        }
    }

    /** Broadcasts raw text to all online players and the console. */
    public static void broadcast(String rawMessage) {
        broadcast(parse(rawMessage));
    }

    /** Broadcasts a component to all online players and the console. */
    public static void broadcast(Component message) {
        if (message == null) {
            return;
        }
        PlatformText text = PlatformText.of(message);
        ServerPlatform current = platform;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (current != null) {
                current.sendMessage(player, text);
            } else {
                player.sendMessage(text.legacy());
            }
        }
        if (current != null) {
            current.sendMessage(Bukkit.getConsoleSender(), text);
        } else {
            Bukkit.getConsoleSender().sendMessage(text.legacy());
        }
    }

    public static void actionBar(Player player, String rawMessage) {
        actionBar(player, parse(rawMessage));
    }

    public static void actionBar(Player player, Component message) {
        ServerPlatform current = platform;
        if (player == null || message == null || current == null) {
            return;
        }
        current.sendActionBar(player, PlatformText.of(message));
    }

    public static void playerListHeaderFooter(Player player, String rawHeader, String rawFooter) {
        ServerPlatform current = platform;
        if (player == null) {
            return;
        }
        PlatformText header = PlatformText.of(parse(rawHeader));
        PlatformText footer = PlatformText.of(parse(rawFooter));
        if (current != null) {
            current.setPlayerListHeaderFooter(player, header, footer);
        } else {
            player.setPlayerListHeaderFooter(header.legacy(), footer.legacy());
        }
    }

    static boolean looksLikeMiniMessage(String input) {
        return input.indexOf('<') >= 0 && MINI_MESSAGE_TAG.matcher(input).find();
    }

    private static String normalizeSection(String input) {
        return input.indexOf(LegacyComponentSerializer.SECTION_CHAR) >= 0
                ? input.replace(LegacyComponentSerializer.SECTION_CHAR, '&')
                : input;
    }

    /** MiniMessage escapes literal tags when serializing; restore the author's tags. */
    private static String unescapeTags(String serialized) {
        return serialized.replace("\\<", "<");
    }
}
