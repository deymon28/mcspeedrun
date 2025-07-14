package org.speedrun.speedrun.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.speedrun.speedrun.utils.LocationUtil;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.events.StructureFoundEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the detection, storage, and state of key structures.
 * This includes handling complex logic for Nether Portals, which have linked locations in two dimensions.
 * |
 * Керує виявленням, зберіганням та станом ключових структур.
 * Включає обробку складної логіки для порталів у Незер, які мають пов'язані локації у двох вимірах.
 */
public class StructureManager {
    private final Speedrun plugin;
    // Stores locations of all tracked structures. Using LinkedHashMap to maintain insertion order.
    // Зберігає локації всіх відстежуваних структур. Використовується LinkedHashMap для збереження порядку додавання.
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();

    private boolean villageSearchFailed = false;

    // Separate locations for each side of the portal pair.
    // Окремі локації для кожної сторони пари порталів.
    private Location overworldPortalLocation;
    private Location netherPortalLocation;

    private Location predictedEndPortalLocation;

    public StructureManager(Speedrun plugin) {
        this.plugin = plugin;
        reset();
    }

    /**
     * Resets all found structure locations to their initial (null) state.
     * Called at the start of a new speedrun.
     * |
     * Скидає всі знайдені локації структур до початкового (null) стану.
     * Викликається на початку нового спідрану.
     */
    public void reset() {
        foundLocations.clear();
        predictedEndPortalLocation = null;
        overworldPortalLocation = null;
        netherPortalLocation = null;

        // Initialize the map with all structures that need to be tracked.
        // Ініціалізуємо мапу всіма структурами, які потрібно відстежувати.
        foundLocations.put("LAVA_POOL", null);
        foundLocations.put("VILLAGE", null);
        foundLocations.put("NETHER_PORTAL", null); // This key is a placeholder for display purposes. / Цей ключ є плейсхолдером для відображення.
        foundLocations.put("FORTRESS", null);
        foundLocations.put("BASTION", null);
        foundLocations.put("END_PORTAL", null);
    }

    /**
     * Registers a found structure (except for Nether Portals, which have special handling).
     * Fires a StructureFoundEvent and broadcasts a message.
     * |
     * Реєструє знайдену структуру (окрім порталів у Незер, які мають спеціальну обробку).
     * Викликає подію StructureFoundEvent та розсилає повідомлення.
     *
     * @param player The player who found the structure. / Гравець, який знайшов структуру.
     * @param key The unique key of the structure (e.g., "VILLAGE"). / Унікальний ключ структури (напр., "VILLAGE").
     * @param loc The location of the structure. / Місцезнаходження структури.
     */
    public void structureFound(Player player, String key, Location loc) {
        // Nether portals are handled by portalLit() and portalExitFound().
        // Портали в Незер обробляються методами portalLit() та portalExitFound().
        if (key.equals("NETHER_PORTAL")) {
            return;
        }

        foundLocations.put(key, loc);
        plugin.getGameManager().getLogger().info("Structure '" + key + "' found/updated by " + player.getName() + " at " + LocationUtil.format(loc));

        Bukkit.getPluginManager().callEvent(new StructureFoundEvent(player, key, loc));
        plugin.getTaskManager().onStructureFound(key, player);
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);

