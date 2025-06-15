package org.speedrun.speedrun;

import org.bukkit.Location;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

// =========================================================================================
// Utility Classes
// =========================================================================================
final class LocationUtil {
    public static String format(Location loc) {
        if (loc == null) return "???";
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Calculates the intersection of two lines defined by points and yaw angles.
     * @return A new Location of the intersection point, or null if they are parallel.
     */
    @Nullable
    public static Location triangulate(double x1, double z1, float yaw1, double x2, double z2, float yaw2) {
        // Convert yaw to normalized 2D direction vectors
        Vector dir1 = new Vector(Math.sin(-Math.toRadians(yaw1)), 0, Math.cos(-Math.toRadians(yaw1))).normalize();
        Vector dir2 = new Vector(Math.sin(-Math.toRadians(yaw2)), 0, Math.cos(-Math.toRadians(yaw2))).normalize();

        // Check for parallel lines (cross product in 2D)
        double det = dir1.getX() * dir2.getZ() - dir1.getZ() * dir2.getX();
        if (Math.abs(det) < 1e-10) { // Using a small epsilon for floating point comparison
            return null; // Lines are parallel, no unique intersection
        }

        double dx = x2 - x1;
        double dz = z2 - z1;

        double t = (dx * dir2.getZ() - dz * dir2.getX()) / det;

        // Intersection point coordinates
        double intersectX = x1 + t * dir1.getX();
        double intersectZ = z1 + t * dir1.getZ();

        // Return a location with a placeholder Y-value
        return new Location(null, intersectX, 64, intersectZ);
    }
}
