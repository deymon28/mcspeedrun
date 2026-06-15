package org.speedrun.speedrun.utils;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Pure helpers for linked Overworld/Nether coordinate conversion.
 */
public final class WorldCoordinateUtil {
    private WorldCoordinateUtil() {
    }

    public static boolean canConvert(World.Environment from, World.Environment to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return (from == World.Environment.NORMAL && to == World.Environment.NETHER)
                || (from == World.Environment.NETHER && to == World.Environment.NORMAL);
    }

    public static double scaleCoordinate(double coordinate, World.Environment from, World.Environment to) {
        if (from == World.Environment.NORMAL && to == World.Environment.NETHER) {
            return coordinate / 8.0;
        }
        if (from == World.Environment.NETHER && to == World.Environment.NORMAL) {
            return coordinate * 8.0;
        }
        return coordinate;
    }

    public static Location convert(Location location, World targetWorld) {
        if (location == null || location.getWorld() == null || targetWorld == null) {
            return location;
        }

        World.Environment from = location.getWorld().getEnvironment();
        World.Environment to = targetWorld.getEnvironment();
        if (!canConvert(from, to)) {
            return location;
        }

        return new Location(targetWorld,
                scaleCoordinate(location.getX(), from, to),
                location.getY(),
                scaleCoordinate(location.getZ(), from, to),
                location.getYaw(),
                location.getPitch());
    }
}
