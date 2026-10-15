package org.speedrun.speedrun.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunStateManagerTest {
    @Test
    void parsesEncodedCoordinates() {
        assertArrayEquals(new double[]{120.5, 64.0, -340.25},
                RunStateManager.parseCoordinates("world;120.5;64.0;-340.25"));
    }

    @Test
    void rejectsMalformedOrMissingLocations() {
        assertNull(RunStateManager.parseCoordinates(""));
        assertNull(RunStateManager.parseCoordinates(null));
        assertNull(RunStateManager.parseCoordinates("world;1;2"));
        assertNull(RunStateManager.parseCoordinates(";1;2;3"));
        assertNull(RunStateManager.parseCoordinates("world;NaN;2;3"));
        assertNull(RunStateManager.parseCoordinates("world;x;2;3"));
    }

    @Test
    void emptyLocationEncodesAsEmptyText() {
        assertEquals("", RunStateManager.encode(null));
    }
}
