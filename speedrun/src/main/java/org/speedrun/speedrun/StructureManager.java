package org.speedrun.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

public class StructureManager {
    private final Speedrun plugin;
    private final Map<String, Location> foundLocations = new LinkedHashMap<>();

    // --- НОВАЯ ЛОГИКА ДЛЯ ПОРТАЛОВ ---
    // Раздельное хранение координат для каждого мира
    private Location overworldPortalLocation;
    private Location netherPortalLocation;
    // --- КОНЕЦ НОВОЙ ЛОГИКИ ---

    private Location predictedEndPortalLocation;

    public StructureManager(Speedrun plugin) {
        this.plugin = plugin;
        reset();
    }

    public void reset() {
        foundLocations.clear();
        predictedEndPortalLocation = null;
        // Сбрасываем порталы
        overworldPortalLocation = null;
        netherPortalLocation = null;

        // Инициализируем остальные структуры
        foundLocations.put("LAVA_POOL", null);
        foundLocations.put("VILLAGE", null);
        // Ключ "NETHER_PORTAL" больше не используется для хранения координат напрямую,
        // но оставляем его для совместимости и отображения в общем списке
        foundLocations.put("NETHER_PORTAL", null);
        foundLocations.put("FORTRESS", null);
        foundLocations.put("BASTION", null);
        foundLocations.put("END_PORTAL", null);
    }

    public void structureFound(Player player, String key, Location loc) {
        // Игнорируем прямое обновление портала через этот метод
        if (key.equals("NETHER_PORTAL")) {
            return;
        }

        foundLocations.put(key, loc);
        plugin.getGameManager().getLogger().info("Structure '" + key + "' found/updated by " + player.getName() + " at " + LocationUtil.format(loc));
        plugin.getTaskManager().onStructureFound(key, player);
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);

