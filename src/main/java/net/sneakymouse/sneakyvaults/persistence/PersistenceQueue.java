package net.sneakymouse.sneakyvaults.persistence;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs every vault file read and write on one background thread, in the order they were submitted.
 *
 * <p>One thread keeps writes to the same file in order (a player's file holds all their vaults, so two
 * quick saves must not overtake each other) and makes every read see the writes submitted before it.
 *
 * <p>The queue is unbounded on purpose: refusing a save would lose items, and running it on the caller
 * would let it overtake queued writes. Its length is bounded in practice by how often players close vaults.
 *
 * <p>Tasks must not touch Bukkit state. Snapshot it on the server thread and hand plain data to the task.
 */
public final class PersistenceQueue {

    @FunctionalInterface
    public interface Task<T> {
        T call() throws IOException;
    }

    @FunctionalInterface
    public interface VoidTask {
        void run() throws IOException;
    }

    private final ExecutorService executor;
    private final Object lock = new Object();
    private boolean closed = false;

    public PersistenceQueue(String threadName) {
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            // A daemon, so an idle queue never keeps the server from exiting. Writes are not cut off,
            // because the plugin drains the queue with shutdown() before it finishes disabling.
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Queue a task. After {@link #shutdown} it runs on the calling thread instead, once the queue is drained.
     * @return Completes with the task's result, or exceptionally with what it threw.
     */
    public <T> CompletableFuture<T> submit(Task<T> task) {
        synchronized(lock) {
            if(!closed)
                return CompletableFuture.supplyAsync(() -> call(task), executor);
        }
        try {
            // Normally already drained by shutdown; the bound only matters if a queued task hangs.
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch(IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<Void> run(VoidTask task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Stop taking new work and wait for the queued tasks to finish.
     * @return True if every queued task finished within the timeout.
     */
    public boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException {
        synchronized(lock) {
            closed = true;
            executor.shutdown();
        }
        return executor.awaitTermination(timeout, unit);
    }

    /** The exception a task threw, without the wrappers CompletableFuture adds. */
    public static Throwable cause(Throwable throwable) {
        Throwable cause = throwable;
        while((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        return cause;
    }

    private static <T> T call(Task<T> task) {
        try {
            return task.call();
        } catch(IOException exception) {
            throw new CompletionException(exception);
        }
    }
}
