package org.speedrun.speedrun;

import org.bukkit.Location;

// =========================================================================================
// Utility Classes
// =========================================================================================
final class LocationUtil {
    public static String format(Location loc) {
        if (loc == null) return "???";
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
