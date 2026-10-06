package org.speedrun.speedrun.webconfig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigSchemaTest {
    @Test
    void rejectsUnknownConfigPaths() {
        WebConfigValidationResult result = WebConfigSchema.validatePatch(new JSONObject()
                .put("server.op-everyone", true));

        assertFalse(result.valid());
        assertTrue(result.errors().getFirst().contains("Unsupported config path"));
    }

    @Test
    void validatesEnumsAndNumberLimits() {
        WebConfigValidationResult result = WebConfigSchema.validatePatch(new JSONObject()
                .put("settings.gamemode", "CREATIVE")
                .put("casual.nether_gold_highlight.radius", 200));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("settings.gamemode")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("casual.nether_gold_highlight.radius")));
    }

    @Test
    void acceptsStructuredProgressionAndRewards() {
        JSONObject progression = new JSONObject()
                .put("1_TEST", new JSONObject()
                        .put("display-name", "Test")
                        .put("world", "NORMAL")
                        .put("tasks", new JSONObject()
                                .put("IRON_INGOT", new JSONObject()
                                        .put("amount", 3)
                                        .put("display-name", "Iron")
                                        .put("srbp", false))));
        JSONObject rewards = new JSONObject()
                .put("enabled", true)
                .put("on-task-complete", new JSONArray().put("api:sound minecraft:entity.player.levelup 1 1"));

        WebConfigValidationResult result = WebConfigSchema.validatePatch(new JSONObject()
                .put("progression", progression)
                .put("rewards", rewards));

        assertTrue(result.valid(), result.errors().toString());
    }

    @Test
    void appliesOnlyWhitelistedPatchValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.gamemode", "NORMAL");
        config.set("casual.nether_gold_highlight.radius", 24);
        config.set("settings.language", "en");

        WebConfigSchema.applyPatch(config, new JSONObject()
                .put("settings.gamemode", "casual")
                .put("settings.language", "UK")
                .put("casual.nether_gold_highlight.radius", 32)
                .put("not.allowed", true));

        assertEquals("CASUAL", config.getString("settings.gamemode"));
        assertEquals("uk", config.getString("settings.language"));
        assertEquals(32, config.getInt("casual.nether_gold_highlight.radius"));
        assertFalse(config.contains("not.allowed"));
    }

    @Test
    void acceptsLanguageAliasesAndRejectsUnsupportedLanguages() {
        WebConfigValidationResult accepted = WebConfigSchema.validatePatch(new JSONObject()
                .put("settings.language", "English"));
        WebConfigValidationResult rejected = WebConfigSchema.validatePatch(new JSONObject()
                .put("settings.language", "xx"));

        assertTrue(accepted.valid(), accepted.errors().toString());
        assertFalse(rejected.valid());
        assertTrue(rejected.errors().stream().anyMatch(error -> error.contains("settings.language")));
    }

    @Test
    void exposesCanonicalLanguageValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.language", "UK");

        assertEquals("uk", WebConfigSchema.currentValues(config).getString("settings.language"));
    }

    @Test
    void marksImpactsForPatch() {
        var impacts = WebConfigSchema.impactsForPatch(new JSONObject()
                .put("settings.language", "uk")
                .put("casual.nether_gold_highlight.enabled", false)
                .put("progression", new JSONObject()));

        assertTrue(impacts.contains(WebConfigField.ApplyImpact.INSTANT));
        assertTrue(impacts.contains(WebConfigField.ApplyImpact.RESTART_TASK));
        assertTrue(impacts.contains(WebConfigField.ApplyImpact.RUN_SENSITIVE));
    }

    @Test
    void exposesGroupMetadataForNestedConfigSections() {
        JSONObject waypointType = WebConfigSchema.field("casual.structure_waypoints.type").toJson();

        assertEquals("casual.waypoints", waypointType.getString("group"));
        assertEquals("Structure Waypoints", waypointType.getString("groupLabel"));
    }

    @Test
    void exposesDependencyMetadataForParentControlledSettings() {
        JSONObject casualEnabled = WebConfigSchema.field("casual.enabled").toJson();
        JSONObject waypointType = WebConfigSchema.field("casual.structure_waypoints.type").toJson();
        JSONObject locateCalls = WebConfigSchema.field("casual.start-pre-scan.locate.calls-per-run").toJson();

        assertEquals("settings.gamemode", casualEnabled.getString("parentPath"));
        assertEquals("CASUAL", casualEnabled.getString("parentValue"));
        assertEquals("casual.structure_waypoints.enabled", waypointType.getString("parentPath"));
        assertTrue(waypointType.getBoolean("parentValue"));
        assertEquals("casual.start-pre-scan.mode", locateCalls.getString("parentPath"));
        assertEquals("LOCATE", locateCalls.getString("parentValue"));
    }

    @Test
    void scalarPatchPreservesUnrelatedNestedSettingsAndLeavesSourceUnchanged() throws InvalidConfigurationException {
        YamlConfiguration source = exampleConfig();

        YamlConfiguration patched = WebConfigSchema.patchedCopy(source,
                new JSONObject().put("settings.language", "uk"));

        assertEquals("uk", patched.getString("settings.language"));
        assertEquals("en", source.getString("settings.language"));
        assertEquals("CASUAL", patched.getString("settings.gamemode"));
        assertEquals(1.5, patched.getDouble("settings.scale-resources-by-playercount.multiplier"));
        assertTrue(patched.getBoolean("casual.start-pre-scan.enabled"));
        assertEquals(7, patched.getInt("progression.1_TEST.tasks.IRON_INGOT.amount"));
        assertNotNull(patched.getConfigurationSection("progression.1_TEST.tasks"));
        assertEquals(java.util.List.of("api:sound minecraft:entity.player.levelup 1 1"),
                patched.getStringList("rewards.on-task-complete"));

        patched.set("progression.1_TEST.tasks.IRON_INGOT.amount", 12);
        assertEquals(7, source.getInt("progression.1_TEST.tasks.IRON_INGOT.amount"));
    }

    @Test
    void structuredPatchesReplaceSectionsAndRemainReadableBeforeSaving() throws InvalidConfigurationException {
        YamlConfiguration config = exampleConfig();
        JSONObject progression = new JSONObject()
                .put("2_REPLACEMENT", new JSONObject()
                        .put("world", "NETHER")
                        .put("tasks", new JSONObject()
                                .put("BLAZE_ROD", new JSONObject().put("amount", 8).put("srbp", false))));
        JSONObject rewards = new JSONObject()
                .put("enabled", false)
                .put("on-stage-complete", new JSONArray().put("api:sound minecraft:block.end_portal.spawn 1 1"));

        WebConfigSchema.applyPatch(config, new JSONObject()
                .put("progression", progression)
                .put("rewards", rewards));

        assertNotNull(config.getConfigurationSection("progression.2_REPLACEMENT.tasks.BLAZE_ROD"));
        assertEquals("NETHER", config.getString("progression.2_REPLACEMENT.world"));
        assertEquals(8, config.getInt("progression.2_REPLACEMENT.tasks.BLAZE_ROD.amount"));
        assertFalse(config.contains("progression.1_TEST"));
        assertNotNull(config.getConfigurationSection("rewards"));
        assertFalse(config.getBoolean("rewards.enabled", true));
        assertEquals(java.util.List.of("api:sound minecraft:block.end_portal.spawn 1 1"),
                config.getStringList("rewards.on-stage-complete"));
        assertFalse(config.contains("rewards.on-task-complete"));
        assertEquals("CASUAL", config.getString("settings.gamemode"));
    }

    @Test
    void scalarAndStructuredPatchesSurviveYamlRoundTrip() throws InvalidConfigurationException {
        YamlConfiguration source = exampleConfig();
        YamlConfiguration patched = WebConfigSchema.patchedCopy(source, new JSONObject()
                .put("settings.language", "uk")
                .put("rewards", new JSONObject()
                        .put("enabled", true)
                        .put("on-task-complete", new JSONArray().put("say Task complete"))));
        YamlConfiguration reloaded = new YamlConfiguration();

        reloaded.loadFromString(patched.saveToString());

        assertEquals("uk", reloaded.getString("settings.language"));
        assertEquals("CASUAL", reloaded.getString("settings.gamemode"));
        assertTrue(reloaded.getBoolean("casual.start-pre-scan.enabled"));
        assertEquals(7, reloaded.getInt("progression.1_TEST.tasks.IRON_INGOT.amount"));
        assertEquals(java.util.List.of("say Task complete"), reloaded.getStringList("rewards.on-task-complete"));
        assertEquals(WebConfigSchema.currentValues(patched).toMap(), WebConfigSchema.currentValues(reloaded).toMap());
    }

    private YamlConfiguration exampleConfig() throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                settings:
                  language: en
                  gamemode: CASUAL
                  scale-resources-by-playercount:
                    multiplier: 1.5
                casual:
                  start-pre-scan:
                    enabled: true
                progression:
                  1_TEST:
                    world: NORMAL
                    tasks:
                      IRON_INGOT:
                        amount: 7
                        srbp: false
                rewards:
                  enabled: true
                  on-task-complete:
                    - "api:sound minecraft:entity.player.levelup 1 1"
                """);
        return config;
    }
}
