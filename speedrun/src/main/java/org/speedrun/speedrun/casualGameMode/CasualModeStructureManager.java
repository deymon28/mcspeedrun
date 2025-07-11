package org.speedrun.speedrun.casualGameMode;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.speedrun.speedrun.Speedrun;

public class CasualModeStructureManager {
    private final Speedrun plugin; // The plugin instance is needed for BukkitRunnable and Metadata

    public CasualModeStructureManager(Speedrun plugin) {
        this.plugin = plugin;
    }

    private Material getBeaconColor(String structureKey) {
        return switch (structureKey) {
            case "VILLAGE" -> Material.GREEN_STAINED_GLASS;  // Village = Green
            case "LAVA_POOL" -> Material.RED_STAINED_GLASS;    // Lava = Red
            default -> Material.ORANGE_STAINED_GLASS; // Default = Orange
        };
    }

    public void createBeaconStructure(Location location, String structureKey) {
        double distanceFromStructure = 10;

        Location beaconLoc = location.clone().add(0.5, 0, 0.5 + distanceFromStructure);
        beaconLoc.setY(beaconLoc.getWorld().getHighestBlockYAt(beaconLoc) + 1);

        new BukkitRunnable() {
            @Override
            public void run() {
                // 3x3 iron base (minimal pyramid)
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        Location blockLoc = beaconLoc.clone().add(x, -1, z);
                        blockLoc.getBlock().setType(Material.IRON_BLOCK);
                        // Make sure your plugin instance is accessible here for FixedMetadataValue
                        blockLoc.getBlock().setMetadata("indestructible", new FixedMetadataValue(plugin, true));
                    }
                }

                // Place beacon
                Block beaconBlock = beaconLoc.getBlock();
                beaconBlock.setType(Material.BEACON);
                beaconBlock.setMetadata("indestructible", new FixedMetadataValue(plugin, true));

                // Set colored glass (hardcoded colors)
                Material glassColor = getBeaconColor(structureKey); // Use the helper method
                Block glassBlock = beaconLoc.clone().add(0, 1, 0).getBlock();
                glassBlock.setType(glassColor);
                glassBlock.setMetadata("indestructible", new FixedMetadataValue(plugin, true));

                // Configure beacon (no effects)
                if (beaconBlock.getState() instanceof Beacon beacon) {
                    beacon.setPrimaryEffect(null);
                    beacon.update(true);
                }

                // Play activation sound
                beaconLoc.getWorld().playSound(beaconLoc, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
            }
        }.runTask(plugin);
    }
}
