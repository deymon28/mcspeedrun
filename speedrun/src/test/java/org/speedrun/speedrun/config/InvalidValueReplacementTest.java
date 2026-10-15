package org.speedrun.speedrun.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidValueReplacementTest {
    private static YamlConfiguration defaults() throws Exception {
        try (InputStream input = InvalidValueReplacementTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(input);
            return ConfigFileStore.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void outOfRangeAndUnknownValuesUseTheDefaultAtRuntime() throws Exception {
        YamlConfiguration defaults = defaults();
        YamlConfiguration config = ConfigFileStore.copy(defaults);
        config.set("settings.proximity-scanner.lava-pool.radius", 5000);
        config.set("settings.task-tracking-mode", "CUMULATIV");
        config.set("settings.village-search-timeout", 900);

        List<String> replaced = ConfigMigrationService.replaceInvalidValues(config, defaults);

        assertTrue(replaced.contains("settings.proximity-scanner.lava-pool.radius"), replaced.toString());
        assertTrue(replaced.contains("settings.task-tracking-mode"), replaced.toString());
        assertEquals(defaults.getInt("settings.proximity-scanner.lava-pool.radius"),
                config.getInt("settings.proximity-scanner.lava-pool.radius"));
        assertEquals("INVENTORY", config.getString("settings.task-tracking-mode"));
        // Valid values are kept.
        assertEquals(900, config.getInt("settings.village-search-timeout"));
    }
}
