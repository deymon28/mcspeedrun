package org.speedrun.speedrun.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardUtilTest {
    @Test
    void consoleCommandsWithoutPlayerPlaceholderRunOnce() {
        assertTrue(RewardUtil.runsOncePerReward("say Stage complete!"));
        assertTrue(RewardUtil.runsOncePerReward("  give @a minecraft:bread 4"));
    }

    @Test
    void perPlayerRewardsRunForEveryTarget() {
        assertFalse(RewardUtil.runsOncePerReward("give %player% minecraft:bread 4"));
        assertFalse(RewardUtil.runsOncePerReward("api:sound minecraft:entity.player.levelup 1.0 1.5"));
        assertFalse(RewardUtil.runsOncePerReward(" api:particle minecraft:heart 10"));
    }
}
