package org.speedrun.speedrun.events;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

// custom event for log

public class StructureFoundEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final Player player;
    private final String structureKey;
    private final Location location;

    public StructureFoundEvent(Player player, String structureKey, Location location){
        this.player = player;
        this.structureKey = structureKey;
        this.location = location;
    }

    public Player getPlayer() { return player; }
    public String getStructureKey() { return structureKey; }
    public Location getLocation() { return location; }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
