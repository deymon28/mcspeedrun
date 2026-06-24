package org.speedrun.speedrun.webconfig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        WebConfigSchema.applyPatch(config, new JSONObject()
                .put("settings.gamemode", "casual")
                .put("casual.nether_gold_highlight.radius", 32)
                .put("not.allowed", true));

        assertEquals("CASUAL", config.getString("settings.gamemode"));
        assertEquals(32, config.getInt("casual.nether_gold_highlight.radius"));
        assertFalse(config.contains("not.allowed"));
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
}
