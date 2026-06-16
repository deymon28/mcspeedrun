package org.speedrun.speedrun.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerTest {
    @Test
    void visibleStageDisplayModesTrackFutureStageProgress() {
        assertFalse(TaskManager.tracksVisibleStageProgress(ConfigManager.TaskDisplayMode.ACTIVE_STAGE));
        assertTrue(TaskManager.tracksVisibleStageProgress(ConfigManager.TaskDisplayMode.ALL_STAGES));
        assertTrue(TaskManager.tracksVisibleStageProgress(ConfigManager.TaskDisplayMode.ALL_GAME_STAGES));
    }
}
