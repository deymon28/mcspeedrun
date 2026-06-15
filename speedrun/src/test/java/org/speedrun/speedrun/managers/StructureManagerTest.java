package org.speedrun.speedrun.managers;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureManagerTest {
    @Test
    void netherPortalLodestoneKeysAreWorldSpecific() {
        UUID overworldId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID netherId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertEquals("NETHER_PORTAL:" + overworldId,
                StructureManager.hiddenLodestoneStorageKey("NETHER_PORTAL", overworldId));
        assertEquals("NETHER_PORTAL:" + netherId,
                StructureManager.hiddenLodestoneStorageKey("NETHER_PORTAL", netherId));
    }

    @Test
    void nonPortalLodestoneKeysStayStable() {
        UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        assertEquals("VILLAGE", StructureManager.hiddenLodestoneStorageKey("VILLAGE", worldId));
        assertEquals("PLAYER_DEATH:player-id", StructureManager.hiddenLodestoneStorageKey("PLAYER_DEATH:player-id", worldId));
    }
}
