package net.sneakymouse.sneakyvaults.persistence;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The in-memory copies of one kind of vault, and the rule that keeps them consistent with disk:
 *
 * <p><b>Once loaded, the in-memory value is the authoritative copy. An entry is only dropped when it is
 * clean: its last write reached disk and nothing is using it.</b>
 *
 * <ul>
 *   <li>So a cache miss means disk is up to date, and a read can never race a pending write of the same key.</li>
 *   <li>A failed write keeps the entry: the next open uses the in-memory value, and
 *       {@link #forEachNeedingSave} lists it for another try.</li>
 *   <li>A clean, unused entry needs no saving: its contents are already on disk.</li>
 * </ul>
 *
 * <p>Server thread only, apart from the read and write tasks, which run on the {@link PersistenceQueue}.
 * {@link #load} completes on the server thread. {@link #save} and {@link #whenWritten} complete on the
 * persistence thread when the write finishes: hop to the server thread before touching Bukkit state there
 * (completing them on the server thread would lose failure reports during shutdown, when the plugin can no
 * longer schedule tasks). This class has no Bukkit dependency: values are opaque, and the caller says when
 * one is in use.
 *
 * @param <D> The plain data read from disk.
 * @param <V> The live value built from it on the server thread (a vault).
 */
public final class VaultStore<D, V> {

    /** Server thread: build the live value from what was read, or return null when the open is no longer valid. */
    @FunctionalInterface
    public interface Builder<D, V> {
        @Nullable V build(@Nullable D data) throws IOException;
    }

    private static final class Entry<V> {
        V value;
        CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

        Entry(V value) {
            this.value = value;
        }

        boolean writeFailed() {
            return lastWrite.isCompletedExceptionally();
        }

        boolean written() {
            return lastWrite.isDone() && !lastWrite.isCompletedExceptionally();
        }
    }

    private final PersistenceQueue persistence;
    private final Executor serverThread;
    private final Predicate<V> inUse;
    private final boolean dropWhenClean;
    private final Map<String, Entry<V>> entries = new HashMap<>();
    private final PendingLoads<String, Optional<D>> loads;

    /**
     * @param inUse         True while something (a viewer) may still change the value.
     * @param dropWhenClean Whether clean, unused entries are dropped. When false, entries stay loaded
     *                      for the rest of the session.
     */
    public VaultStore(PersistenceQueue persistence, Executor serverThread, Predicate<V> inUse, boolean dropWhenClean) {
        this.persistence = persistence;
        this.serverThread = serverThread;
        this.inUse = inUse;
        this.dropWhenClean = dropWhenClean;
        this.loads = new PendingLoads<>(serverThread);
    }

    public @Nullable V get(String key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value;
    }

    /**
     * The loaded value, or a load of it. Concurrent loads of one key share one read, and every caller's
     * builder runs in the same server-thread task, so the first value built is the one all of them get.
     * @param read    Persistence thread: read the data, or return null when nothing is saved.
     * @param builder Server thread: called only when no value is loaded by then.
     * @return Completes on the server thread with the value, or null when the builder returned null.
     *         Completes exceptionally if the read or the builder failed; nothing is cached then.
     */
    public CompletableFuture<V> load(String key, PersistenceQueue.Task<D> read, Builder<D, V> builder) {
        Entry<V> loaded = entries.get(key);
        if(loaded != null) return CompletableFuture.completedFuture(loaded.value);

        return loads.load(key, () -> persistence.submit(() -> Optional.ofNullable(read.call())))
                .thenApply(data -> adopt(key, data.orElse(null), builder));
    }

    private @Nullable V adopt(String key, @Nullable D data, Builder<D, V> builder) {
        Entry<V> loaded = entries.get(key);
        if(loaded != null) return loaded.value;

        V value;
        try {
            value = builder.build(data);
        } catch(IOException exception) {
            throw new CompletionException(exception);
        }
        if(value != null)
            entries.put(key, new Entry<>(value));
        return value;
    }

    /**
     * Server thread: replace a key's value (or add one) without reading disk. A write still pending for the
     * old value keeps the entry from being dropped until it completes.
     */
    public void put(String key, V value) {
        Entry<V> entry = entries.get(key);
        if(entry == null)
            entries.put(key, new Entry<>(value));
        else
            entry.value = value;
    }

    /**
     * Server thread: queue a write of a key's current contents. The caller snapshots them on the server
     * thread and hands the write plain data. The entry is dropped once this write succeeds, if it is
     * still the latest write and the value is unused by then.
     * @return Completes on the persistence thread when written, or exceptionally if the write failed.
     */
    public CompletableFuture<Void> save(String key, PersistenceQueue.VoidTask write) {
        CompletableFuture<Void> written = persistence.run(write);
        Entry<V> entry = entries.get(key);
        if(entry == null) return written;

        entry.lastWrite = written;
        written.whenCompleteAsync((ignored, error) -> {
            if(entries.get(key) == entry && entry.lastWrite == written)
                dropIfClean(key, entry);
        }, serverThread);
        return written;
    }

    /** Server thread: drop a loaded value that ended up not being used, unless it isn't clean. */
    public void release(String key) {
        Entry<V> entry = entries.get(key);
        if(entry != null) dropIfClean(key, entry);
    }

    private void dropIfClean(String key, Entry<V> entry) {
        if(dropWhenClean && entry.written() && !inUse.test(entry.value))
            entries.remove(key, entry);
    }

    /**
     * Completes when the latest write of every matching key has succeeded, or exceptionally if one failed.
     * Snapshot of the writes pending now; later writes are not included. Completes on the persistence thread.
     */
    public CompletableFuture<Void> whenWritten(Predicate<String> keys) {
        return CompletableFuture.allOf(entries.entrySet().stream()
                .filter(entry -> keys.test(entry.getKey()))
                .map(entry -> entry.getValue().lastWrite)
                .toArray(CompletableFuture[]::new));
    }

    /** Server thread: drop matching entries that are clean and unused. Returns how many were dropped. */
    public int removeCleanIf(Predicate<String> keys) {
        int before = entries.size();
        entries.entrySet().removeIf(entry -> keys.test(entry.getKey())
                && entry.getValue().written() && !inUse.test(entry.getValue().value));
        return before - entries.size();
    }

    /**
     * Entries whose contents may not be on disk: in use, or with a failed last write.
     * Used on shutdown; clean, unused entries are already on disk.
     */
    public void forEachNeedingSave(BiConsumer<String, V> action) {
        for(Map.Entry<String, Entry<V>> entry : new ArrayList<>(entries.entrySet()))
            if(entry.getValue().writeFailed() || inUse.test(entry.getValue().value))
                action.accept(entry.getKey(), entry.getValue().value);
    }

    /** A copy of the loaded values. */
    public List<V> values() {
        List<V> values = new ArrayList<>();
        entries.values().forEach(entry -> values.add(entry.value));
        return values;
    }

    public boolean isLoaded(String key) {
        return entries.containsKey(key);
    }
}
