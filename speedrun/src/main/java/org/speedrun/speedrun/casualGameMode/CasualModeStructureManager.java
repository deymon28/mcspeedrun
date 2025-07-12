package org.speedrun.speedrun.casualGameMode;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.speedrun.speedrun.Speedrun;

/**
 * Manages features specific to the "casual" game mode, such as creating visual waypoints.
 * This class is responsible for generating temporary beacon structures to mark found locations.
 * |
 * Керує функціями, специфічними для "казуального" режиму гри, як-от створення візуальних вейпоінтів.
 * Цей клас відповідає за генерацію тимчасових структур-маяків для позначення знайдених локацій.
 */
public class CasualModeStructureManager {
    private final Speedrun plugin; // The plugin instance is needed for BukkitRunnable and Metadata

    public CasualModeStructureManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    /**
     * Determines the color of the beacon's glass based on the structure type.
     * |
     * Визначає колір скла для маяка на основі типу структури.
     *
     * @param structureKey The unique key of the structure. / Унікальний ключ структури.
     * @return The Material of the stained glass to use. / Матеріал вітражного скла для використання.
     */
    private Material getBeaconColor(String structureKey) {
        return switch (structureKey) {
            case "VILLAGE" -> Material.GREEN_STAINED_GLASS;  // Village = Green
            case "LAVA_POOL" -> Material.RED_STAINED_GLASS;    // Lava = Red
            default -> Material.ORANGE_STAINED_GLASS; // Default = Orange
        };
    }

    /**
     * Creates a temporary beacon structure at a specified location to act as a waypoint.
     * The structure consists of a 3x3 iron base, a beacon, and colored glass.
     * All blocks are marked with metadata to be indestructible.
     * |
     * Створює тимчасову структуру-маяк у вказаному місці, яка слугує вейпоінтом.
     * Структура складається з залізної основи 3x3, маяка та кольорового скла.
     * Усі блоки позначаються метаданими, щоб бути незнищенними.
     *
     * @param location The central location of the found structure. / Центральна локація знайденої структури.
     * @param structureKey The key of the structure, used to determine the beacon color. / Ключ структури, використовується для визначення кольору маяка.
     */
    public void createBeaconStructure(Location location, String structureKey) {
        // Place the beacon slightly away from the actual structure to avoid interference.
        // Розміщуємо маяк трохи осторонь від самої структури, щоб уникнути перешкод.
        double distanceFromStructure = 10;

        Location beaconLoc = location.clone().add(0.5, 0, 0.5 + distanceFromStructure);
        // Place it on the highest solid block to ensure it's visible.
        // Ставимо його на найвищий твердий блок, щоб він був видимим.
        beaconLoc.setY(beaconLoc.getWorld().getHighestBlockYAt(beaconLoc) + 1);

        // Run the block placement in a BukkitRunnable to ensure it happens on the main server thread.
        // Виконуємо розміщення блоків у BukkitRunnable, щоб гарантувати, що це відбудеться в основному потоці сервера.
        new BukkitRunnable() {
            @Override
            public void run() {
                // Build the 3x3 iron base (minimal pyramid for beacon activation).
                // Будуємо залізну основу 3x3 (мінімальна піраміда для активації маяка).
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        Location blockLoc = beaconLoc.clone().add(x, -1, z);
                        blockLoc.getBlock().setType(Material.IRON_BLOCK);
                        // Add metadata to prevent players from breaking the waypoint.
                        // Додаємо метадані, щоб гравці не могли зламати вейпоінт.
                        blockLoc.getBlock().setMetadata("indestructible", new FixedMetadataValue(plugin, true));
                    }
                }

                // Place the beacon itself and the colored glass on top.
                // Розміщуємо сам маяк і кольорове скло зверху.
                Block beaconBlock = beaconLoc.getBlock();
                beaconBlock.setType(Material.BEACON);
                beaconBlock.setMetadata("indestructible", new FixedMetadataValue(plugin, true));

                // Set colored glass (hardcoded colors)
                Material glassColor = getBeaconColor(structureKey); // Use the helper method
                Block glassBlock = beaconLoc.clone().add(0, 1, 0).getBlock();
                glassBlock.setType(glassColor);
                glassBlock.setMetadata("indestructible", new FixedMetadataValue(plugin, true));

                // Update the beacon's state to ensure it activates immediately.
                // Оновлюємо стан маяка, щоб він активувався негайно.
                if (beaconBlock.getState() instanceof Beacon beacon) {
                    beacon.setPrimaryEffect(null);
                    beacon.update(true);
                }

                // Play a sound to notify players that the waypoint has been created.
                // Програємо звук, щоб повідомити гравців про створення вейпоінту.
                beaconLoc.getWorld().playSound(beaconLoc, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
            }
        }.runTask(plugin);
    }
}
