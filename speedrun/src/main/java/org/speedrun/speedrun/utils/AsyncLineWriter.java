package org.speedrun.speedrun.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Appends text lines to a file on a dedicated background thread.
 *
 * <p>Log lines are produced on the server thread (they need live game data), but disk writes
 * and flushes happen on the writer thread, so slow disks never stall a tick. The queue is
 * bounded: if the disk cannot keep up, excess lines are dropped and counted instead of
 * growing memory without limit. {@link #close()} drains every queued line.</p>
 */
public final class AsyncLineWriter implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 50_000;
    private static final int BATCH_SIZE = 512;
    private static final String POISON = new String("\u0000close");

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;
    private final BufferedWriter writer;
    private final Consumer<String> errorReporter;
    private volatile boolean closed;

    public AsyncLineWriter(Path file, String threadName, Consumer<String> errorReporter) throws IOException {
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        this.errorReporter = errorReporter;
        this.thread = new Thread(this::run, threadName);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Queues one line; never blocks the caller. */
    public void write(String line) {
        if (closed || !queue.offer(line)) {
            dropped.incrementAndGet();
        }
    }

    public long droppedLines() {
        return dropped.get();
    }

    private void run() {
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        boolean failed = false;
        try {
            while (true) {
                String first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                boolean stop = false;
                for (String line : batch) {
                    if (line == POISON) {
                        stop = true;
                        continue;
                    }
                    if (!failed) {
                        try {
                            writer.write(line);
                            writer.newLine();
                        } catch (IOException exception) {
                            failed = true;
                            errorReporter.accept("Log writing failed: " + exception.getMessage());
                        }
                    }
                }
                batch.clear();
                if (!failed) {
                    try {
                        writer.flush();
                    } catch (IOException exception) {
                        failed = true;
                        errorReporter.accept("Log flushing failed: " + exception.getMessage());
                    }
                }
                if (stop) {
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Nothing more can be written.
            }
        }
    }

    /** Writes all queued lines and closes the file (waits up to five seconds). */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        long lost = dropped.get();
        if (lost > 0) {
            queue.offer("# " + lost + " log lines were dropped because the disk could not keep up.");
        }
        closed = true;
        try {
            if (!queue.offer(POISON, 5, TimeUnit.SECONDS)) {
                thread.interrupt();
                errorReporter.accept("Log writer did not drain in time; remaining lines were dropped.");
            }
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
