package net.sneakymouse.sneakyvaults.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interleavings the reviews found, against the store's rule: an entry is authoritative and is only
 * dropped once its last write succeeded and it is unused.
 */
class VaultStoreTest {

    /** A vault: its contents, and whether a viewer has it open. */
    private static final class Vault {
        String contents;
        boolean open;

        Vault(String contents) {
            this.contents = contents;
        }
    }

    /** Stands in for the Bukkit scheduler: tasks run only when the test ticks. */
    private static final class ServerThread implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        void tick() {
            while(true) {
                Runnable task;
                synchronized(this) {
                    task = tasks.poll();
                }
                if(task == null) return;
                task.run();
            }
        }
    }

    private final PersistenceQueue queue = new PersistenceQueue("test-store", message -> {});
    private final ServerThread serverThread = new ServerThread();
    private final Map<String, String> disk = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final VaultStore<String, Vault> store = new VaultStore<>(queue, serverThread, vault -> vault.open, true);

    @AfterEach
    void stop() throws InterruptedException {
        queue.shutdown(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<Vault> load(String key) {
        return store.load(key, () -> { reads.incrementAndGet(); return disk.get(key); },
                data -> new Vault(data == null ? "" : data));
    }

    private CompletableFuture<Void> save(String key, Vault vault) {
        String snapshot = vault.contents;
        return store.save(key, () -> disk.put(key, snapshot));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Let queued disk work finish, then run the server-thread tasks it scheduled. */
    private void settle() throws Exception {
        queue.submit(() -> null).get(5, TimeUnit.SECONDS);
        serverThread.tick();
    }

    private Vault open(String key) throws Exception {
        CompletableFuture<Vault> loading = load(key);
        settle();
        Vault vault = loading.get(1, TimeUnit.SECONDS);
        vault.open = true;
        return vault;
    }

    private void close(String key, Vault vault) {
        vault.open = false;
        save(key, vault);
    }

    @Test
    void concurrentColdLoadsShareOneReadAndOneValue() throws Exception {
        disk.put("v", "diamond");
        CompletableFuture<Vault> first = load("v");
        CompletableFuture<Vault> second = load("v");
        settle();

        assertSame(first.get(), second.get());
        assertEquals("diamond", first.get().contents);
        assertEquals(1, reads.get());
    }

    @Test
    void reopenWhileTheSaveIsPendingUsesTheSameValueWithoutReading() throws Exception {
        disk.put("v", "diamond");
        Vault vault = open("v");
        vault.contents = "";                       // withdrew the diamond

        CountDownLatch stall = new CountDownLatch(1);
        queue.run(() -> await(stall));                   // the disk is slow
        close("v", vault);
        CompletableFuture<Vault> reopened = load("v");

        assertTrue(reopened.isDone(), "served from memory, not queued behind the save");
        assertSame(vault, reopened.get());
        assertEquals(1, reads.get());
        stall.countDown();
    }

    @Test
    void cleanUnusedEntryIsDroppedAndTheNextMissReadsTheLatestWrite() throws Exception {
        Vault vault = open("v");
        vault.contents = "emerald";
        close("v", vault);
        settle();

        assertFalse(store.isLoaded("v"));
        Vault reloaded = open("v");
        assertEquals("emerald", reloaded.contents);
        assertEquals(2, reads.get());
    }

    @Test
    void failedWriteKeepsTheEntryAndListsItForSaving() throws Exception {
        disk.put("v", "diamond");
        Vault vault = open("v");
        vault.contents = "";
        vault.open = false;
        store.save("v", () -> { throw new IOException("disk full"); });
        settle();

        assertTrue(store.isLoaded("v"), "the older file must not be read again");
        assertSame(vault, load("v").get());
        List<String> needing = new ArrayList<>();
        store.forEachNeedingSave((key, value) -> needing.add(key));
        assertEquals(List.of("v"), needing);
        store.release("v");
        assertTrue(store.isLoaded("v"), "release must not drop unwritten contents");

        save("v", vault);
        settle();
        assertFalse(store.isLoaded("v"));
        assertEquals("", disk.get("v"));
    }

    @Test
    void entryStillInUseWhenItsWriteCompletesStaysUntilTheLastClose() throws Exception {
        Vault vault = open("v");
        save("v", vault);                          // one viewer closed, another is still looking
        settle();
        assertTrue(store.isLoaded("v"));

        close("v", vault);
        settle();
        assertFalse(store.isLoaded("v"));
    }

    @Test
    void onlyTheLatestWriteDecidesWhenTheEntryIsDropped() throws Exception {
        Vault vault = open("v");
        vault.open = false;
        CountDownLatch stall = new CountDownLatch(1);
        CompletableFuture<Void> older = store.save("v", () -> { await(stall); disk.put("v", "old"); });
        CompletableFuture<Void> newer = store.save("v", () -> { throw new IOException("disk full"); });

        stall.countDown();
        older.handle((ignored, error) -> null).get(5, TimeUnit.SECONDS);
        settle();

        assertTrue(newer.isCompletedExceptionally());
        assertTrue(store.isLoaded("v"), "an older successful write must not drop contents a newer write failed to save");
    }

    @Test
    void builderReturningNullCachesNothing() throws Exception {
        CompletableFuture<Vault> loading = store.load("v", () -> "stale", data -> null);
        settle();

        assertNull(loading.get());
        assertFalse(store.isLoaded("v"));
    }

    @Test
    void failedReadCachesNothingAndReportsTheCause() throws Exception {
        IOException failure = new IOException("Invalid YAML");
        CompletableFuture<Vault> loading = store.load("v", () -> { throw failure; }, data -> new Vault("x"));
        settle();

        assertTrue(loading.isCompletedExceptionally());
        assertSame(failure, PersistenceQueue.cause(loading.handle((value, error) -> error).get()));
        assertFalse(store.isLoaded("v"));
    }

    @Test
    void releaseDropsAnUnusedLoadButKeepsOneInUse() throws Exception {
        CompletableFuture<Vault> loading = load("a");
        settle();
        store.release("a");
        assertFalse(store.isLoaded("a"));

        Vault open = open("b");
        store.release("b");
        assertTrue(store.isLoaded("b"));
        assertTrue(open.open);
        assertTrue(loading.isDone());
    }

    @Test
    void whenWrittenWaitsForPendingWritesAndFailsIfOneFailed() throws Exception {
        Vault a = open("shop:1");
        Vault b = open("shop:2");
        close("shop:1", a);
        b.open = false;
        store.save("shop:2", () -> { throw new IOException("disk full"); });

        CompletableFuture<Void> written = store.whenWritten(key -> key.startsWith("shop:"));
        settle();

        assertTrue(written.isCompletedExceptionally());
        assertFalse(store.isLoaded("shop:1"), "written and unused: dropped");
        assertEquals(0, store.removeCleanIf(key -> key.startsWith("shop:")));
        assertTrue(store.isLoaded("shop:2"), "the unwritten vault is kept");
    }

    @Test
    void whenWrittenSucceedsOnceEveryMatchingWriteIsOnDisk() throws Exception {
        Vault a = open("shop:1");
        Vault other = open("farm:1");
        CountDownLatch stall = new CountDownLatch(1);
        queue.run(() -> await(stall));
        close("shop:1", a);
        other.open = false;
        store.save("farm:1", () -> { throw new IOException("unrelated"); });

        CompletableFuture<Void> written = store.whenWritten(key -> key.startsWith("shop:"));
        assertFalse(written.isDone(), "waits for the pending shop write");
        stall.countDown();
        written.get(5, TimeUnit.SECONDS);
        assertFalse(written.isCompletedExceptionally(), "another owner's failed write does not count");
    }

    @Test
    void removeCleanIfDropsLoadedUnusedEntriesOnly() throws Exception {
        CompletableFuture<Vault> unused = load("shop:1");
        open("shop:2");
        settle();

        assertEquals(1, store.removeCleanIf(key -> key.startsWith("shop:")));
        assertFalse(store.isLoaded("shop:1"));
        assertTrue(store.isLoaded("shop:2"), "still in use");
        assertTrue(unused.isDone());
    }

    @Test
    void valuePutWhileALoadIsInFlightWinsOverTheLoadedData() throws Exception {
        disk.put("copy", "old copy");
        CompletableFuture<Vault> loading = load("copy");
        Vault fresh = new Vault("fresh template");
        store.put("copy", fresh);
        settle();

        assertSame(fresh, loading.get(), "the builder must not replace a value that is already loaded");
    }

    @Test
    void putReplacesTheValueWhileTheOldWriteIsPending() throws Exception {
        disk.put("copy", "old");
        Vault old = open("copy");
        old.contents = "edited";
        CountDownLatch stall = new CountDownLatch(1);
        queue.run(() -> await(stall));
        close("copy", old);

        // A forced reopen: a fresh copy replaces the old one and is opened at once.
        Vault fresh = new Vault("fresh template");
        store.put("copy", fresh);
        fresh.open = true;
        stall.countDown();
        settle();

        assertSame(fresh, store.get("copy"), "the open fresh copy is kept");
        assertEquals("edited", disk.get("copy"), "the old copy's close was still written");
    }

    @Test
    void storeThatKeepsEntriesNeverDropsThem() throws Exception {
        VaultStore<String, Vault> keeping = new VaultStore<>(queue, serverThread, vault -> vault.open, false);
        CompletableFuture<Vault> loading = keeping.load("v", () -> null, data -> new Vault(""));
        settle();
        Vault vault = loading.get();
        keeping.save("v", () -> disk.put("v", vault.contents));
        settle();

        assertTrue(keeping.isLoaded("v"));
        List<String> needing = new ArrayList<>();
        keeping.forEachNeedingSave((key, value) -> needing.add(key));
        assertTrue(needing.isEmpty(), "written and unused: already on disk");
    }
}
