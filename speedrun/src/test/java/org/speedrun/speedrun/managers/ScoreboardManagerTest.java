package org.speedrun.speedrun.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardManagerTest {
    private static final String HEX_ORANGE = "§x§f§f§8§8§0§0";

    @Test
    void shortLinesStayInThePrefix() {
        String[] parts = ScoreboardManager.splitTeamText("§aTime §e00:01:05");

        assertEquals("§aTime §e00:01:05", parts[0]);
        assertEquals("", parts[1]);
    }

    @Test
    void splitNeverCutsAHexColorAndCarriesItIntoTheSuffix() {
        // Place a hex color so that the 64-character limit falls inside it.
        String text = "a".repeat(58) + HEX_ORANGE + "Village 120, 64, -340";

        String[] parts = ScoreboardManager.splitTeamText(text);

        assertEquals("a".repeat(58), parts[0]);
        assertTrue(parts[1].startsWith(HEX_ORANGE + "Village"), parts[1]);
        assertEquals(text, parts[0] + parts[1]);
    }

    @Test
    void suffixRepeatsColorAndDecorationsActiveAtTheCut() {
        String text = "§c§l" + "x".repeat(70);

        String[] parts = ScoreboardManager.splitTeamText(text);

        assertEquals(ScoreboardManager.TEAM_PART_LIMIT, parts[0].length());
        assertTrue(parts[1].startsWith("§c§lx"), parts[1]);
    }

    @Test
    void activeFormattingResetsDecorationsOnNewColor() {
        assertEquals("§b", ScoreboardManager.getActiveFormatting("§a§lBold§bPlain"));
        assertEquals(HEX_ORANGE + "§o", ScoreboardManager.getActiveFormatting("§a" + HEX_ORANGE + "§otext"));
        assertEquals("", ScoreboardManager.getActiveFormatting("§a§rplain"));
    }
}
