package org.speedrun.speedrun.utils;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldChangeJournalTest {
    @Test
    void entriesSurviveASerializationRoundTrip() throws Exception {
        List<WorldChangeJournal.Entry> entries = List.of(
                new WorldChangeJournal.Entry("world", 10, -56, -20, Material.LODESTONE, "minecraft:deepslate[axis=y]"),
                new WorldChangeJournal.Entry("world", 3, 71, 9, Material.END_GATEWAY, "minecraft:air"));

        List<WorldChangeJournal.Entry> parsed = WorldChangeJournal.parse(WorldChangeJournal.serialize(entries));

        assertEquals(entries, parsed);
    }

    @Test
    void damagedEntriesAreSkipped() throws Exception {
        String text = """
                blocks:
                  - {world: world, x: 1, y: 2, z: 3, placed: NOT_A_BLOCK, previous: "minecraft:stone"}
                  - {world: world, x: 1, y: 2, previous: "minecraft:stone", placed: BEACON}
                  - {world: world, x: 4, y: 5, z: 6, placed: BEACON, previous: "minecraft:air"}
                """;

        List<WorldChangeJournal.Entry> parsed = WorldChangeJournal.parse(text);

        assertEquals(1, parsed.size());
        assertTrue(parsed.getFirst().placed() == Material.BEACON && parsed.getFirst().x() == 4);
    }
}
