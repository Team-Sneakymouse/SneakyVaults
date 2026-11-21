package net.sneakymouse.sneakyvaults.persistence;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Shares one in-flight load per key, so two quick requests for the same vault get the same data
 * instead of building two inventories for it.
 *
 * <p>Server thread only. The returned future completes on the server thread, and the key is forgotten
 * just before it completes, so every caller that is waiting runs in that same server-thread task.
 * That task is where callers turn the data into a cached vault: no close or save can run in between.
 */
public final class PendingLoads<K, V> {

    private final Map<K, CompletableFuture<V>> pending = new HashMap<>();
    private final Executor serverThread;

    public PendingLoads(Executor serverThread) {
        this.serverThread = serverThread;
    }

    /**
     * @param start Starts the load when none is in flight for this key. May complete on any thread.
     * @return Completes on the server thread with the loaded value.
     */
    public CompletableFuture<V> load(K key, Supplier<CompletableFuture<V>> start) {
        CompletableFuture<V> inFlight = pending.get(key);
        if(inFlight != null) return inFlight;

        CompletableFuture<V> result = new CompletableFuture<>();
        pending.put(key, result);
        start.get().whenCompleteAsync((value, error) -> {
            pending.remove(key, result);
            if(error != null)
                result.completeExceptionally(PersistenceQueue.cause(error));
            else
                result.complete(value);
        }, serverThread);
        return result;
    }
}
