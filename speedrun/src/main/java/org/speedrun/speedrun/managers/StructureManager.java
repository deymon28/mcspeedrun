package org.speedrun.speedrun.managers;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.generator.structure.Structure;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.StructureSearchResult;
import org.speedrun.speedrun.utils.LocationUtil;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.events.StructureFoundEvent;

import java.util.*;

/**
 * Manages the detection, storage, and state of key structures.
 * This includes handling complex logic for Nether Portals, which have linked locations in two dimensions.
 * |
 * Керує виявленням, зберіганням та станом ключових структур.
 * Включає обробку складної логіки для порталів у Незер, які мають пов'язані локації у двох вимірах.
 */
public class StructureManager {
    private static final String HIDDEN_LODESTONE_METADATA = "speedrun_hidden_lodestone";
    private final Speedrun plugin;
    // Stores locations of all tracked structures. Using LinkedHashMap to maintain insertion order.
    // Зберігає локації всіх відстежуваних структур. Використовується LinkedHashMap для збереження порядку додавання.
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();
    private final Map<String, Location> hiddenLodestones = new LinkedHashMap<>();
    private final Map<String, Material> hiddenLodestonePreviousBlocks = new HashMap<>();
    private final Set<String> disabledSearches = new HashSet<>();
    private BukkitTask preScanTask;
    private long preScanGeneration = 0;

    public boolean villageSearchFailed = false;

    // Separate locations for each side of the portal pair.
    // Окремі локації для кожної сторони пари порталів.
    private Location overworldPortalLocation;
    private Location netherPortalLocation;

    private Location predictedEndPortalLocation;
    private boolean predictedEndPortalApproximate;

    private final Set<String> hiddenStructures = new HashSet<>();

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
        cancelPreScan();
        clearAllHiddenLodestones();
        foundLocations.clear();
        hiddenStructures.clear();
        disabledSearches.clear();
        predictedEndPortalLocation = null;
        predictedEndPortalApproximate = false;
        overworldPortalLocation = null;
        netherPortalLocation = null;

        villageSearchFailed = false;

