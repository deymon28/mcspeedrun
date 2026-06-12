package org.speedrun.speedrun.utils;

import org.bukkit.Location;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * A utility class for location-related calculations and formatting.
 * Contains methods for formatting coordinates and performing geometric calculations like triangulation.
 * |
 * Утилітарний клас для обчислень та форматування, пов'язаних із локаціями.
 * Містить методи для форматування координат та виконання геометричних обчислень, як-от тріангуляція.
 */
public final class LocationUtil {
    /**
     * Formats a Location object into a user-friendly "X, Y, Z" string.
     * Returns "???" if the location is null.
     * |
     * Форматує об'єкт Location у зручний для читання рядок "X, Y, Z".
     * Повертає "???", якщо локація є null.
     *
     * @param loc The location to format. / Локація для форматування.
     * @return A formatted string of the location's block coordinates. / Відформатований рядок координат блоку.
     */
    public static String format(Location loc) {
        if (loc == null) return "???";
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Calculates the intersection point of two lines in a 2D plane, defined by two points and their yaw angles.
     * This is used for triangulating the position of a stronghold.
     * |
     * Обчислює точку перетину двох ліній на 2D-площині, визначених двома точками та їхніми кутами повороту (yaw).
     * Використовується для тріангуляції місцезнаходження фортеці.
     *
     * @param x1 The X-coordinate of the first point. / X-координата першої точки.
     * @param z1 The Z-coordinate of the first point. / Z-координата першої точки.
     * @param yaw1 The yaw angle (direction) at the first point. / Кут повороту (напрямок) у першій точці.
     * @param x2 The X-coordinate of the second point. / X-координата другої точки.
     * @param z2 The Z-coordinate of the second point. / Z-координата другої точки.
     * @param yaw2 The yaw angle (direction) at the second point. / Кут повороту (напрямок) у другій точці.
     * @return A new Location of the intersection point, or null if the lines are parallel. / Нова Location точки перетину, або null, якщо лінії паралельні.
     */
    @Nullable
    public static Location triangulate(double x1, double z1, float yaw1, double x2, double z2, float yaw2) {
        // Convert yaw angles into normalized 2D direction vectors.
        // Minecraft yaw is clockwise from south, so we adjust to standard math angles.
        // Перетворюємо кути повороту в нормалізовані 2D-вектори напрямку.
        // Yaw в Minecraft йде за годинниковою стрілкою від півдня, тому ми коригуємо до стандартних математичних кутів.
        Vector dir1 = new Vector(Math.sin(-Math.toRadians(yaw1)), 0, Math.cos(-Math.toRadians(yaw1))).normalize();
        Vector dir2 = new Vector(Math.sin(-Math.toRadians(yaw2)), 0, Math.cos(-Math.toRadians(yaw2))).normalize();

        // Check for parallel lines using the 2D cross product (determinant).
        // If the determinant is close to zero, the lines are parallel.
        // Перевіряємо на паралельність ліній за допомогою 2D векторного добутку (визначника).
        // Якщо визначник близький до нуля, лінії паралельні.
        double det = dir1.getX() * dir2.getZ() - dir1.getZ() * dir2.getX();
        if (Math.abs(det) < 1e-10) { // Using a small epsilon for floating-point comparison. / Використання малого епсилону для порівняння чисел з плаваючою комою.
            return null; // Lines are parallel, no unique intersection. / Лінії паралельні, унікального перетину немає.
        }

        // Solve the system of linear equations to find the intersection parameter 't'.
        // Розв'язуємо систему лінійних рівнянь, щоб знайти параметр перетину 't'.
        double dx = x2 - x1;
        double dz = z2 - z1;

        double t = (dx * dir2.getZ() - dz * dir2.getX()) / det;

        // Calculate the intersection point coordinates using the parameter 't'.
        // Обчислюємо координати точки перетину, використовуючи параметр 't'.
        double intersectX = x1 + t * dir1.getX();
        double intersectZ = z1 + t * dir1.getZ();

        // Return a new Location. Y-coordinate is a placeholder as it's a 2D calculation.
        // Повертаємо нову Location. Y-координата є плейсхолдером, оскільки це 2D-обчислення.
        return new Location(null, intersectX, 64, intersectZ);
    }
}
