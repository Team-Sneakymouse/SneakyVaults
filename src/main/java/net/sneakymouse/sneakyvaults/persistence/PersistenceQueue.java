package net.sneakymouse.sneakyvaults.persistence;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs every vault file read and write on one background thread, in the order they were submitted.
 *
 * <p>One thread keeps writes to the same file in order (a player's file holds all their vaults, so two
 * quick saves must not overtake each other) and makes every read see the writes submitted before it.
 *
 * <p>The queue is unbounded on purpose: refusing a save would lose items, and running it on the caller
 * would let it overtake queued writes. Its length is bounded in practice by how often players close vaults;
 * if it grows past {@link #BACKLOG_WARNING} tasks (a stalled disk), a warning is reported once a minute.
 *
 * <p>Tasks must not touch Bukkit state. Snapshot it on the server thread and hand plain data to the task.
 */
public final class PersistenceQueue {

    public static final int BACKLOG_WARNING = 500;
    private static final long WARNING_INTERVAL_MILLIS = 60_000;

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
    private final long lateWaitMillis;
    private final Consumer<String> warn;
    private final AtomicInteger backlog = new AtomicInteger();
    private final AtomicLong lastWarning = new AtomicLong();
    private volatile Thread worker;

    /** @param warn Receives a warning when the backlog grows past {@link #BACKLOG_WARNING}. */
    public PersistenceQueue(String threadName, Consumer<String> warn) {
        this(threadName, warn, 5_000);
    }

    /** @param lateWaitMillis How long a task submitted after shutdown waits for the queue to drain. */
    PersistenceQueue(String threadName, Consumer<String> warn, long lateWaitMillis) {
        this.lateWaitMillis = lateWaitMillis;
        this.warn = warn;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            // A daemon, so an idle queue never keeps the server from exiting. Writes are not cut off,
            // because the plugin drains the queue with shutdown() before it finishes disabling.
            thread.setDaemon(true);
            worker = thread;
            return thread;
        });
    }

    /**
     * Queue a task. After {@link #shutdown} it runs on the calling thread instead, once the queue is drained.
     * If the queue is still busy then, the task fails rather than overtaking the older writes.
     * A task submitted by a queued task (a continuation running on the worker) after shutdown runs at once,
     * as the next step of the queue.
     * @return Completes with the task's result, or exceptionally with what it threw.
     */
    public <T> CompletableFuture<T> submit(Task<T> task) {
        synchronized(lock) {
            if(!closed) {
                warnIfBacklogged(backlog.incrementAndGet());
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return call(task);
                    } finally {
                        backlog.decrementAndGet();
                    }
                }, executor);
            }
        }
        if(Thread.currentThread() != worker) {
            try {
                // Normally already drained by shutdown; the wait only matters while another thread drains it.
                if(!executor.awaitTermination(lateWaitMillis, TimeUnit.MILLISECONDS))
                    return CompletableFuture.failedFuture(new IOException("The persistence queue is still busy after shutdown"));
            } catch(InterruptedException exception) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(new IOException("Interrupted while waiting for the persistence queue", exception));
            }
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

    /** True once {@link #shutdown} was called. */
    boolean isShutdown() {
        synchronized(lock) {
            return closed;
        }
    }

    /** The exception a task threw, without the wrappers CompletableFuture adds. */
    public static Throwable cause(Throwable throwable) {
        Throwable cause = throwable;
        while((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        return cause;
    }

    private void warnIfBacklogged(int queued) {
        if(queued <= BACKLOG_WARNING) return;
        long now = System.currentTimeMillis();
        long last = lastWarning.get();
        if(now - last >= WARNING_INTERVAL_MILLIS && lastWarning.compareAndSet(last, now))
            warn.accept(queued + " vault reads and writes are waiting; the disk may be stalled.");
    }

    private static <T> T call(Task<T> task) {
        try {
            return task.call();
        } catch(IOException exception) {
            throw new CompletionException(exception);
        }
    }
}
