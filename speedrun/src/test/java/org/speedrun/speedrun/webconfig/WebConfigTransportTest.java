package org.speedrun.speedrun.webconfig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigTransportTest {
    @Test
    void loopbackEditorsOnlyAcceptLoopbackHostNames() {
        assertTrue(WebConfigManager.hostAllowed("127.0.0.1:8765", "127.0.0.1", 8765));
        assertTrue(WebConfigManager.hostAllowed("localhost:8765", "127.0.0.1", 8765));
        assertTrue(WebConfigManager.hostAllowed("[::1]:8765", "127.0.0.1", 8765));
        assertFalse(WebConfigManager.hostAllowed("attacker.example:8765", "127.0.0.1", 8765), "DNS rebinding");
        assertFalse(WebConfigManager.hostAllowed("127.0.0.1:9999", "127.0.0.1", 8765));
        assertFalse(WebConfigManager.hostAllowed(null, "127.0.0.1", 8765));
        assertTrue(WebConfigManager.hostAllowed("mc.example.org:8765", "0.0.0.0", 8765), "exposed editors rely on the token");
    }

    @Test
    void onlyBundledFilesAreServed() {
        assertEquals("index.html", WebConfigManager.staticFile("/"));
        assertEquals("app.js", WebConfigManager.staticFile("/app.js"));
        assertEquals("i18n/pt_BR.json", WebConfigManager.staticFile("/i18n/pt_BR.json"));
        assertNull(WebConfigManager.staticFile("/i18n/../config.json"));
        assertNull(WebConfigManager.staticFile("/i18n/xx.json"));
        assertNull(WebConfigManager.staticFile("/../config.yml"));
        assertNull(WebConfigManager.staticFile("/plugin.yml"));
    }

    @Test
    void tokenComparisonRejectsMissingAndWrongTokens() {
        assertTrue(WebConfigManager.tokensMatch("abc123", "abc123"));
        assertFalse(WebConfigManager.tokensMatch("abc123", "abc124"));
        assertFalse(WebConfigManager.tokensMatch("abc123", null));
        assertFalse(WebConfigManager.tokensMatch(null, null));
        assertFalse(WebConfigManager.tokensMatch("", ""));
    }

    @Test
    void runSensitiveSettingsWaitForTheRunToEnd() {
        assertEquals("pending-run-end", WebConfigSession.holdReason("settings.gamemode", true));
        assertNull(WebConfigSession.holdReason("settings.gamemode", false));
        assertEquals("pending-run-end", WebConfigSession.holdReason("progression", true));
        assertEquals("pending-restart", WebConfigSession.holdReason("web-config.port", false));
        assertNull(WebConfigSession.holdReason("settings.language", true));
    }

    @Test
    void newConsoleRewardLinesAreDetected() {
        YamlConfiguration saved = new YamlConfiguration();
        saved.set("rewards.on-task-complete", List.of("api:sound minecraft:block.note_block.bell", "say existing"));
        YamlConfiguration desired = new YamlConfiguration();
        desired.set("rewards.on-task-complete", List.of("api:sound minecraft:block.note_block.bell", "say existing", "op someone"));
        desired.set("rewards.on-stage-complete", List.of("  api:particle minecraft:flash"));

        assertEquals(List.of("op someone"), WebConfigSession.addedConsoleRewardLines(saved, desired));
    }
}
