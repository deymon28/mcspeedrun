package org.speedrun.speedrun.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Formatted text handed to a {@link PlatformAdapter}.
 *
 * <p>The plugin bundles a relocated copy of Adventure, so its component objects can never be
 * passed to a server's native Adventure API. Adapters therefore receive text in a portable
 * form: MiniMessage for servers with native Adventure (Paper) and legacy section-sign text for
 * Bukkit/Spigot. Both forms are rendered lazily and cached.</p>
 */
public final class PlatformText {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final PlatformText EMPTY = new PlatformText(Component.empty());

    private final Component component;
    private String miniMessage;
    private String legacy;

    private PlatformText(Component component) {
        this.component = component;
    }

    public static PlatformText of(Component component) {
        return component == null ? EMPTY : new PlatformText(component);
    }

    public static PlatformText empty() {
        return EMPTY;
    }

    /** Serialized MiniMessage, suitable for native Adventure deserialization. */
    public String miniMessage() {
        String value = miniMessage;
        if (value == null) {
            value = MINI_MESSAGE.serialize(component);
            miniMessage = value;
        }
        return value;
    }

    /**
     * The first "open URL" click target in the text, or null. Lets adapters without native
     * components keep links clickable.
     */
    public String clickUrl() {
        return findUrl(component);
    }

    private static String findUrl(Component node) {
        ClickEvent click = node.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.OPEN_URL
                && click.payload() instanceof ClickEvent.Payload.Text text) {
            return text.value();
        }
        for (Component child : node.children()) {
            String url = findUrl(child);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /** Legacy text using section-sign color codes and the Bukkit hex color format. */
    public String legacy() {
        String value = legacy;
        if (value == null) {
            value = LEGACY.serialize(component);
            legacy = value;
        }
        return value;
    }
}
