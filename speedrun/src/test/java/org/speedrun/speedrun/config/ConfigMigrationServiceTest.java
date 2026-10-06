package org.speedrun.speedrun.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationServiceTest {
    private static final String VERSION = "26.3-4.0.0-TEST";
    private final ConfigMigrationService service = new ConfigMigrationService(ConfigFileStore::writeAtomically,
            Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC));

    @TempDir
    Path folder;

    private static String resource(String name) throws IOException {
        try (InputStream input = ConfigMigrationServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static YamlConfiguration defaults() throws Exception {
        return ConfigFileStore.parse(resource("config.yml"));
    }

    private Path oldConfig(String extra, String... replacements) throws IOException {
        String text = resource("migration/config-3.5.4.yml");
        for (int i = 0; i < replacements.length; i += 2) {
            assertTrue(text.contains(replacements[i]), replacements[i]);
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        Path file = folder.resolve("config.yml");
        Files.writeString(file, text + extra, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void upgradesAnOldConfigKeepingCompatibleValues() throws Exception {
        Path file = oldConfig("""
                custom:
                  flag: true
                """,
                "language: \"en\"", "language: \"UA\"",
                "gamemode: \"NORMAL\"", "gamemode: \"casual\"",
                "village-search-timeout: 600", "village-search-timeout: 900",
                "port: 8765", "port: \"not a number\"",
                "    - \"api:sound minecraft:entity.player.levelup 1.0 1.5\"", "    - \"say %player% finished a task\"",
                "  5_STRONGHOLD:", "  6_THE_END:\n    display-name: \"The End\"\n    world: \"THE_END\"\n    tasks: {}\n\n  5_STRONGHOLD:");
        byte[] original = Files.readAllBytes(file);

        ConfigMigrationService.Result result = service.migrate(file, defaults(), VERSION);
        YamlConfiguration config = result.config();
        ConfigMigrationReport report = result.report();

        assertEquals("MIGRATED", report.status());
        assertEquals(0, report.fromSchema());
        assertEquals(1, config.getInt("config-version"));
        assertEquals(VERSION, config.getString("plugin-version"));
        assertEquals("uk", config.getString("settings.language"));
        assertEquals("CASUAL", config.getString("settings.gamemode"));
        assertEquals(900, config.getInt("settings.village-search-timeout"));
        assertEquals(8765, config.getInt("web-config.port"));
        assertTrue(hasChange(report, "web-config.port", "RESET"));
        assertTrue(hasChange(report, "settings.pause-when-empty", "DEFAULTED"));
        assertTrue(hasChange(report, "custom.flag", "UNKNOWN_KEPT"));
        assertTrue(config.getBoolean("custom.flag"));
        assertEquals("The End", config.getString("progression.6_THE_END.display-name"));
        assertEquals(16, config.getInt("progression.1_GETTING_STARTED.tasks.OAK_LOG.amount"));
        assertEquals("say %player% finished a task", config.getStringList("rewards.on-task-complete").getFirst());
        assertTrue(report.transferred() > 50, "transferred " + report.transferred());

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("# Speedrun - main configuration"), "new comments are written");
        assertEquals("config-backups/config-20261006-120000.yml", report.backupFile());
        assertArrayEquals(original, Files.readAllBytes(folder.resolve(report.backupFile())));
    }

    @Test
    void movesLegacyPreScanSettings() throws Exception {
        Path file = oldConfig("", "settings:\n", "settings:\n  start-pre-scan:\n    enabled: true\n    radius: 640\n");
        String text = Files.readString(file).replace("casual:\n  enabled: true\n  start-pre-scan:\n    enabled: false\n",
                "casual:\n  enabled: true\n  start-pre-scan:\n");
        text = text.replace("    radius: 1000\n", "");
        Files.writeString(file, text);

        ConfigMigrationService.Result result = service.migrate(file, defaults(), VERSION);

        assertTrue(result.config().getBoolean("casual.start-pre-scan.enabled"));
        assertEquals(640, result.config().getInt("casual.start-pre-scan.radius"));
        assertFalse(result.config().contains("settings.start-pre-scan", true));
        assertTrue(hasChange(result.report(), "casual.start-pre-scan.enabled", "RENAMED"));
    }

    @Test
    void currentConfigIsLeftUntouched() throws Exception {
        Path file = oldConfig("");
        service.migrate(file, defaults(), VERSION);
        byte[] upgraded = Files.readAllBytes(file);

        ConfigMigrationService.Result second = service.migrate(file, defaults(), VERSION);

        assertEquals("CURRENT", second.report().status());
        assertArrayEquals(upgraded, Files.readAllBytes(file));
        try (Stream<Path> backups = Files.list(folder.resolve(ConfigMigrationService.BACKUP_FOLDER))) {
            assertEquals(1, backups.count());
        }
    }

    @Test
    void missingConfigIsCreatedFromDefaults() throws Exception {
        Path file = folder.resolve("config.yml");

        ConfigMigrationService.Result result = service.migrate(file, defaults(), VERSION);

        assertEquals("CREATED", result.report().status());
        assertTrue(Files.exists(file));
        assertEquals(VERSION, ConfigFileStore.read(file).getString("plugin-version"));
        assertFalse(Files.exists(folder.resolve(ConfigMigrationService.BACKUP_FOLDER)));
    }

    @Test
    void invalidYamlIsReportedAndNeverReplaced() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, "settings:\n  language: [unclosed\n");
        byte[] original = Files.readAllBytes(file);

        InvalidConfigurationException failure = assertThrows(InvalidConfigurationException.class,
                () -> service.migrate(file, defaults(), VERSION));

        assertFalse(failure.getMessage().contains("unclosed"), "parser details with file content are not echoed");
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void configFromANewerPluginIsRejected() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, "config-version: 99\n");

        assertThrows(InvalidConfigurationException.class, () -> service.migrate(file, defaults(), VERSION));
        assertEquals("config-version: 99\n", Files.readString(file));
    }

    @Test
    void compatibilityRulesFollowTheDefaultType() {
        assertTrue(ConfigMigrationService.compatible(true, false));
        assertFalse(ConfigMigrationService.compatible(true, "true"));
        assertTrue(ConfigMigrationService.compatible(0.5, 2));
        assertFalse(ConfigMigrationService.compatible(10, 2.5));
        assertTrue(ConfigMigrationService.compatible(List.of("a"), List.of("b", "c")));
        assertFalse(ConfigMigrationService.compatible("text", 5));
    }

    private static boolean hasChange(ConfigMigrationReport report, String path, String action) {
        return report.changes().stream().anyMatch(change -> change.path().equals(path) && change.action().equals(action));
    }
}
