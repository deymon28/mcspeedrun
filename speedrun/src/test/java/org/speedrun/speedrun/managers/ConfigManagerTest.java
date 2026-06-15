package org.speedrun.speedrun.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigManagerTest {
    @Test
    void parsesTaskDisplayModesCaseInsensitively() {
        assertEquals(ConfigManager.TaskDisplayMode.ACTIVE_STAGE,
                ConfigManager.parseTaskDisplayMode("active_stage", null));
        assertEquals(ConfigManager.TaskDisplayMode.ALL_STAGES,
                ConfigManager.parseTaskDisplayMode("ALL_STAGES", null));
        assertEquals(ConfigManager.TaskDisplayMode.ALL_GAME_STAGES,
                ConfigManager.parseTaskDisplayMode("all_game_stages", null));
    }

    @Test
    void fallsBackForInvalidTaskDisplayMode() {
        assertEquals(ConfigManager.TaskDisplayMode.ACTIVE_STAGE,
                ConfigManager.parseTaskDisplayMode("bad", ConfigManager.TaskDisplayMode.ACTIVE_STAGE));
    }

    @Test
    void parsesCoordinateDisplayModesCaseInsensitively() {
        assertEquals(ConfigManager.CoordinateDisplayMode.UNIFIED,
                ConfigManager.parseCoordinateDisplayMode("unified", null));
        assertEquals(ConfigManager.CoordinateDisplayMode.SEPARATE,
                ConfigManager.parseCoordinateDisplayMode("SEPARATE", null));
        assertEquals(ConfigManager.CoordinateDisplayMode.CONDITIONAL,
                ConfigManager.parseCoordinateDisplayMode("conditional", null));
    }

    @Test
    void parsesStartPreScanModesCaseInsensitively() {
        assertEquals(ConfigManager.StartPreScanMode.SAFE,
                ConfigManager.parseStartPreScanMode("safe", null));
        assertEquals(ConfigManager.StartPreScanMode.BALANCED,
                ConfigManager.parseStartPreScanMode("BALANCED", null));
        assertEquals(ConfigManager.StartPreScanMode.AGGRESSIVE,
                ConfigManager.parseStartPreScanMode("aggressive", null));
        assertEquals(ConfigManager.StartPreScanMode.LOCATE,
                ConfigManager.parseStartPreScanMode("locate", null));
    }

    @Test
    void exposesStartPreScanProfileDefaults() {
        assertEquals(0, ConfigManager.defaultStartPreScanChunksPerRun(ConfigManager.StartPreScanMode.SAFE));
        assertEquals(2, ConfigManager.defaultStartPreScanChunksPerRun(ConfigManager.StartPreScanMode.BALANCED));
        assertEquals(8, ConfigManager.defaultStartPreScanChunksPerRun(ConfigManager.StartPreScanMode.AGGRESSIVE));
        assertEquals(0, ConfigManager.defaultStartPreScanChunksPerRun(ConfigManager.StartPreScanMode.LOCATE));

        assertEquals(40, ConfigManager.defaultStartPreScanPeriodTicks(ConfigManager.StartPreScanMode.SAFE));
        assertEquals(20, ConfigManager.defaultStartPreScanPeriodTicks(ConfigManager.StartPreScanMode.BALANCED));
        assertEquals(10, ConfigManager.defaultStartPreScanPeriodTicks(ConfigManager.StartPreScanMode.AGGRESSIVE));
        assertEquals(40, ConfigManager.defaultStartPreScanPeriodTicks(ConfigManager.StartPreScanMode.LOCATE));

        assertEquals(0, ConfigManager.defaultStartPreScanMaxQueuedChunks(ConfigManager.StartPreScanMode.SAFE));
        assertEquals(1500, ConfigManager.defaultStartPreScanMaxQueuedChunks(ConfigManager.StartPreScanMode.BALANCED));
        assertEquals(5000, ConfigManager.defaultStartPreScanMaxQueuedChunks(ConfigManager.StartPreScanMode.AGGRESSIVE));
        assertEquals(0, ConfigManager.defaultStartPreScanMaxQueuedChunks(ConfigManager.StartPreScanMode.LOCATE));
    }
}
