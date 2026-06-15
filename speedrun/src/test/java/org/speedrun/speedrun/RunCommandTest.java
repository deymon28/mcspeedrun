package org.speedrun.speedrun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunCommandTest {
    @Test
    void resolvesStructureAliases() {
        assertEquals("LAVA_POOL", RunCommand.resolveStructureKey("lava pool"));
        assertEquals("LAVA_POOL", RunCommand.resolveStructureKey("lava-pool"));
        assertEquals("NETHER_PORTAL", RunCommand.resolveStructureKey("nether_portal"));
        assertEquals("NETHER_PORTAL", RunCommand.resolveStructureKey("nether portal"));
        assertEquals("END_PORTAL", RunCommand.resolveStructureKey("end portal"));
        assertEquals("FORTRESS", RunCommand.resolveStructureKey("fortress"));
        assertEquals("BASTION", RunCommand.resolveStructureKey("bastion"));
    }

    @Test
    void rejectsUnknownStructureAlias() {
        assertNull(RunCommand.resolveStructureKey("ancient city"));
    }
}
