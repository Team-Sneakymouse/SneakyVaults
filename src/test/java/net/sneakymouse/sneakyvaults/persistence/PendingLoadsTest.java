package net.sneakymouse.sneakyvaults.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingLoadsTest {

    /** Stands in for the Bukkit scheduler: tasks run only when the test ticks it. */
    private static final class ServerThread implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int tick() {
            int ran = 0;
            Runnable task;
            while((task = tasks.poll()) != null) {
                task.run();
                ran++;
            }
            return ran;
        }
    }

    private final ServerThread serverThread = new ServerThread();
    private final PendingLoads<String, String> loads = new PendingLoads<>(serverThread);

    @Test
    void concurrentRequestsShareOneLoad() {
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<String> disk = new CompletableFuture<>();

        CompletableFuture<String> first = loads.load("vault", () -> { started.incrementAndGet(); return disk; });
        CompletableFuture<String> second = loads.load("vault", () -> { started.incrementAndGet(); return disk; });

        assertSame(first, second);
        assertEquals(1, started.get());
    }

    @Test
    void completesOnTheServerThreadAndEveryWaiterRunsInThatTask() {
        CompletableFuture<String> disk = new CompletableFuture<>();
        List<String> seen = new ArrayList<>();
        loads.load("vault", () -> disk).thenAccept(value -> seen.add("first " + value));
        loads.load("vault", () -> disk).thenAccept(value -> seen.add("second " + value));

        disk.complete("items");
        assertTrue(seen.isEmpty(), "must not complete on the I/O thread");

        // One scheduled task ran both waiters (CompletableFuture runs them in no guaranteed order).
        assertEquals(1, serverThread.tick());
        assertEquals(2, seen.size());
        assertTrue(seen.containsAll(List.of("first items", "second items")));
    }

    @Test
    void forgetsTheKeyBeforeWaitersRunSoTheyCanStartAFreshLoad() {
        CompletableFuture<String> disk = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();
        List<CompletableFuture<String>> reloads = new ArrayList<>();
        loads.load("vault", () -> disk).thenAccept(value ->
                reloads.add(loads.load("vault", () -> { started.incrementAndGet(); return new CompletableFuture<>(); })));

        disk.complete("items");
        serverThread.tick();

        assertEquals(1, started.get());
        assertFalse(reloads.getFirst().isDone());
    }

    @Test
    void failurePassesTheUnwrappedCauseAndForgetsTheKey() {
        IOException failure = new IOException("Invalid YAML");
        CompletableFuture<String> disk = CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(failure));
        CompletableFuture<String> loaded = loads.load("vault", () -> disk);
        serverThread.tick();

        assertTrue(loaded.isCompletedExceptionally());
        Throwable cause = loaded.handle((value, error) -> error).join();
        assertSame(failure, cause);

        AtomicInteger started = new AtomicInteger();
        loads.load("vault", () -> { started.incrementAndGet(); return new CompletableFuture<>(); });
        assertEquals(1, started.get());
    }

    @Test
    void differentKeysLoadSeparately() {
        AtomicInteger started = new AtomicInteger();
        loads.load("vault:1", () -> { started.incrementAndGet(); return new CompletableFuture<>(); });
        loads.load("vault:2", () -> { started.incrementAndGet(); return new CompletableFuture<>(); });
        assertEquals(2, started.get());
    }

    @Test
    void loadThatIsAlreadyDoneStillCompletesOnTheServerThread() {
        CompletableFuture<String> loaded = loads.load("vault", () -> CompletableFuture.completedFuture("items"));
        assertFalse(loaded.isDone());
        serverThread.tick();
        assertInstanceOf(String.class, loaded.join());
    }
}
