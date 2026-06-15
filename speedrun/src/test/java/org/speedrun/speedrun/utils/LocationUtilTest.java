package org.speedrun.speedrun.utils;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocationUtilTest {
    @Test
    void formatsNullAsUnknown() {
        assertEquals("???", LocationUtil.format(null));
    }

    @Test
    void formatsBlockCoordinates() {
        assertEquals("10, 64, -4", LocationUtil.format(new Location(null, 10.9, 64.1, -3.2)));
    }

    @Test
    void triangulatesCrossingEyeThrows() {
        Location result = LocationUtil.triangulate(0, 0, -90f, 10, -10, 0f);

        assertEquals(10.0, result.getX(), 0.0001);
        assertEquals(64.0, result.getY(), 0.0001);
        assertEquals(0.0, result.getZ(), 0.0001);
    }

    @Test
    void returnsNullForParallelTriangulationLines() {
        assertNull(LocationUtil.triangulate(0, 0, 0f, 10, 10, 0f));
    }
}
