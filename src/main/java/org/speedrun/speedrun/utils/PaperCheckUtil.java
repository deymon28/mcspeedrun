package org.speedrun.speedrun.utils;

/**
 * A utility class to check if the server is running Paper.
 * This allows the plugin to use Paper-specific APIs for performance improvements when available,
 * while maintaining compatibility with Spigot/Bukkit.
 * |
 * Утилітарний клас для перевірки, чи сервер працює на ядрі Paper.
 * Це дозволяє плагіну використовувати специфічні для Paper API методи для покращення продуктивності,
 * коли це можливо, зберігаючи при цьому сумісність зі Spigot/Bukkit.
 */
public class PaperCheckUtil {
    // Static initializer block to check for the Paper class only once when PaperCheckUtil is loaded.
    // Статичний блок ініціалізації для перевірки наявності класу Paper лише один раз при завантаженні PaperCheckUtil.
    private static boolean isServerPaper() {
        try {
            // Attempt to find a class that only exists on Paper servers.
            // If this succeeds, we are on Paper.
            // Спроба знайти клас, який існує лише на серверах Paper.
            // Якщо це вдається, ми на Paper.
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (ClassNotFoundException e) {
            // If the class is not found, we are on a non-Paper server (e.g., Spigot, Bukkit).
            // Якщо клас не знайдено, ми на сервері, що не є Paper (напр., Spigot, Bukkit).
            return false;
        }
    }

    /**
     * Checks if the server is running on the Paper software.
     * The result is cached for efficiency.
     * |
     * Перевіряє, чи сервер працює на програмному забезпеченні Paper.
     * Результат кешується для ефективності.
     *
     * @return true if the server is Paper, false otherwise. / true, якщо сервер є Paper, інакше false.
     */
    public static boolean IsPaper() {return isServerPaper();}
}
