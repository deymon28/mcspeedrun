package org.speedrun.speedrun.platform;

import java.util.logging.Logger;

/**
 * Chooses the most capable {@link PlatformAdapter} for the running server.
 *
 * <p>Paper (and Paper forks) get the native adapter from the Paper source set, Spigot-API
 * servers get {@link SpigotPlatformAdapter}, and everything else falls back to the plain
 * Bukkit implementation. Detection is based on classes, not version strings, so forks are
 * handled by the capabilities they actually provide.</p>
 */
public final class PlatformDetector {
    static final String PAPER_ADAPTER_CLASS = "org.speedrun.speedrun.platform.paper.PaperPlatformAdapter";

    private PlatformDetector() {
    }

    public static PlatformAdapter detect(Logger logger) {
        if (isPaper()) {
            try {
                Class<?> adapterClass = Class.forName(PAPER_ADAPTER_CLASS, true, PlatformDetector.class.getClassLoader());
                return (PlatformAdapter) adapterClass.getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                logger.warning("Paper was detected, but the Paper adapter could not be loaded ("
                        + failure.getClass().getSimpleName() + "). Falling back to Spigot compatibility mode.");
            }
        }
        if (isSpigot()) {
            return new SpigotPlatformAdapter(isPaper() ? "Paper (compatibility mode)" : "Spigot");
        }
        return new CraftBukkitPlatformAdapter(logger);
    }

    public static boolean isPaper() {
        return classPresent("io.papermc.paper.ServerBuildInfo")
                || classPresent("com.destroystokyo.paper.PaperConfig");
    }

    public static boolean isSpigot() {
        return classPresent("org.bukkit.entity.Player$Spigot");
    }

    static boolean classPresent(String className) {
        try {
            Class.forName(className, false, PlatformDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
