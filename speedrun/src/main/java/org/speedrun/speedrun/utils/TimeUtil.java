package org.speedrun.speedrun.utils;

/**
 * A utility class for time-related formatting.
 * Provides static methods to convert a total number of seconds into human-readable time strings.
 */
public final class TimeUtil {
    /**
     * Formats a duration in total seconds into a "HH:MM:SS" string.
     * Ensures each component is zero-padded to two digits.
     *
     * @param totalSeconds The total number of seconds to format.
     * @return A formatted string like "01:23:45".
     */
    public static String format(long totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    /**
     * Formats a duration in total seconds into a "MM:SS" string.
     * Useful for shorter timers where hours are not needed.
     *
     * @param totalSeconds The total number of seconds to format.
     * @return A formatted string like "05:30".
     */
    public static String formatMinutesSeconds(long totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }

}