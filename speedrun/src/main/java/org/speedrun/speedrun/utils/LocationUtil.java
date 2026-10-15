package org.speedrun.speedrun.utils;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * A utility class for location-related calculations and formatting.
 * Contains methods for formatting coordinates and performing geometric calculations like triangulation.
 */
public final class LocationUtil {
    /**
     * Formats a Location object into a user-friendly "X, Y, Z" string.
     * Returns "???" if the location is null.
     *
     * @param loc The location to format.
     * @return A formatted string of the location's block coordinates.
     */
    public static String format(Location loc) {
        if (loc == null) return "???";
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Calculates the intersection point of two lines in a 2D plane, defined by two points and their yaw angles.
     * This is used for triangulating the position of a stronghold.
     *
     * @param x1 The X-coordinate of the first point.
     * @param z1 The Z-coordinate of the first point.
     * @param yaw1 The yaw angle (direction) at the first point.
     * @param x2 The X-coordinate of the second point.
     * @param z2 The Z-coordinate of the second point.
     * @param yaw2 The yaw angle (direction) at the second point.
     * @return A new Location of the intersection point, or null if the lines are parallel or
     *         cross behind one of the throw positions (an Eye of Ender always flies towards the
     *         stronghold, so such an intersection is a measuring error).
     */
    @Nullable
    public static Location triangulate(double x1, double z1, float yaw1, double x2, double z2, float yaw2) {
        // Convert yaw angles into normalized 2D direction vectors.
        // Minecraft yaw is clockwise from south, so we adjust to standard math angles.
        Vector dir1 = new Vector(Math.sin(-Math.toRadians(yaw1)), 0, Math.cos(-Math.toRadians(yaw1))).normalize();
        Vector dir2 = new Vector(Math.sin(-Math.toRadians(yaw2)), 0, Math.cos(-Math.toRadians(yaw2))).normalize();

        // Check for parallel lines using the 2D cross product (determinant).
        // If the determinant is close to zero, the lines are parallel.
        double det = dir1.getX() * dir2.getZ() - dir1.getZ() * dir2.getX();
        if (Math.abs(det) < 1e-10) { // Using a small epsilon for floating-point comparison.
            return null; // Lines are parallel, no unique intersection.
        }

        // Solve the system of linear equations to find the intersection parameter 't'.
        double dx = x2 - x1;
        double dz = z2 - z1;

        double t = (dx * dir2.getZ() - dz * dir2.getX()) / det;
        double s = (dx * dir1.getZ() - dz * dir1.getX()) / det;
        if (t <= 0 || s <= 0) {
            return null;
        }

        // Calculate the intersection point coordinates using the parameter 't'.
        double intersectX = x1 + t * dir1.getX();
        double intersectZ = z1 + t * dir1.getZ();

        // Return a new Location. Y-coordinate is a placeholder as it's a 2D calculation.
        return new Location(null, intersectX, 64, intersectZ);
    }
}
