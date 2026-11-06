package com.subhanmalik.lrucache;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import com.subhanmalik.lrucache.EvictionListener.EvictionReason;

/**
 * A bounded, thread-safe, generic LRU (least recently used) cache.
 *
 * <p>Design:
 * <ul>
 *   <li>Backed by a {@link LinkedHashMap} in access-order mode, so the eldest
 *       entry is always the least recently used one and eviction is O(1).</li>
 *   <li>One {@link ReentrantLock} guards the map and the counters. A single
 *       lock (instead of a read/write pair) is deliberate: even a read
 *       mutates access order, so reads are writes here.</li>
 *   <li>Entries may carry an optional per-entry TTL. Expiry is lazy: an
 *       expired entry is removed (and the listener notified) the first time
 *       it is touched, and reaped in bulk by {@link #size()} and
 *       {@link #keysLeastRecentlyUsedFirst()}.</li>
 *   <li>Eviction is explicit, not via {@code removeEldestEntry}, so the
 *       eviction listener fires exactly once per evicted entry and the
 *       eviction reason is known.</li>
 * </ul>
 *
 * <p>Null keys and null values are rejected: a cache that silently stores
 * {@code null} makes "absent" and "cached null" indistinguishable.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class LruCache<K, V> {

    private static final long NO_EXPIRY = Long.MAX_VALUE;

    private final int capacity;
    private final Clock clock;
    private final EvictionListener<K, V> listener;
    private final ReentrantLock lock = new ReentrantLock();

    /** Access-ordered: iteration order is least-recently-used first. */
    private final Map<K, Entry<V>> map;

    private long hits;
    private long misses;
    private long evictions;

    public LruCache(int capacity) {
        this(capacity, Clock.systemUTC(), null);
    }

    public LruCache(int capacity, EvictionListener<K, V> listener) {
        this(capacity, Clock.systemUTC(), listener);
    }

    /** Package-visible for tests: inject a controllable clock. */
    LruCache(int capacity, Clock clock, EvictionListener<K, V> listener) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = listener;
        this.map = new LinkedHashMap<>(capacity, 0.75f, true);
    }

    /** Returns the value for {@code key}, or {@code null} if absent or expired. */
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Entry<V> entry = map.get(key);
            if (entry == null) {
                misses++;
                return null;
            }
            if (isExpired(entry)) {
                map.remove(key);
                evictions++;
                notifyListener(key, entry.value, EvictionReason.EXPIRED);
                misses++;
                return null;
            }
            hits++;
            return entry.value;
        } finally {
            lock.unlock();
        }
    }

    /** Stores {@code value} under {@code key} with no expiry. */
    public void put(K key, V value) {
        put(key, value, NO_EXPIRY);
    }

    /**
     * Stores {@code value} under {@code key}, expiring {@code ttlMillis}
     * milliseconds from now.
     *
     * @throws IllegalArgumentException if {@code ttlMillis} is negative
     */
    public void put(K key, V value, long ttlMillis) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (ttlMillis < 0) {
            throw new IllegalArgumentException("ttlMillis must be >= 0, was " + ttlMillis);
        }
        long expiresAt = ttlMillis == NO_EXPIRY
                ? NO_EXPIRY
                : clock.millis() + ttlMillis;
        lock.lock();
        try {
            map.put(key, new Entry<>(value, expiresAt));
            evictExcess();
        } finally {
            lock.unlock();
        }
    }

    /** Removes {@code key} and returns its value, or {@code null} if absent. */
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Entry<V> removed = map.remove(key);
            return removed == null ? null : removed.value;
        } finally {
            lock.unlock();
        }
    }

    /** True if a live (non-expired) entry exists for {@code key}. */
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            // Look the entry up without touching access order: a probe is
            // not a use, so it must not refresh recency.
            Entry<V> entry = null;
            for (Map.Entry<K, Entry<V>> e : map.entrySet()) {
                if (e.getKey().equals(key)) {
                    entry = e.getValue();
                    break;
                }
            }
            if (entry == null) {
                return false;
            }
            if (isExpired(entry)) {
                map.remove(key);
                evictions++;
                notifyListener(key, entry.value, EvictionReason.EXPIRED);
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Number of live entries. Expired entries are reaped first. */
    public int size() {
        lock.lock();
        try {
            reapExpired();
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /** Removes every entry. The listener is not notified for explicit clears. */
    public void clear() {
        lock.lock();
        try {
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Keys from least recently used to most recently used. */
    public List<K> keysLeastRecentlyUsedFirst() {
        lock.lock();
        try {
            reapExpired();
            return new ArrayList<>(map.keySet());
        } finally {
            lock.unlock();
        }
    }

    /** Lifetime hit/miss/eviction counters. */
    public CacheStats stats() {
        lock.lock();
        try {
            return new CacheStats(hits, misses, evictions);
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    /** Evicts least recently used entries until the cache fits its capacity. */
    private void evictExcess() {
        while (map.size() > capacity) {
            K eldest = map.keySet().iterator().next();
            Entry<V> entry = map.remove(eldest);
            evictions++;
            notifyListener(eldest, entry.value, EvictionReason.CAPACITY);
        }
    }

    /** Removes all expired entries; caller must hold the lock. */
    private void reapExpired() {
        map.entrySet().removeIf(e -> {
            if (isExpired(e.getValue())) {
                evictions++;
                notifyListener(e.getKey(), e.getValue().value, EvictionReason.EXPIRED);
                return true;
            }
            return false;
        });
    }

    private boolean isExpired(Entry<V> entry) {
        return clock.millis() >= entry.expiresAt;
    }

    private void notifyListener(K key, V value, EvictionReason reason) {
        if (listener != null) {
            listener.onEviction(key, value, reason);
        }
    }

    private record Entry<V>(V value, long expiresAt) {}
}
