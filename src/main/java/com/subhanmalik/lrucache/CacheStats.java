package com.subhanmalik.lrucache;

/** Immutable snapshot of a cache's lifetime counters. */
public record CacheStats(long hits, long misses, long evictions) {

    /** Fraction of lookups that hit, in [0, 1]; 0 when there were no lookups. */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public long lookups() {
        return hits + misses;
    }
}
