package org.speedrun.speedrun.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncLineWriterTest {
    @TempDir
    Path folder;

    @Test
    void closeWritesEveryQueuedLine() throws Exception {
        Path file = folder.resolve("run.json");
        List<String> errors = new ArrayList<>();
        AsyncLineWriter writer = new AsyncLineWriter(file, "test-writer", errors::add);
        for (int i = 0; i < 1000; i++) {
            writer.write("{\"line\":" + i + "}");
        }
        writer.close();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1000, lines.size());
        // Every line stays valid JSON: notices about dropped lines never go into the file.
        assertTrue(lines.stream().allMatch(line -> line.startsWith("{")));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void linesAfterCloseAreDropped() throws Exception {
        Path file = folder.resolve("run.log");
        AsyncLineWriter writer = new AsyncLineWriter(file, "test-writer", ignored -> { });
        writer.close();
        writer.write("late");

        assertEquals(1, writer.droppedLines());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).isEmpty());
    }
}
