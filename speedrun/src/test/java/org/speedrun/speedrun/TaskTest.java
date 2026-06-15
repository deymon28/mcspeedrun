package org.speedrun.speedrun;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskTest {
    @Test
    void calculatesScaledRequirementWithCeiling() {
        assertEquals(16, Task.calculateRequiredAmount(16, false, 4, 0.5));
        assertEquals(16, Task.calculateRequiredAmount(16, true, 1, 0.5));
        assertEquals(40, Task.calculateRequiredAmount(16, true, 4, 0.5));
        assertEquals(25, Task.calculateRequiredAmount(10, true, 4, 0.5));
    }

    @Test
    void scalesTaskRequirementAndResetsWhenScalingDisabled() {
        Task task = new Task("OAK_LOG", Task.Type.ITEM, "Wood", 16, true, World.Environment.NORMAL);

        task.scale(4, 0.5);

        assertEquals(40, task.getRequiredAmount());

        Task unscaledTask = new Task("IRON_INGOT", Task.Type.ITEM, "Iron", 7, false, World.Environment.NORMAL);
        unscaledTask.scale(4, 0.5);

        assertEquals(7, unscaledTask.getRequiredAmount());
    }

    @Test
    void progressCannotGoNegativeAndCompletedTasksIgnoreProgressChanges() {
        Task task = new Task("COBBLESTONE", Task.Type.ITEM, "Cobble", 16, false, World.Environment.NORMAL);

        task.setProgress(-5);
        assertEquals(0, task.getProgress());

        task.addProgress(5);
        assertEquals(5, task.getProgress());

        task.completed = true;
        task.addProgress(5);
        task.setProgress(12);

        assertEquals(5, task.getProgress());
        assertFalse(task.isSrbpEnabled());
    }
}
