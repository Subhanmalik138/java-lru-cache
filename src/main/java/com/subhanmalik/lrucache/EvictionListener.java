package com.subhanmalik.lrucache;

/**
 * Called whenever an entry leaves the cache for a reason other than an
 * explicit {@link LruCache#remove(Object)} or {@link LruCache#clear()}.
 *
 * <p>Listeners run while the cache lock is held, so they must be fast and
 * must never call back into the cache (that would deadlock).
 */
@FunctionalInterface
public interface EvictionListener<K, V> {

    void onEviction(K key, V value, EvictionReason reason);

    /** Why an entry was evicted. */
    enum EvictionReason {
        /** The cache was full and this was the least recently used entry. */
        CAPACITY,
        /** The entry's time-to-live elapsed and it was reaped on access. */
        EXPIRED
    }
}