        // Initialize the map with all structures that need to be tracked.
        // Ініціалізуємо мапу всіма структурами, які потрібно відстежувати.
        foundLocations.put("LAVA_POOL", null);
        foundLocations.put("VILLAGE", null);
        foundLocations.put("NETHER_PORTAL", null); // This key is a placeholder for display purposes. / Цей ключ є плейсхолдером для відображення.
        foundLocations.put("FORTRESS", null);
        foundLocations.put("BASTION", null);
        foundLocations.put("END_PORTAL", null);
    }

    public void preScanRequiredStructures() {
        cancelPreScan();
        long generation = preScanGeneration;

        List<Runnable> scans = new ArrayList<>();
        World overworld = getWorld(World.Environment.NORMAL);
        World nether = getWorld(World.Environment.NETHER);

        if (overworld != null) {
            Location origin = getPreScanOrigin(overworld);
            scans.add(() -> {
                Location village = locateNearest(origin,
                        Structure.VILLAGE_PLAINS,
                        Structure.VILLAGE_DESERT,
                        Structure.VILLAGE_SAVANNA,
                        Structure.VILLAGE_SNOWY,
                        Structure.VILLAGE_TAIGA);
                if (village != null) {
                    registerPreScannedStructure(generation, "VILLAGE", village);
                }
            });

            scans.add(() -> {
                Location stronghold = locateNearest(origin, Structure.STRONGHOLD);
                if (stronghold != null) {
                    registerPreScannedStructure(generation, "END_PORTAL", stronghold);
                }
            });

            if (plugin.getConfigManager().isStartPreScanLavaEnabled()) {
                scans.add(() -> {
                    Location lavaPool = findLoadedLavaPool(origin);
                    if (lavaPool != null) {
                        registerPreScannedStructure(generation, "LAVA_POOL", lavaPool);
                    }
                });
            }
        }

        if (nether != null) {
            Location origin = getPreScanOrigin(nether);
            scans.add(() -> {
                Location fortress = locateNearest(origin, Structure.FORTRESS);
                if (fortress != null) {
                    registerPreScannedStructure(generation, "FORTRESS", fortress);
                }
            });

            scans.add(() -> {
                Location bastion = locateNearest(origin, Structure.BASTION_REMNANT);
                if (bastion != null) {
                    registerPreScannedStructure(generation, "BASTION", bastion);
                }
            });
        }

        if (scans.isEmpty()) {
            return;
        }

        Iterator<Runnable> iterator = scans.iterator();
        preScanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!iterator.hasNext()) {
                cancelPreScan();
                return;
            }
            iterator.next().run();
        }, 1L, 20L);
    }

    private World getWorld(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return world;
            }
        }
        return null;
    }

    private Location locateNearest(Location origin, Structure... structures) {
        int radius = plugin.getConfigManager().getStartPreScanRadiusChunks();
        Location nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Structure structure : structures) {
            StructureSearchResult result = origin.getWorld().locateNearestStructure(origin, structure, radius, false);
            if (result == null || result.getLocation() == null) {
                continue;
            }

            double distanceSquared = origin.distanceSquared(result.getLocation());
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = result.getLocation();
            }
        }

        return nearest;
    }

    private void registerPreScannedStructure(long generation, String key, Location location) {
        if (generation != preScanGeneration || location == null || location.getWorld() == null) {
            return;
        }

        if (location.getBlockY() != 0) {
            structureFound(null, key, location);
            return;
        }

        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        world.getChunkAtAsync(chunkX, chunkZ).thenAccept((Chunk ignored) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (generation != preScanGeneration || !plugin.getGameManager().isRunning()) {
                        return;
                    }
                    Location adjusted = location.clone();
                    adjusted.setY(world.getHighestBlockYAt(adjusted) + 1);
                    structureFound(null, key, adjusted);
                })
        ).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to resolve Y for pre-scanned " + key + ": " + ex.getMessage());
            return null;
        });
    }

    private Location getPreScanOrigin(World world) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == world.getEnvironment()) {
                return player.getLocation();
            }
        }
        return world.getSpawnLocation();
    }

    private Location findLoadedLavaPool(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        int radiusBlocks = plugin.getConfigManager().getStartPreScanRadius();
        int radiusChunks = Math.min(plugin.getConfigManager().getStartPreScanRadiusChunks(), 16);
        int centerChunkX = origin.getBlockX() >> 4;
        int centerChunkZ = origin.getBlockZ() >> 4;
        int requiredSources = plugin.getConfigManager().getLavaPoolRequiredSources();

        for (int chunkX = centerChunkX - radiusChunks; chunkX <= centerChunkX + radiusChunks; chunkX++) {
            for (int chunkZ = centerChunkZ - radiusChunks; chunkZ <= centerChunkZ + radiusChunks; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                Location candidate = findLavaClusterInChunk(world, chunkX, chunkZ, origin, radiusBlocks, requiredSources);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Location findLavaClusterInChunk(World world, int chunkX, int chunkZ, Location origin, int radiusBlocks, int requiredSources) {
        int minY = Math.max(world.getMinHeight(), origin.getBlockY() - 32);
        int maxY = Math.min(world.getMaxHeight() - 1, origin.getBlockY() + 32);
        int count = 0;
        Location first = null;

        for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
            for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                if (Math.abs(x - origin.getBlockX()) > radiusBlocks || Math.abs(z - origin.getBlockZ()) > radiusBlocks) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.LAVA) {
                        if (first == null) {
                            first = block.getLocation();
                        }
                        if (++count >= requiredSources) {
                            return first;
                        }
                    }
                }
            }
        }
        return null;
    }

    public void cancelPreScan() {
        preScanGeneration++;
        if (preScanTask != null) {
            preScanTask.cancel();
            preScanTask = null;
        }
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

        if (key.equals("VILLAGE")) {
            villageSearchFailed = false;
            plugin.getGameManager().villageTimeElapsed = 0;
        }
        if (key.equals("END_PORTAL")) {
            predictedEndPortalLocation = null;
            predictedEndPortalApproximate = false;
        }

        foundLocations.put(key, loc);
        disabledSearches.remove(key);
        registerHiddenLodestone(key, loc);
        String playerName = player != null ? player.getName() : "SERVER";
        plugin.getGameManager().getLogger().info("Structure '" + key + "' found/updated by " + playerName + " at " + LocationUtil.format(loc));

        Bukkit.getPluginManager().callEvent(new StructureFoundEvent(player, key, loc));
        boolean completedActiveStructureTask = plugin.getTaskManager().onStructureFound(key, player);
        if (!completedActiveStructureTask) {
            plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
        }

        String displayName = getLocalizedStructureName(key);
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                "%player%", playerName,
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
        // Ignore duplicate detections around the same physical portal frame.
        for (Location knownLoc : Arrays.asList(overworldPortalLocation, netherPortalLocation)) {
            if (knownLoc != null
                    && knownLoc.getWorld().equals(loc.getWorld())
                    && knownLoc.distance(loc) < 4.0) {
                return; // same portal
            }
        }


        if (isPortalFullyFound() && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            if (player != null) {
                MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.reassigning-disabled"));
            }
            plugin.getLogger().info("Blocked portal reassignment: portal reassignment is disabled in the config.");
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
        disabledSearches.remove("NETHER_PORTAL");
        registerHiddenLodestone("NETHER_PORTAL", loc);
        Bukkit.getPluginManager().callEvent(new StructureFoundEvent(player, "NETHER_PORTAL", loc));

        String playerName = (player != null) ? player.getName() : "GAME_WORLD";
        plugin.getGameManager().getLogger().info("Nether Portal lit by " + playerName + " in " + world.name() + " at " + LocationUtil.format(loc));

        boolean completedActiveStructureTask = plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD", player);
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", playerName));
        if (!completedActiveStructureTask) {
            plugin.getConfigManager().executeRewardCommands("on-task-complete", null);
        }

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
            registerHiddenLodestone("NETHER_PORTAL", exitLoc);
            Bukkit.getPluginManager().callEvent(new StructureFoundEvent(null, "NETHER_PORTAL", exitLoc));
            plugin.getGameManager().getLogger().info("Nether Portal exit (Nether-side) found at " + LocationUtil.format(exitLoc));
        } else if (exitWorld == World.Environment.NORMAL && this.overworldPortalLocation == null) {
            this.overworldPortalLocation = exitLoc;
            registerHiddenLodestone("NETHER_PORTAL", exitLoc);
            Bukkit.getPluginManager().callEvent(new StructureFoundEvent(null, "NETHER_PORTAL", exitLoc));
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
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.reassigning-disabled"));
            return false;
        }
        portalLit(player, player.getLocation());
        MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.portal-reassigned"));
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
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.unknown-structure-name", "%name%", naturalName));
            return false;
        }

        if (foundLocations.get(key) != null && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.reassigning-disabled"));
            return false;
        }

        if (key.equals("NETHER_PORTAL")) {
            MessageUtil.send(player, plugin.getConfigManager().getFormatted("messages.use-run-new-nether-portal"));
            return false;
        }
        // Use the standard structureFound method to ensure all logic (events, messages) is triggered.
        // Використовуємо стандартний метод structureFound, щоб гарантувати спрацювання всієї логіки (події, повідомлення).
        structureFound(player, key, newLocation);
        return true;
    }

    public void restoreStructure(String key) {
        if (!foundLocations.containsKey(key)) return;

        hiddenStructures.remove(key);
        disabledSearches.remove(key);
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    public boolean removeStructure(String key) {
        if (!foundLocations.containsKey(key)) return false;

        if ("NETHER_PORTAL".equals(key)) {
            overworldPortalLocation = null;
            netherPortalLocation = null;
        }

        foundLocations.put(key, null);
        disabledSearches.add(key);
        clearHiddenLodestone(key);
        hiddenStructures.add(key);
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
        return true;
    }


    // =========================================================================================
    // Getters & State Checks
    // =========================================================================================

    public boolean isStructureHidden(String key) {
        return hiddenStructures.contains(key);
    }

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
        if (foundLocations.get("VILLAGE") != null) return;
        if (disabledSearches.contains("VILLAGE")
                || villageSearchFailed
                || plugin.getGameManager().getVillageTimeRemaining() > 0) {
            return;
        }

        villageSearchFailed = true;
        foundLocations.put("VILLAGE", null);
        MessageUtil.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    /** @return True if the plugin is currently actively searching for a village. / True, якщо плагін наразі активно шукає село. */
    public boolean isVillageSearchActive() {
        return foundLocations.get("VILLAGE") == null
                && !disabledSearches.contains("VILLAGE")
                && plugin.getGameManager().getVillageTimeElapsed() < plugin.getConfigManager().getVillageTimeout()
                && !villageSearchFailed;
    }

    public boolean isVillageSearchFailed() {
        return villageSearchFailed;
    }

    /** @return True if the plugin is actively searching for a lava pool. / True, якщо плагін активно шукає озеро лави. */
    public boolean isLavaPoolSearchActive() {
        // Search for lava only if a portal hasn't been found yet.
        // Шукаємо лаву, тільки якщо портал ще не знайдено.
        return foundLocations.containsKey("LAVA_POOL")
                && foundLocations.get("LAVA_POOL") == null
                && !disabledSearches.contains("LAVA_POOL")
                && !isPortalPartiallyFound();
    }

    /** @return The predicted location of the End Portal from triangulation, or null. / Передбачена локація порталу в Край з тріангуляції, або null. */
    public Location getPredictedEndPortalLocation() {
        return predictedEndPortalLocation;
    }

    /** @return True while the End Portal location is only a triangulated estimate. / True, якщо локація порталу Краю є лише приблизною. */
    public boolean isPredictedEndPortalApproximate() {
        return predictedEndPortalApproximate;
    }

    /** Sets the predicted location of the End Portal. / Встановлює передбачену локацію порталу в Край. */
    public void setPredictedEndPortalLocation(Location location) {
        this.predictedEndPortalLocation = location;
        this.predictedEndPortalApproximate = location != null;
    }

    public Location getHiddenLodestone(String key) {
        return hiddenLodestones.get(key);
    }

    public Map<String, Location> getHiddenLodestones() {
        return Collections.unmodifiableMap(hiddenLodestones);
    }

    private void registerHiddenLodestone(String key, Location structureLocation) {
        if (structureLocation == null || structureLocation.getWorld() == null) {
            return;
        }

        clearHiddenLodestone(key);

        Location lodestoneLocation = structureLocation.clone().add(0, -5, 0);
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            return;
        }

        int minHeight = world.getMinHeight();
        if (lodestoneLocation.getBlockY() < minHeight) {
            lodestoneLocation.setY(minHeight);
        }

        if (!world.isChunkLoaded(lodestoneLocation.getBlockX() >> 4, lodestoneLocation.getBlockZ() >> 4)) {
            return;
        }
        Block block = lodestoneLocation.getBlock();
        hiddenLodestonePreviousBlocks.put(key, block.getType());
        block.setType(Material.LODESTONE, false);
        block.setMetadata(HIDDEN_LODESTONE_METADATA, new FixedMetadataValue(plugin, true));
        hiddenLodestones.put(key, lodestoneLocation);
    }

    private void clearHiddenLodestone(String key) {
        Location lodestoneLocation = hiddenLodestones.remove(key);
        Material previousType = hiddenLodestonePreviousBlocks.remove(key);
        if (lodestoneLocation == null || lodestoneLocation.getWorld() == null) {
            return;
        }

        if (!lodestoneLocation.getWorld().isChunkLoaded(lodestoneLocation.getBlockX() >> 4, lodestoneLocation.getBlockZ() >> 4)) {
            return;
        }
        Block block = lodestoneLocation.getBlock();
        if (block.hasMetadata(HIDDEN_LODESTONE_METADATA)) {
            block.removeMetadata(HIDDEN_LODESTONE_METADATA, plugin);
        }

        if (previousType != null) {
            block.setType(previousType, false);
        }
    }

    private void clearAllHiddenLodestones() {
        for (String key : new ArrayList<>(hiddenLodestones.keySet())) {
            clearHiddenLodestone(key);
        }
    }
}
