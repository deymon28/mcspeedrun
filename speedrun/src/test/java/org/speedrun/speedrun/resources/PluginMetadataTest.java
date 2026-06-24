package org.speedrun.speedrun.resources;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMetadataTest {
    @Test
    void pluginYmlDeclaresMainCommandAndPermissions() {
        YamlConfiguration pluginYml = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/plugin.yml").toFile());

        assertEquals("Speedrun", pluginYml.getString("name"));
        assertEquals("org.speedrun.speedrun.Speedrun", pluginYml.getString("main"));
        assertTrue(pluginYml.contains("commands.run"));
        assertTrue(pluginYml.getString("commands.run.usage").contains("webconfig"));
        assertTrue(pluginYml.getStringList("commands.run.aliases").contains("sr"));
        assertEquals("speedrun.player", pluginYml.getString("commands.run.permission"));
        assertEquals("true", String.valueOf(pluginYml.get("permissions.speedrun.player.default")));
        assertEquals("op", pluginYml.getString("permissions.speedrun.admin.default"));
    }

    @Test
    void pluginYmlResourceExists() {
        File file = Path.of("src/main/resources/plugin.yml").toFile();
        assertTrue(file.isFile());
    }
}
