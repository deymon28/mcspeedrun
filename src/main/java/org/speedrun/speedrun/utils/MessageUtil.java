package org.speedrun.speedrun.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Centralized message helper for legacy color codes, MiniMessage strings,
 * Adventure components, and global send/broadcast operations.
 */
public final class MessageUtil {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private MessageUtil() {
    }

    /**
     * Parses a raw message into an Adventure component.
     * Legacy ampersand color codes are supported for existing configs; strings
     * containing MiniMessage tags are parsed through MiniMessage first.
     *
     * @param rawMessage raw config or code-provided message
     * @return parsed Adventure component
     */
    public static Component parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return Component.empty();
        }

        String trimmed = rawMessage.trim();
        if (looksLikeMiniMessage(trimmed)) {
            try {
                return MINI_MESSAGE.deserialize(trimmed);
            } catch (Exception ignored) {
                // Fall through to legacy parsing.
            }
        }

        return LEGACY_AMPERSAND.deserialize(rawMessage);
    }

    /**
     * Sends a raw message to any Bukkit command sender.
     *
     * @param sender player, console, or command block sender
     * @param rawMessage legacy or MiniMessage text
     */
    public static void send(CommandSender sender, String rawMessage) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(parse(rawMessage));
    }

    /**
     * Sends a prebuilt Adventure component to a player.
     *
     * @param player recipient
     * @param message component to send
     */
    public static void send(Player player, Component message) {
        if (player == null || message == null) {
            return;
        }
        player.sendMessage(message);
    }

    /**
     * Sends a raw message to a player.
     *
     * @param player recipient
     * @param rawMessage legacy or MiniMessage text
     */
    public static void send(Player player, String rawMessage) {
        if (player == null) {
            return;
        }
        player.sendMessage(parse(rawMessage));
    }

    /**
     * Broadcasts raw text to all online players and the console.
     *
     * @param rawMessage legacy or MiniMessage text
     */
    public static void broadcast(String rawMessage) {
        Component component = parse(rawMessage);
        Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(component));
        Bukkit.getConsoleSender().sendMessage(component);
    }

    /**
     * Broadcasts a component to all online players and the console.
     *
     * @param message component to broadcast
     */
    public static void broadcast(Component message) {
        if (message == null) {
            return;
        }
        Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message));
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /**
     * Sends a raw action-bar message to one player.
     *
     * @param player recipient
     * @param rawMessage legacy or MiniMessage text
     */
    public static void actionBar(Player player, String rawMessage) {
        if (player == null) {
            return;
        }
        player.sendActionBar(parse(rawMessage));
    }

    /**
     * Sends a component action-bar message to one player.
     *
     * @param player recipient
     * @param message component to display
     */
    public static void actionBar(Player player, Component message) {
        if (player == null || message == null) {
            return;
        }
        player.sendActionBar(message);
    }

    private static boolean looksLikeMiniMessage(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.contains("<red>")
                || lower.contains("<green>")
                || lower.contains("<yellow>")
                || lower.contains("<gold>")
                || lower.contains("<gray>")
                || lower.contains("<white>")
                || lower.contains("<bold>")
                || lower.contains("<italic>")
                || lower.contains("<underlined>");
    }
}
