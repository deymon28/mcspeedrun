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
 * |
 * Кастомна подія Bukkit, яка викликається щоразу, коли під час спідрану знаходять ключову структуру.
 * Це дозволяє іншим частинам плагіна (наприклад, логеру або менеджеру завдань) реагувати на цю подію.
 */
public class StructureFoundEvent extends Event {
    // Standard Bukkit event handler list required for custom events.
    // Стандартний список обробників Bukkit, необхідний для кастомних подій.
    private static final HandlerList handlers = new HandlerList();

    private final Player player;
    private final String structureKey;
    private final Location location;

    /**
     * Constructs a new StructureFoundEvent.
     * |
     * Створює нову подію StructureFoundEvent.
     *
     * @param player The player who found the structure. / Гравець, який знайшов структуру.
     * @param structureKey The unique identifier for the structure (e.g., "VILLAGE"). / Унікальний ідентифікатор структури (напр., "VILLAGE").
     * @param location The location where the structure was found. / Місцезнаходження, де було знайдено структуру.
     */
    public StructureFoundEvent(@Nullable Player player, String structureKey, Location location){
        this.player = player;
        this.structureKey = structureKey;
        this.location = location;
    }

    /**
     * @return The player who found the structure. / Гравець, який знайшов структуру.
     */
    public Player getPlayer() { return player; }

    /**
     * @return The unique key of the found structure. / Унікальний ключ знайденої структури.
     */
    public String getStructureKey() { return structureKey; }

    /**
     * @return The location of the found structure. / Місцезнаходження знайденої структури.
     */
    public Location getLocation() { return location; }

    /**
     * Standard Bukkit method to get the handlers for this event.
     * Стандартний метод Bukkit для отримання обробників цієї події.
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    /**
     * Standard Bukkit static method to get the handler list.
     * Стандартний статичний метод Bukkit для отримання списку обробників.
     */
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