        String langKey = "structures." + key;
        String displayName = plugin.getConfigManager().getLangString(langKey, key);
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                "%player%", player.getName(),
                "%structure%", displayName,
                "%coords%", LocationUtil.format(loc)));
    }

    // --- НОВЫЕ И ПЕРЕРАБОТАННЫЕ МЕТОДЫ ДЛЯ ПОРТАЛОВ ---

    /**
     * Регистрирует зажжённый портал.
     * Вызывается, когда игрок зажигает портал в любом из миров.
     */
    public void portalLit(Player player, Location loc) {
        // 1. Проверяем, можно ли переназначать портал, если он уже существует
        if (isPortalFullyFound() && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage("§cПереназначение портала отключено в конфиге.");
            return;
        }

        World.Environment world = player.getWorld().getEnvironment();

        // 2. Сбрасываем ОБА местоположения, так как создается новый "основной" портал
        this.overworldPortalLocation = null;
        this.netherPortalLocation = null;

        // 3. Устанавливаем координаты для текущего мира
        if (world == World.Environment.NORMAL) {
            this.overworldPortalLocation = loc;
        } else if (world == World.Environment.NETHER) {
            this.netherPortalLocation = loc;
        } else {
            // Не обрабатываем порталы в других мирах (например, The End)
            return;
        }

        // Обновляем "виртуальную" запись в foundLocations, чтобы скорборд знал, что портал есть
        foundLocations.put("NETHER_PORTAL", loc);

        plugin.getGameManager().getLogger().info("Nether Portal lit by " + player.getName() + " in " + world.name() + " at " + LocationUtil.format(loc));
        // Сообщаем о событии для выполнения задач
        plugin.getTaskManager().onStructureFound("NETHER_PORTAL_OVERWORLD", player); // Используем старый ключ для совместимости с задачами
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.portal-lit", "%player%", player.getName()));
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);

        // Обновляем скорборд для всех
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    public boolean updateStructureLocation(String naturalName, Location newLocation, Player player) {
        String key = naturalName.replace(' ', '_').toUpperCase();

        // ИСПРАВЛЕНИЕ: Использование локализованного сообщения для неизвестного имени структуры
        if (!foundLocations.containsKey(key) && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage(plugin.getConfigManager().getFormattedText("messages.unknown-structure-name", "%name%", naturalName));
            return false;
        }

        boolean isAlreadyFound = foundLocations.get(key) != null;

        if (isAlreadyFound && !plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage("§cПереназначение локаций отключено в конфиге.");
            return false;
        }

        if (key.equals("NETHER_PORTAL")) {
            player.sendMessage("§cИспользуйте /run new nether portal для переназначения портала.");
            return false;
        }

        foundLocations.put(key, newLocation);
        plugin.getGameManager().getLogger().info("Structure '" + key + "' updated by " + player.getName() + " at " + LocationUtil.format(newLocation));
        plugin.getTaskManager().onStructureFound(key, player);
        plugin.getConfigManager().executeRewardCommands("on-task-complete", player);

        String langKey = "structures." + key;
        String displayName = plugin.getConfigManager().getLangString(langKey, key);
        Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.structure-found",
                "%player%", player.getName(),
                "%structure%", displayName,
                "%coords%", LocationUtil.format(newLocation)));
        return true;
    }

    /**
     * Регистрирует выход из портала после телепортации.
     * Принимает уже потенциально уточненную локацию портала.
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
        // Обновляем скорборд для всех
        Bukkit.getOnlinePlayers().forEach(p -> plugin.getScoreboardManager().updateScoreboard(p));
    }

    /**
     * Обрабатывает команду /run new Nether Portal.
     */
    public boolean reassignNetherPortal(Player player) {
        // Команда работает только если переназначение включено в конфиге
        if (!plugin.getConfigManager().isReassigningLocationsEnabled()) {
            player.sendMessage("§cПереназначение локаций отключено в конфиге.");
            return false;
        }

        // Используем существующий метод portalLit, который теперь содержит всю нужную логику
        // (сброс старых координат и установка новых)
        portalLit(player, player.getLocation());
        player.sendMessage("§aКоординаты портала были сброшены и установлены на ваше текущее местоположение.");
        return true;
    }

    // --- GETTERS & HELPERS ---

    public Location getOverworldPortalLocation() {
        return overworldPortalLocation;
    }

    public Location getNetherPortalLocation() {
        return netherPortalLocation;
    }

    /**
     * Возвращает координаты портала для мира, в котором находится игрок.
     * Используется для отображения в скорборде.
     */
    public Location getPortalLocationForWorld(World.Environment world) {
        if (world == World.Environment.NORMAL) {
            return overworldPortalLocation;
        }
        if (world == World.Environment.NETHER) {
            return netherPortalLocation;
        }
        return null;
    }

    /**
     * Проверяет, найден ли портал хотя бы с одной стороны.
     */
    public boolean isPortalPartiallyFound() {
        return overworldPortalLocation != null || netherPortalLocation != null;
    }

    /**
     * Проверяет, найден ли портал с обеих сторон.
     */
    public boolean isPortalFullyFound() {
        return overworldPortalLocation != null && netherPortalLocation != null;
    }

    // --- Остальные методы без изменений ---

    public String getLocalizedStructureName(String key) {
        return plugin.getConfigManager().getLangString("structures." + key, key);
    }

    public void checkVillageTimeout() {
        if (isVillageSearchActive() && plugin.getGameManager().getVillageTimeRemaining() <= 0) {
            foundLocations.remove("VILLAGE");
            Bukkit.broadcast(plugin.getConfigManager().getFormatted("messages.village-timeout"));
        }
    }

    public boolean isVillageSearchActive() {
        return foundLocations.containsKey("VILLAGE") && foundLocations.get("VILLAGE") == null;
    }

    public boolean isLavaPoolSearchActive() {
        return foundLocations.containsKey("LAVA_POOL") && foundLocations.get("LAVA_POOL") == null && !isPortalPartiallyFound();
    }

    public Map<String, Location> getFoundStructures() {
        return foundLocations;
    }

    public Location getPredictedEndPortalLocation() {
        return predictedEndPortalLocation;
    }

    public void setPredictedEndPortalLocation(Location location) {
        this.predictedEndPortalLocation = location;
    }
}