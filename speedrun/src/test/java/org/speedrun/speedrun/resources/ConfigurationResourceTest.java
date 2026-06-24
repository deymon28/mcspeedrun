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
        assertEquals(12, config.getInt("casual.start-pre-scan.lava-pool-vertical-scan.below-player"));
        assertEquals(32, config.getInt("casual.start-pre-scan.lava-pool-vertical-scan.above-player"));
        assertEquals(8, config.getInt("casual.start-pre-scan.safety.max-chunks-per-run"));
        assertEquals(5000, config.getInt("casual.start-pre-scan.safety.max-queued-chunks"));
        assertEquals(96, config.getInt("casual.start-pre-scan.locate.radius-chunks"));
        assertEquals(1, config.getInt("casual.start-pre-scan.locate.calls-per-run"));
        assertTrue(config.getBoolean("casual.start-pre-scan.locate.targets.stronghold"));

        assertEquals(0, config.getInt("casual.start-pre-scan.profiles.SAFE.chunks-per-run"));
        assertFalse(config.getBoolean("casual.start-pre-scan.profiles.SAFE.load-missing-chunks"));

        assertEquals(2, config.getInt("casual.start-pre-scan.profiles.BALANCED.chunks-per-run"));
        assertEquals(1500, config.getInt("casual.start-pre-scan.profiles.BALANCED.max-queued-chunks"));
        assertTrue(config.getBoolean("casual.start-pre-scan.profiles.BALANCED.load-missing-chunks"));

        assertEquals(8, config.getInt("casual.start-pre-scan.profiles.AGGRESSIVE.chunks-per-run"));
        assertEquals(5000, config.getInt("casual.start-pre-scan.profiles.AGGRESSIVE.max-queued-chunks"));
        assertTrue(config.getBoolean("casual.start-pre-scan.profiles.AGGRESSIVE.include-nether"));

        assertEquals(0, config.getInt("casual.start-pre-scan.profiles.LOCATE.chunks-per-run"));
        assertEquals(0, config.getInt("casual.start-pre-scan.profiles.LOCATE.max-queued-chunks"));
        assertFalse(config.getBoolean("casual.start-pre-scan.profiles.LOCATE.load-missing-chunks"));
    }

    @Test
    void generatedConfigKeepsStructureStagesEmptyByDefault() {
        YamlConfiguration config = loadYaml("src/main/resources/config.yml");

        assertTrue(config.getConfigurationSection("progression.4_NETHER_STRUCTURES.tasks").getKeys(false).isEmpty());
        assertTrue(config.getConfigurationSection("progression.5_STRONGHOLD.tasks").getKeys(false).isEmpty());
    }

    @Test
    void diagnosticsTraceIsAvailableButDisabledByDefault() {
        YamlConfiguration config = loadYaml("src/main/resources/config.yml");

        assertFalse(config.getBoolean("diagnostics.trace.enabled"));
        assertFalse(config.getBoolean("diagnostics.trace.mirror-to-console"));
        assertTrue(config.getStringList("diagnostics.trace.categories").contains("ALL"));
    }

    @Test
    void configUsesRequestedRuntimeDefaults() {
        YamlConfiguration config = loadYaml("src/main/resources/config.yml");

        assertTrue(config.getBoolean("web-config.enabled"));
        assertEquals("127.0.0.1", config.getString("web-config.bind"));
        assertEquals(8765, config.getInt("web-config.port"));
        assertTrue(config.getBoolean("settings.reset_time_on_join"));
        assertEquals("END_GATEWAY", config.getString("casual.structure_waypoints.type"));
        assertEquals("ALL_GAME_STAGES", config.getString("progression.settings.task-display-mode"));
        assertTrue(config.getBoolean("progression.settings.completed-task-hide.enabled"));
        assertEquals(10, config.getInt("progression.settings.completed-task-hide.timeout-seconds"));
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
