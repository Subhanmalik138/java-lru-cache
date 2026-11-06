package com.subhanmalik.lrucache;

import java.util.Scanner;

/**
 * Small interactive REPL for {@link LruCache}.
 *
 * <p>Usage: {@code java -jar lru-cache.jar [capacity]}
 *
 * <p>Commands:
 * <pre>
 *   put &lt;key&gt; &lt;value&gt; [ttlMillis]   store a value (optional TTL)
 *   get &lt;key&gt;                       fetch a value
 *   remove &lt;key&gt;                    delete an entry
 *   list                            keys, least recently used first
 *   stats                           hits, misses, evictions, hit rate
 *   demo                            run a scripted eviction scenario
 *   clear                           empty the cache
 *   help                            show this list
 *   exit                            quit
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int capacity = 5;
        if (args.length > 0) {
            try {
                capacity = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("capacity must be an integer, got: " + args[0]);
                System.exit(2);
            }
        }
        if (capacity < 1) {
            System.err.println("capacity must be >= 1");
            System.exit(2);
        }

        LruCache<String, String> cache = new LruCache<>(capacity,
                (key, value, reason) ->
                        System.out.printf("[evicted] %s -> %s (%s)%n", key, value, reason));

        System.out.printf("LRU cache started (capacity %d). Type 'help' for commands.%n", capacity);
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("lru> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            String command = parts[0].toLowerCase();
            try {
                switch (command) {
                    case "exit", "quit" -> {
                        System.out.println("Bye.");
                        return;
                    }
                    case "help" -> printHelp();
                    case "demo" -> runDemo(cache);
                    case "clear" -> {
                        cache.clear();
                        System.out.println("cache cleared");
                    }
                    case "list" -> System.out.println("keys (LRU -> MRU): "
                            + cache.keysLeastRecentlyUsedFirst());
                    case "stats" -> {
                        CacheStats s = cache.stats();
                        System.out.printf("hits=%d misses=%d evictions=%d hit-rate=%.1f%%%n",
                                s.hits(), s.misses(), s.evictions(), s.hitRate() * 100);
                    }
                    case "put" -> {
                        requireArgs(parts, 3, "put <key> <value> [ttlMillis]");
                        if (parts.length >= 4) {
                            cache.put(parts[1], parts[2], Long.parseLong(parts[3]));
                        } else {
                            cache.put(parts[1], parts[2]);
                        }
                        System.out.println("stored " + parts[1]);
                    }
                    case "get" -> {
                        requireArgs(parts, 2, "get <key>");
                        String value = cache.get(parts[1]);
                        System.out.println(value == null ? "(miss)" : value);
                    }
                    case "remove" -> {
                        requireArgs(parts, 2, "remove <key>");
                        System.out.println(cache.remove(parts[1]) == null
                                ? "(absent)" : "removed " + parts[1]);
                    }
                    default -> System.out.println("unknown command: " + command
                            + " (type 'help')");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("error: " + e.getMessage());
            }
        }
    }

    private static void requireArgs(String[] parts, int min, String usage) {
        if (parts.length < min) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static void printHelp() {
        System.out.println("""
                put <key> <value> [ttlMillis]   store a value (optional TTL)
                get <key>                       fetch a value
                remove <key>                    delete an entry
                list                            keys, least recently used first
                stats                           hits, misses, evictions, hit rate
                demo                            run a scripted eviction scenario
                clear                           empty the cache
                help                            show this list
                exit                            quit""");
    }

    /** Fills the cache past capacity so eviction is visible, then shows stats. */
    private static void runDemo(LruCache<String, String> cache) {
        System.out.println("--- demo: capacity " + cache.capacity() + " ---");
        cache.clear();
        cache.put("a", "alpha");
        cache.put("b", "bravo");
        cache.put("c", "charlie");
        System.out.println("put a, b, c");
        System.out.println("get a -> " + cache.get("a") + "   (a is now most recently used)");
        cache.put("d", "delta");
        cache.put("e", "echo");
        cache.put("f", "foxtrot");
        System.out.println("put d, e, f -> oldest unused entries were evicted above");
        System.out.println("keys (LRU -> MRU): " + cache.keysLeastRecentlyUsedFirst());
        CacheStats s = cache.stats();
        System.out.printf("stats: hits=%d misses=%d evictions=%d%n",
                s.hits(), s.misses(), s.evictions());
    }
}
