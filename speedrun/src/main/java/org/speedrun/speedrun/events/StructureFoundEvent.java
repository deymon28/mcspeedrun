package org.speedrun.speedrun.events;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A custom Bukkit event that is called whenever a key structure is found during the speedrun.
 * This allows other parts of the plugin (like the logger or task manager) to react to this event.
 */
public class StructureFoundEvent extends Event {
    // Standard Bukkit event handler list required for custom events.
    private static final HandlerList handlers = new HandlerList();

    private final Player player;
    private final String structureKey;
    private final Location location;

    /**
     * Constructs a new StructureFoundEvent.
     *
     * @param player The player who found the structure.
     * @param structureKey The unique identifier for the structure (e.g., "VILLAGE").
     * @param location The location where the structure was found.
     */
    public StructureFoundEvent(@Nullable Player player, String structureKey, Location location){
        this.player = player;
        this.structureKey = structureKey;
        this.location = location;
    }

    /**
     * @return The player who found the structure.
     */
    public Player getPlayer() { return player; }

    /**
     * @return The unique key of the found structure.
     */
    public String getStructureKey() { return structureKey; }

    /**
     * @return The location of the found structure.
     */
    public Location getLocation() { return location; }

    /**
     * Standard Bukkit method to get the handlers for this event.
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    /**
     * Standard Bukkit static method to get the handler list.
     */
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
