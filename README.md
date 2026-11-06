# LRU Cache — Java

A thread-safe, generic LRU (least recently used) cache in plain Java 17, with
optional per-entry TTL, eviction listeners, and hit/miss statistics. No
dependencies — just the JDK.

## The problem

Nearly every backend keeps a small hot set in memory: session tokens, rendered
fragments, DNS lookups, config values. An unbounded `HashMap` grows forever;
a hand-rolled cache usually gets one of three things wrong — eviction order,
thread safety, or stale entries that never expire. This cache gets all three
right in ~200 lines, using only the standard library.

## Design decisions

1. **Access-ordered `LinkedHashMap` as the store.** With access ordering on,
   iteration order is least-recently-used first, so finding the eviction
   victim is O(1) and no separate linked list has to be maintained by hand.
2. **One `ReentrantLock` instead of a read/write lock.** Even a `get()` mutates
   access order, so reads are writes here — a read/write lock would buy
   nothing and invite subtle races. A single lock keeps the invariant
   obvious: map + counters change atomically.
3. **Lazy TTL expiry.** Expired entries are reaped when touched (on `get`,
   `containsKey`, `size`, or key listing) instead of by a background sweeper
   thread. That keeps the cache allocation-free at idle and avoids a thread
   that outlives its owner. The trade-off is that an entry nobody ever touches
   again sits in memory until the next bulk read — acceptable for a bounded
   cache, where capacity eviction still caps memory.
4. **`containsKey` is a pure probe.** It looks the entry up without touching
   access order, so checking for a key never counts as using it. (The naive
   `map.get()` would silently refresh recency.)
5. **Eviction is explicit, not `removeEldestEntry`.** Managing eviction in
   `put()` means the listener fires exactly once per evicted entry and the
   reason (`CAPACITY` vs `EXPIRED`) is always known.

## Usage

```java
LruCache<String, String> cache = new LruCache<>(100,
        (key, value, reason) -> System.out.println("evicted " + key + ": " + reason));

cache.put("user:42", "{...}");          // no expiry
cache.put("token:7", "abc", 60_000);    // 60 s TTL

String value = cache.get("user:42");    // null on miss or expiry
cache.remove("user:42");
System.out.println(cache.keysLeastRecentlyUsedFirst());
System.out.println(cache.stats());      // hits, misses, evictions, hit rate
```

## Run

```bash
./build.sh
java -jar out/lru-cache.jar [capacity]   # default capacity 5
```

The jar starts a small REPL:

```
lru> put a alpha
lru> put b bravo
lru> get a
alpha
lru> demo        # scripted eviction scenario
lru> stats
hits=1 misses=0 evictions=0 hit-rate=100.0%
lru> exit
```

## Run the tests

```bash
./build.sh test
```

15 JUnit 5 tests cover eviction order, recency refresh on read and update,
capacity-1 behaviour, TTL expiry (with an injectable clock), the eviction
listener, statistics, argument validation, and a concurrent hammer test
(8 threads × 500 puts) asserting the size never exceeds capacity.

## Tech stack

- Java 17, standard library only (no dependencies)
- JUnit 5 (console standalone) for tests
- `javac` + `jar` build via `build.sh` — no Maven/Gradle required

## License

MIT — see [LICENSE](LICENSE).
