package com.subhanmalik.lrucache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.subhanmalik.lrucache.EvictionListener.EvictionReason;

class LruCacheTest {

    @Test
    void putAndGetReturnsValue() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("one", 1);
        assertEquals(1, cache.get("one"));
        assertEquals(1, cache.size());
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3"); // "a" is least recently used
        assertNull(cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    void getRefreshesRecency() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a"); // "a" is now most recently used
        cache.put("c", "3"); // evicts "b", not "a"
        assertEquals("1", cache.get("a"));
        assertNull(cache.get("b"));
    }

    @Test
    void updatingExistingKeyDoesNotEvictAndRefreshesRecency() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("a", "updated");
        assertEquals(2, cache.size());
        assertEquals("updated", cache.get("a"));
        cache.put("c", "3"); // evicts "b"
        assertNull(cache.get("b"));
        assertEquals("updated", cache.get("a"));
    }

    @Test
    void keysAreListedLeastRecentlyUsedFirst() {
        LruCache<String, String> cache = new LruCache<>(3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.get("a");
        assertEquals(List.of("b", "c", "a"), cache.keysLeastRecentlyUsedFirst());
    }

    @Test
    void removeDeletesEntry() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "1");
        assertEquals("1", cache.remove("a"));
        assertNull(cache.get("a"));
        assertEquals(0, cache.size());
        assertNull(cache.remove("missing"));
    }

    @Test
    void clearEmptiesCache() {
        LruCache<String, String> cache = new LruCache<>(3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    void capacityOneKeepsOnlyLatest() {
        LruCache<String, String> cache = new LruCache<>(1);
        cache.put("a", "1");
        cache.put("b", "2");
        assertNull(cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals(1, cache.size());
    }

    @Test
    void containsKeyDoesNotRefreshRecency() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "1");
        cache.put("b", "2");
        assertTrue(cache.containsKey("a")); // probe only
        cache.put("c", "3"); // must still evict "a"
        assertFalse(cache.containsKey("a"));
        assertTrue(cache.containsKey("b"));
    }

    @Test
    void evictionListenerNotifiedOnCapacityEviction() {
        List<String> evicted = new ArrayList<>();
        LruCache<String, String> cache = new LruCache<>(1,
                (k, v, reason) -> evicted.add(k + "=" + v + ":" + reason));
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(List.of("a=1:CAPACITY"), evicted);
    }

    @Test
    void statsTrackHitsMissesAndEvictions() {
        LruCache<String, String> cache = new LruCache<>(1);
        cache.put("a", "1");
        cache.get("a");       // hit
        cache.get("missing"); // miss
        cache.put("b", "2");  // evicts "a"
        CacheStats stats = cache.stats();
        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(1, stats.evictions());
        assertEquals(0.5, stats.hitRate(), 1e-9);
    }

    @Test
    void expiredEntryIsInvisibleAndReported() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        List<String> evicted = new ArrayList<>();
        LruCache<String, String> cache = new LruCache<>(3, clock,
                (k, v, reason) -> evicted.add(k + ":" + reason));
        cache.put("a", "1", 1_000); // 1 second TTL
        assertEquals("1", cache.get("a"));
        clock.advance(Duration.ofSeconds(2));
        assertNull(cache.get("a"));
        assertEquals(List.of("a:EXPIRED"), evicted);
        assertEquals(0, cache.size());
    }

    @Test
    void ttlEntrySurvivesBeforeExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LruCache<String, String> cache = new LruCache<>(3, clock, null);
        cache.put("a", "1", 10_000);
        clock.advance(Duration.ofSeconds(5));
        assertEquals("1", cache.get("a"));
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, String>(0));
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, String>(-3));
        LruCache<String, String> cache = new LruCache<>(2);
        assertThrows(NullPointerException.class, () -> cache.put(null, "1"));
        assertThrows(NullPointerException.class, () -> cache.put("a", null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(IllegalArgumentException.class, () -> cache.put("a", "1", -1));
    }

    @Test
    void concurrentPutsNeverExceedCapacity() throws InterruptedException {
        int capacity = 10;
        LruCache<Integer, Integer> cache = new LruCache<>(capacity);
        int threads = 8;
        int putsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t * putsPerThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        cache.put(base + i, i);
                        cache.get(base + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();
        assertTrue(cache.size() <= capacity,
                "size " + cache.size() + " exceeded capacity " + capacity);
    }

    /** A clock whose instant can be advanced by hand, for TTL tests. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
