package org.speedrun.speedrun.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeUtilTest {
    @Test
    void formatsFullRunTimer() {
        assertEquals("00:00:00", TimeUtil.format(0));
        assertEquals("00:01:05", TimeUtil.format(65));
        assertEquals("01:01:01", TimeUtil.format(3661));
    }

    @Test
    void clampsNegativeFullRunTimerToZero() {
        assertEquals("00:00:00", TimeUtil.format(-10));
    }

    @Test
    void formatsMinuteSecondTimer() {
        assertEquals("00:00", TimeUtil.formatMinutesSeconds(0));
        assertEquals("10:00", TimeUtil.formatMinutesSeconds(600));
        assertEquals("61:01", TimeUtil.formatMinutesSeconds(3661));
    }
}
