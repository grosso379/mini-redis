package com.miniredis.store;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class KVStoreConcurrencyTest {

    private static final int THREADS = 16;
    private static final int OPS_PER_THREAD = 10_000;

    /**
     * Each thread owns a disjoint key range and is the only writer for those keys.
     * After the run we replay each thread's operation log to compute the expected
     * final state, and assert the store matches byte-for-byte. This proves no
     * lost updates and no torn writes within the single-writer-per-key regime.
     */
    @RepeatedTest(5)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void disjointKeysNoLostUpdates() throws Exception {
        KVStore store = new KVStore();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Each thread records the final value (or DELETED) for each of its keys.
        @SuppressWarnings("unchecked")
        Map<String, byte[]>[] expectedPerThread = new Map[THREADS];
        @SuppressWarnings("unchecked")
        Set<String>[] deletedPerThread = new Set[THREADS];
        for (int i = 0; i < THREADS; i++) {
            expectedPerThread[i] = new HashMap<>();
            deletedPerThread[i] = new HashSet<>();
        }

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            final Map<String, byte[]> expected = expectedPerThread[t];
            final Set<String> deleted = deletedPerThread[t];
            pool.submit(() -> {
                try {
                    start.await();
                    java.util.Random rng = new java.util.Random(threadId * 31L + 7);
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        // Keep the key range narrow per thread so puts and deletes
                        // actually contend with each other on the same keys.
                        String key = "t" + threadId + "-k" + (i % 64);
                        int op = rng.nextInt(10);
                        if (op < 7) {
                            byte[] value = ("t" + threadId + "-i" + i).getBytes(StandardCharsets.UTF_8);
                            store.put(key, value);
                            expected.put(key, value);
                            deleted.remove(key);
                        } else if (op < 9) {
                            store.get(key);
                        } else {
                            store.delete(key);
                            expected.remove(key);
                            deleted.add(key);
                        }
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(25, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "executor did not terminate");

        if (failure.get() != null) {
            fail("worker threw: " + failure.get(), failure.get());
        }

        for (int t = 0; t < THREADS; t++) {
            for (Map.Entry<String, byte[]> e : expectedPerThread[t].entrySet()) {
                byte[] actual = store.get(e.getKey());
                assertNotNull(actual, "missing key " + e.getKey());
                assertEquals(
                        new String(e.getValue(), StandardCharsets.UTF_8),
                        new String(actual, StandardCharsets.UTF_8),
                        "value mismatch for key " + e.getKey());
            }
            for (String key : deletedPerThread[t]) {
                assertNull(store.get(key), "deleted key still present: " + key);
            }
        }
    }

    /**
     * All threads hammer a small set of shared keys with mixed put/get/delete.
     * We can't predict the final state, but we can assert that each key's final
     * value is either absent or equal to a value some thread actually wrote, and
     * that no exception escaped CHM's atomic operations.
     */
    @RepeatedTest(5)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void sharedKeysNoCorruption() throws Exception {
        String[] sharedKeys = {"shared-a", "shared-b", "shared-c", "shared-d"};
        KVStore store = new KVStore();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Track every value any thread ever wrote, so we can verify the final
        // state of each key is one of those values (or absent).
        Set<String> allWrittenValues = java.util.concurrent.ConcurrentHashMap.newKeySet();

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    java.util.Random rng = new java.util.Random(threadId * 17L + 3);
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        String key = sharedKeys[rng.nextInt(sharedKeys.length)];
                        int op = rng.nextInt(10);
                        if (op < 6) {
                            String v = "t" + threadId + "-i" + i;
                            allWrittenValues.add(v);
                            store.put(key, v.getBytes(StandardCharsets.UTF_8));
                        } else if (op < 9) {
                            store.get(key);
                        } else {
                            store.delete(key);
                        }
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(25, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "executor did not terminate");

        if (failure.get() != null) {
            fail("worker threw: " + failure.get(), failure.get());
        }

        for (String key : sharedKeys) {
            byte[] actual = store.get(key);
            if (actual != null) {
                String value = new String(actual, StandardCharsets.UTF_8);
                assertTrue(allWrittenValues.contains(value),
                        "final value for " + key + " was never written by any thread: " + value);
            }
        }
    }
}
