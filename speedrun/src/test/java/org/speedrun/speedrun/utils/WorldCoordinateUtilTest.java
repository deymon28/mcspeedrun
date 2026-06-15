package org.speedrun.speedrun.utils;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldCoordinateUtilTest {
    @Test
    void convertsOverworldToNetherScale() {
        assertTrue(WorldCoordinateUtil.canConvert(World.Environment.NORMAL, World.Environment.NETHER));
        assertEquals(12.5, WorldCoordinateUtil.scaleCoordinate(100.0, World.Environment.NORMAL, World.Environment.NETHER));
    }

    @Test
    void convertsNetherToOverworldScale() {
        assertTrue(WorldCoordinateUtil.canConvert(World.Environment.NETHER, World.Environment.NORMAL));
        assertEquals(800.0, WorldCoordinateUtil.scaleCoordinate(100.0, World.Environment.NETHER, World.Environment.NORMAL));
    }

    @Test
    void leavesUnlinkedDimensionsUnchanged() {
        assertFalse(WorldCoordinateUtil.canConvert(World.Environment.NORMAL, World.Environment.THE_END));
        assertEquals(100.0, WorldCoordinateUtil.scaleCoordinate(100.0, World.Environment.NORMAL, World.Environment.THE_END));
    }
}