        String displayName = getLocalizedStructureName(key);
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                "%player%", player.getName(),
                "%structure%", displayName,
                "%coords%", LocationUtil.format(loc)));
    }

    // =========================================================================================
    // Nether Portal Specific Methods
    // =========================================================================================

    /**
     * Registers a newly lit Nether Portal. This becomes the new "main" portal pair.
     * It resets any previously found portal locations.
     * |
     * Реєструє щойно запалений портал у Незер. Він стає новою "основною" парою порталів.
     * Скидає будь-які раніше знайдені локації порталів.
     *
     * @param player The player who lit the portal, or null if lit by environment. / Гравець, що запалив портал, або null, якщо запалено оточенням.
     * @param loc The location of a portal block. / Місцезнаходження блоку порталу.
     */
    public void portalLit(Player player, Location loc) {
        // То є в певній мірі костиль
        // Перевіряємо, чи цей портал вже відомий
        for (Location knownLoc : Arrays.asList(overworldPortalLocation, netherPortalLocation)) {
            if (knownLoc != null && knownLoc.distance(loc) < 4.0) {
                return; // Портал вже зареєстровано
            }
        }


        if (isPortalFullyFound() && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            String message = "Portal reassignment is disabled in the config.";
            if (player != null) player.sendMessage(Component.text(message, NamedTextColor.RED));
            plugin.getLogger().info("Blocked portal reassignment: " + message);
            return;
        }

        // Determine which dimension the portal was lit in.
        // Визначаємо, в якому вимірі було запалено портал.
        World.Environment world = (player != null) ? player.getWorld().getEnvironment() : loc.getWorld().getEnvironment();

        // Reset both locations to establish a new primary portal pair.
        // Скидаємо обидві локації, щоб створити нову основну пару порталів.
        this.overworldPortalLocation = null;
        this.netherPortalLocation = null;

        if (world == World.Environment.NORMAL) {
            this.overworldPortalLocation = loc;
        } else if (world == World.Environment.NETHER) {
            this.netherPortalLocation = loc;
        } else {
            return; // Portals in other dimensions are not tracked. / Портали в інших вимірах не відстежуються.
        }

        // Update the placeholder for scoreboard display.
        // Оновлюємо плейсхолдер для відображення на скорборді.
        foundLocations.put("NETHER_PORTAL", loc);

        String playerName = (player != null) ? player.getName() : "GAME_WORLD";
        plugin.getGameManager().getLogger().info("Nether Portal lit by " + playerName + " in " + world.name() + " at " + LocationUtil.format(loc));

        plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD", player);
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", playerName));
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);

        // Update scoreboards for all players to reflect the new portal location.
        // Оновлюємо скорборд для всіх гравців, щоб відобразити нову локацію порталу.
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    /**
     * Registers the location of a portal on the destination side after teleportation.
     * This only sets the location if it hasn't been discovered yet.
     * |
     * Реєструє місцезнаходження порталу на стороні призначення після телепортації.
     * Встановлює локацію, тільки якщо її ще не було виявлено.
     *
     * @param exitLoc The precise location of the exit portal. / Точне місцезнаходження вихідного порталу.
     */
    public void portalExitFound(Location exitLoc) {
        World.Environment exitWorld = exitLoc.getWorld().getEnvironment();
        if (exitWorld == World.Environment.NETHER && this.netherPortalLocation == null) {
            this.netherPortalLocation = exitLoc;
            plugin.getGameManager().getLogger().info("Nether Portal exit (Nether-side) found at " + LocationUtil.format(exitLoc));
        } else if (exitWorld == World.Environment.NORMAL && this.overworldPortalLocation == null) {
            this.overworldPortalLocation = exitLoc;
            plugin.getGameManager().getLogger().info("Nether Portal exit (Overworld-side) found at " + LocationUtil.format(exitLoc));
        }
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    /**
     * Handles the logic for the `/run new nether portal` command.
     * |
     * Обробляє логіку команди `/run new nether portal`.
     *
     * @param player The player executing the command. / Гравець, що виконує команду.
     * @return True if the portal was successfully reassigned. / True, якщо портал було успішно перепризначено.
     */
    public boolean reassignNetherPortal(Player player) {
        if (!plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage("§cReassigning locations is disabled in the config.");
            return false;
        }
        // Use the existing portalLit method which contains the reset logic.
        // Використовуємо існуючий метод portalLit, який містить логіку скидання.
        portalLit(player, player.getLocation());
        player.sendMessage("§aPortal coordinates have been reset to your current location.");
        return true;
    }

    /**
     * Updates a structure's location via an admin command.
     * |
     * Оновлює локацію структури через команду адміністратора.
     *
     * @param naturalName The user-friendly name of the structure (e.g., "Bastion Remnant"). / Зрозуміла назва структури (напр., "Bastion Remnant").
     * @param newLocation The new location to set. / Нова локація для встановлення.
     * @param player The player who issued the command. / Гравець, що видав команду.
     * @return True on success. / True у разі успіху.
     */
    public boolean updateStructureLocation(String naturalName, Location newLocation, Player player) {
        String key = naturalName.replace(' ', '_').toUpperCase();

        if (!foundLocations.containsKey(key)) {
            player.sendMessage(plugin.getConfigManager().getFormatted("messages.unknown-structure-name", "%name%", naturalName));
            return false;
        }

        if (foundLocations.get(key) != null && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage("§cReassigning locations is disabled in the config.");
            return false;
        }

        if (key.equals("NETHER_PORTAL")) {
            player.sendMessage("§cUse '/run new nether portal' to reassign the portal.");
            return false;
        }
        // Use the standard structureFound method to ensure all logic (events, messages) is triggered.
        // Використовуємо стандартний метод structureFound, щоб гарантувати спрацювання всієї логіки (події, повідомлення).
        structureFound(player, key, newLocation);
        return true;
    }


    // =========================================================================================
    // Getters & State Checks
    // =========================================================================================

    /** @return The location of the Overworld-side portal, or null if not found. / Локація порталу у Звичайному світі, або null, якщо не знайдено. */
    public Location getOverworldPortalLocation() { return overworldPortalLocation; }

    /** @return The location of the Nether-side portal, or null if not found. / Локація порталу в Незері, або null, якщо не знайдено. */
    public Location getNetherPortalLocation() { return netherPortalLocation; }

    /**
     * Gets the appropriate portal location to display on the scoreboard based on the player's current dimension.
     * Отримує відповідну локацію порталу для відображення на скорборді, залежно від поточного виміру гравця.
     */
    public Location getPortalLocationForWorld(World.Environment world) {
        if (world == World.Environment.NORMAL) return overworldPortalLocation;
        if (world == World.Environment.NETHER) return netherPortalLocation;
        return null;
    }

    /** @return True if at least one side of the Nether Portal pair has been found. / True, якщо знайдено хоча б одну сторону пари порталів у Незер. */
    public boolean isPortalPartiallyFound() {
        return overworldPortalLocation != null || netherPortalLocation != null;
    }

    /** @return True if both sides of the Nether Portal pair have been found. / True, якщо знайдено обидві сторони пари порталів у Незер. */
    public boolean isPortalFullyFound() {
        return overworldPortalLocation != null && netherPortalLocation != null;
    }

    /** @return A map of all tracked structures and their locations. / Мапа всіх відстежуваних структур та їхніх локацій. */
    public Map<String, Location> getFoundStructures() {
        return foundLocations;
    }

    /** Gets the localized, user-friendly name for a structure key. / Отримує локалізовану, зрозумілу назву для ключа структури. */
    public String getLocalizedStructureName(String key) {
        return plugin.getConfigManager().getLangString("structures." + key, key);
    }

    /** Checks if the village search timer has expired. / Перевіряє, чи не сплив час таймера пошуку села. */
    public void checkVillageTimeout() {
        if (isVillageSearchActive() && plugin.getGameManager().getVillageTimeRemaining() <= 0) {
            if (!villageSearchFailed) { // Перевіряємо, чи не було вже повідомлення
                foundLocations.remove("VILLAGE", null); // Reset the search. / Скидаємо пошук.
                Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));

                villageSearchFailed = true; // Забороняємо повторні повідомлення
            }
        }
    }

    /** @return True if the plugin is currently actively searching for a village. / True, якщо плагін наразі активно шукає село. */
    public boolean isVillageSearchActive() {
        return plugin.getGameManager().getVillageTimeElapsed() < plugin.getConfigManager().getVillageTimeout() && !villageSearchFailed;
        //return foundLocations.containsKey("VILLAGE") && foundLocations.get("VILLAGE") == null;
    }

    public boolean isVillageSearchFailed() {
        return villageSearchFailed;
    }

    /** @return True if the plugin is actively searching for a lava pool. / True, якщо плагін активно шукає озеро лави. */
    public boolean isLavaPoolSearchActive() {
        // Search for lava only if a portal hasn't been found yet.
        // Шукаємо лаву, тільки якщо портал ще не знайдено.
        return foundLocations.containsKey("LAVA_POOL") && foundLocations.get("LAVA_POOL") == null && !isPortalPartiallyFound();
    }

    /** @return The predicted location of the End Portal from triangulation, or null. / Передбачена локація порталу в Край з тріангуляції, або null. */
    public Location getPredictedEndPortalLocation() {
        return predictedEndPortalLocation;
    }

    /** Sets the predicted location of the End Portal. / Встановлює передбачену локацію порталу в Край. */
    public void setPredictedEndPortalLocation(Location location) {
        this.predictedEndPortalLocation = location;
    }
}