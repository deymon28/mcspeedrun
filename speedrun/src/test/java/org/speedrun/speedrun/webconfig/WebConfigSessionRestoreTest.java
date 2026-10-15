package org.speedrun.speedrun.webconfig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WebConfigSessionRestoreTest {
    @Test
    void heldSectionIsRestoredAsAReadableSection() {
        YamlConfiguration active = new YamlConfiguration();
        active.set("progression.settings.task-display-mode", "ALL_GAME_STAGES");
        active.set("progression.1_START.display-name", "Getting Started");
        active.set("progression.1_START.tasks.OAK_LOG.amount", 16);

        YamlConfiguration next = new YamlConfiguration();
        next.set("progression.1_START.display-name", "Renamed during the run");

        WebConfigSession.restore(next, active, "progression");

        assertNotNull(next.getConfigurationSection("progression"));
        assertEquals("Getting Started", next.getString("progression.1_START.display-name"));
        assertEquals(16, next.getInt("progression.1_START.tasks.OAK_LOG.amount"));
        assertEquals("ALL_GAME_STAGES", next.getString("progression.settings.task-display-mode"));
    }

    @Test
    void missingValueIsRemoved() {
        YamlConfiguration active = new YamlConfiguration();
        YamlConfiguration next = new YamlConfiguration();
        next.set("settings.language", "uk");

        WebConfigSession.restore(next, active, "settings.language");

        assertFalse(next.contains("settings.language"));
    }

    @Test
    void loopbackDetectionUsesTheResolvedAddress() {
        org.junit.jupiter.api.Assertions.assertTrue(WebConfigManager.isLoopbackBind("127.0.0.1"));
        org.junit.jupiter.api.Assertions.assertTrue(WebConfigManager.isLoopbackBind("0:0:0:0:0:0:0:1"));
        org.junit.jupiter.api.Assertions.assertTrue(WebConfigManager.isLoopbackBind("[::1]"));
        org.junit.jupiter.api.Assertions.assertTrue(WebConfigManager.isLoopbackBind(""));
        org.junit.jupiter.api.Assertions.assertTrue(WebConfigManager.isLoopbackBind("localhost"));
        assertFalse(WebConfigManager.isLoopbackBind("0.0.0.0"));
        assertFalse(WebConfigManager.isLoopbackBind("192.168.1.20"));
        assertFalse(WebConfigManager.isLoopbackBind("example.org"));
    }
}
