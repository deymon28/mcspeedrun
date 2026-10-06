package org.speedrun.speedrun.platform;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.speedrun.speedrun.utils.AsyncLineWriter;
import org.speedrun.speedrun.utils.MessageUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSupportTest {
    @Test
    void parsesMinecraftVersionsOfAllServerFamilies() {
        assertEquals("26.3", ServerPlatform.parseMinecraftVersion("26.3-R0.1-SNAPSHOT"));
        assertEquals("26.3", ServerPlatform.parseMinecraftVersion("26.3.build.157-beta"));
        assertEquals("26.1.2", ServerPlatform.parseMinecraftVersion("26.1.2-R0.1-SNAPSHOT"));
        assertEquals("unknown", ServerPlatform.parseMinecraftVersion(null));
    }

    @Test
    void workerPoolStaysSmall() {
        assertEquals(1, ServerPlatform.defaultWorkerThreads(2));
        assertEquals(2, ServerPlatform.defaultWorkerThreads(16));
    }

    @Test
    void miniMessageAndLegacyRenderingKeepFormattingAndLinks() {
        PlatformText link = PlatformText.of(MessageUtil.parse(
                "<green>Editor: <click:open_url:'http://127.0.0.1:8765/#token=abc'><u>open</u></click>"));
        assertEquals("http://127.0.0.1:8765/#token=abc", link.clickUrl());
        assertTrue(link.miniMessage().contains("open_url"));

        PlatformText legacy = PlatformText.of(MessageUtil.parse("&aDone &e%time%"));
        assertEquals("§aDone §e%time%", legacy.legacy());
        assertNull(legacy.clickUrl());
        assertEquals("Done 00:01", MessageUtil.stripFormatting("&aDone &e00:01"));
        assertEquals("", PlatformText.of(Component.empty()).legacy());
    }

    @Test
    void mixedLegacyAndMiniMessageFormattingIsSupported() {
        assertEquals("§a§lHi", MessageUtil.toLegacy("&a<bold>Hi"));
        assertEquals("plain <not a tag", MessageUtil.toLegacy("plain <not a tag"));
    }

    @Test
    void snapshotWorkersRejectWhenFullAndCancelOnInvalidation() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (SnapshotExecutor executor = new SnapshotExecutor(1, 1)) {
            CompletableFuture<Integer> running = executor.compute(() -> {
                await(release);
                return 1;
            });
            CompletableFuture<Integer> queued = executor.compute(() -> 2);
            CompletableFuture<Integer> rejected = executor.compute(() -> 3);
            assertTrue(rejected.isCompletedExceptionally());

            executor.invalidate();
            release.countDown();
            assertThrows(CancellationException.class, () -> queued.getNow(0));
            assertThrows(Exception.class, () -> running.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void asyncLineWriterWritesEveryLineBeforeClosing(@org.junit.jupiter.api.io.TempDir Path folder) throws Exception {
        Path file = folder.resolve("log.txt");
        List<String> errors = new ArrayList<>();
        AsyncLineWriter writer = new AsyncLineWriter(file, "test-writer", errors::add);
        for (int i = 0; i < 2000; i++) {
            writer.write("line " + i);
        }
        writer.close();
        List<String> lines = Files.readAllLines(file);
        assertEquals(2000, lines.size());
        assertEquals("line 1999", lines.getLast());
        assertTrue(errors.isEmpty());
        writer.write("after close");
        assertEquals(1, writer.droppedLines());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
