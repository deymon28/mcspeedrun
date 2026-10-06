package org.speedrun.speedrun.platform;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Bounded CPU work only: tasks must use snapshots and captured immutable data. */
public final class SnapshotExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile long generation;

    public SnapshotExecutor(int threads, int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "Speedrun-snapshot-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean hasCapacity() {
        return !executor.isShutdown() && executor.getQueue().remainingCapacity() > 0;
    }

    public <T> CompletableFuture<T> compute(Supplier<T> computation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        long submittedGeneration = generation;
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        try {
            executor.execute(() -> {
                if (result.isDone() || generation != submittedGeneration) {
                    result.cancel(false);
                    return;
                }
                try {
                    T value = computation.get();
                    if (generation == submittedGeneration) result.complete(value);
                    else result.cancel(false);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    public void invalidate() {
        generation++;
        pending.forEach(future -> future.completeExceptionally(new CancellationException("Run state changed")));
        executor.getQueue().clear();
    }

    public int queuedTasks() { return executor.getQueue().size(); }
    public int activeTasks() { return executor.getActiveCount(); }

    @Override
    public void close() {
        invalidate();
        executor.shutdownNow();
    }
}
