package org.speedrun.speedrun.resources;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationResourceTest {
    @Test
    void configDefinesExpectedCasualPreScanProfiles() {
        YamlConfiguration config = loadYaml("src/main/resources/config.yml");

        assertEquals("BALANCED", config.getString("casual.start-pre-scan.mode"));
        assertEquals(1000, config.getInt("casual.start-pre-scan.radius"));
        assertEquals(10, config.getInt("casual.start-pre-scan.loaded-chunk-radius"));
        assertTrue(config.getBoolean("casual.start-pre-scan.include-lava-pool"));

        assertEquals(0, config.getInt("casual.start-pre-scan.profiles.SAFE.chunks-per-run"));
        assertFalse(config.getBoolean("casual.start-pre-scan.profiles.SAFE.load-missing-chunks"));

        assertEquals(2, config.getInt("casual.start-pre-scan.profiles.BALANCED.chunks-per-run"));
        assertEquals(1500, config.getInt("casual.start-pre-scan.profiles.BALANCED.max-queued-chunks"));
        assertTrue(config.getBoolean("casual.start-pre-scan.profiles.BALANCED.load-missing-chunks"));

        assertEquals(8, config.getInt("casual.start-pre-scan.profiles.AGGRESSIVE.chunks-per-run"));
        assertEquals(5000, config.getInt("casual.start-pre-scan.profiles.AGGRESSIVE.max-queued-chunks"));
        assertTrue(config.getBoolean("casual.start-pre-scan.profiles.AGGRESSIVE.include-nether"));
    }

    @Test
    void generatedConfigKeepsStructureStagesEmptyByDefault() {
        YamlConfiguration config = loadYaml("src/main/resources/config.yml");

        assertTrue(config.getConfigurationSection("progression.4_NETHER_STRUCTURES.tasks").getKeys(false).isEmpty());
        assertTrue(config.getConfigurationSection("progression.5_STRONGHOLD.tasks").getKeys(false).isEmpty());
    }

    @Test
    void langFilesContainCompassDeathAndApproximateKeys() {
        for (String langPath : List.of("src/main/resources/lang/en.yml", "src/main/resources/lang/uk.yml")) {
            YamlConfiguration lang = loadYaml(langPath);

            assertTrue(lang.contains("compass.gui.death-location-name"), langPath);
            assertTrue(lang.contains("compass.gui.death-click-hint"), langPath);
            assertTrue(lang.contains("compass.gui.approximate-destination-name"), langPath);
            assertTrue(lang.contains("compass.gui.approximate-lore"), langPath);
            assertTrue(lang.contains("structures.PLAYER_DEATH"), langPath);
        }
    }

    private YamlConfiguration loadYaml(String relativePath) {
        File file = Path.of(relativePath).toFile();
        assertTrue(file.isFile(), "Missing resource file: " + relativePath);
        return YamlConfiguration.loadConfiguration(file);
    }
}
