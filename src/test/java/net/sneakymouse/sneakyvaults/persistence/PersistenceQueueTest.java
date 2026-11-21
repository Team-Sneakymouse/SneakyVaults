package net.sneakymouse.sneakyvaults.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceQueueTest {

    private final PersistenceQueue queue = new PersistenceQueue("test-io");

    @AfterEach
    void stop() throws InterruptedException {
        queue.shutdown(5, TimeUnit.SECONDS);
    }

    @Test
    void runsTasksInSubmissionOrderOffTheCallingThread() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Thread> worker = new AtomicReference<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for(int i = 0; i < 200; i++) {
            int index = i;
            tasks.add(queue.run(() -> {
                worker.compareAndSet(null, Thread.currentThread());
                assertSame(worker.get(), Thread.currentThread());
                order.add(index);
            }));
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

        List<Integer> expected = new ArrayList<>();
        for(int i = 0; i < 200; i++) expected.add(i);
        assertEquals(expected, order);
        assertNotEquals(Thread.currentThread(), worker.get());
    }

    @Test
    void failedTaskCompletesExceptionallyAndLaterTasksStillRun() throws Exception {
        IOException failure = new IOException("disk full");
        CompletableFuture<Void> failed = queue.run(() -> { throw failure; });
        CompletableFuture<String> next = queue.submit(() -> "saved");

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
        assertSame(failure, PersistenceQueue.cause(thrown));
        assertEquals("saved", next.get(5, TimeUnit.SECONDS));
    }

    @Test
    void shutdownWaitsForEveryQueuedTask() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> done = Collections.synchronizedList(new ArrayList<>());
        queue.run(() -> {
            try {
                release.await();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.add(0);
        });
        for(int i = 1; i <= 5; i++) {
            int index = i;
            queue.run(() -> done.add(index));
        }

        new Thread(release::countDown).start();
        assertTrue(queue.shutdown(5, TimeUnit.SECONDS));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), done);
    }

    @Test
    void shutdownReportsTasksStillRunningAfterTheTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        queue.run(() -> {
            try {
                release.await();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertFalse(queue.shutdown(50, TimeUnit.MILLISECONDS));
        release.countDown();
    }

    @Test
    void taskSubmittedAfterShutdownRunsOnTheCallingThreadAfterTheQueue() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        queue.run(() -> order.add("queued"));
        assertTrue(queue.shutdown(5, TimeUnit.SECONDS));

        AtomicReference<Thread> ranOn = new AtomicReference<>();
        CompletableFuture<Void> late = queue.run(() -> {
            ranOn.set(Thread.currentThread());
            order.add("late");
        });

        assertTrue(late.isDone());
        late.get();
        assertSame(Thread.currentThread(), ranOn.get());
        assertEquals(List.of("queued", "late"), order);
    }

    @Test
    void failedTaskAfterShutdownCompletesExceptionally() throws Exception {
        assertTrue(queue.shutdown(5, TimeUnit.SECONDS));
        IOException failure = new IOException("disk full");

        CompletableFuture<Void> late = queue.run(() -> { throw failure; });

        assertSame(failure, PersistenceQueue.cause(assertThrows(ExecutionException.class, late::get)));
    }
}
