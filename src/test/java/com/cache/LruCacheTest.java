package com.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LruCache")
class LruCacheTest {

    // ──────────────────────────────────────────────────────────────────
    //  Contrato de la interfaz y validación de argumentos
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validación de argumentos")
    class Validation {

        @Test
        @DisplayName("rechaza capacidad <= 0")
        void rejectsNonPositiveCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new LruCache<>(0));
            assertThrows(IllegalArgumentException.class, () -> new LruCache<>(-1));
        }

        @Test
        @DisplayName("rechaza llave nula en put")
        void rejectsNullKeyOnPut() {
            Cache<String, String> cache = new LruCache<>(5);
            assertThrows(IllegalArgumentException.class, () -> cache.put(null, "v"));
        }

        @Test
        @DisplayName("rechaza valor nulo en put")
        void rejectsNullValueOnPut() {
            Cache<String, String> cache = new LruCache<>(5);
            assertThrows(IllegalArgumentException.class, () -> cache.put("k", null));
        }

        @Test
        @DisplayName("rechaza llave nula en get")
        void rejectsNullKeyOnGet() {
            Cache<String, String> cache = new LruCache<>(5);
            assertThrows(IllegalArgumentException.class, () -> cache.get(null));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Comportamiento básico LRU
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("comportamiento básico")
    class BasicBehaviour {

        @Test
        @DisplayName("get de llave inexistente retorna Optional vacío")
        void getMissingKeyReturnsEmpty() {
            Cache<String, Integer> cache = new LruCache<>(3);
            assertEquals(Optional.empty(), cache.get("missing"));
        }

        @Test
        @DisplayName("put + get recupera el valor correcto")
        void putAndGetRoundTrip() {
            Cache<String, Integer> cache = new LruCache<>(3);
            cache.put("a", 1);
            assertEquals(Optional.of(1), cache.get("a"));
        }

        @Test
        @DisplayName("put sobre llave existente actualiza el valor")
        void putOverwritesExistingValue() {
            Cache<String, Integer> cache = new LruCache<>(3);
            cache.put("a", 1);
            cache.put("a", 99);
            assertEquals(Optional.of(99), cache.get("a"));
            assertEquals(1, cache.size());
        }

        @Test
        @DisplayName("size refleja el número correcto de elementos")
        void sizeIsAccurate() {
            Cache<Integer, String> cache = new LruCache<>(5);
            assertEquals(0, cache.size());
            cache.put(1, "a");
            assertEquals(1, cache.size());
            cache.put(2, "b");
            cache.put(3, "c");
            assertEquals(3, cache.size());
        }

        @Test
        @DisplayName("desaloja el menos recientemente usado al llenarse")
        void evictsLeastRecentlyUsed() {
            Cache<Integer, String> cache = new LruCache<>(2);

            cache.put(1, "a");
            cache.put(2, "b");
            cache.put(3, "c"); // desaloja la llave 1

            assertEquals(Optional.empty(), cache.get(1));
            assertEquals(Optional.of("b"), cache.get(2));
            assertEquals(Optional.of("c"), cache.get(3));
            assertEquals(2, cache.size());
        }

        @Test
        @DisplayName("get promueve el nodo y cambia el candidato de desalojo")
        void getPromotesNode() {
            Cache<Integer, String> cache = new LruCache<>(2);

            cache.put(1, "a");
            cache.put(2, "b");
            cache.get(1);        // promueve llave 1 → ahora 2 es LRU
            cache.put(3, "c");   // desaloja llave 2

            assertEquals(Optional.of("a"), cache.get(1));
            assertEquals(Optional.empty(), cache.get(2));
            assertEquals(Optional.of("c"), cache.get(3));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Patrón Observer — EvictionListener
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EvictionListener")
    class ListenerTests {

        @Test
        @DisplayName("notifica al listener cuando se desaloja un elemento")
        void singleListenerIsNotified() {
            List<String> evicted = new ArrayList<>();

            Cache<Integer, String> cache = new LruCache<>(2,
                    (key, value) -> evicted.add(key + ":" + value));

            cache.put(1, "a");
            cache.put(2, "b");
            cache.put(3, "c"); // desaloja 1

            assertEquals(1, evicted.size());
            assertEquals("1:a", evicted.get(0));
        }

        @Test
        @DisplayName("notifica a múltiples listeners en orden")
        void multipleListenersAreNotified() {
            List<Integer> firstLog = new ArrayList<>();
            List<Integer> secondLog = new ArrayList<>();

            Cache<Integer, String> cache = new LruCache<>(1,
                    (k, v) -> firstLog.add(k),
                    (k, v) -> secondLog.add(k));

            cache.put(1, "a");
            cache.put(2, "b"); // desaloja 1

            assertEquals(List.of(1), firstLog);
            assertEquals(List.of(1), secondLog);
        }

        @Test
        @DisplayName("no notifica al actualizar un valor existente")
        void noNotificationOnUpdate() {
            AtomicInteger count = new AtomicInteger();

            Cache<String, Integer> cache = new LruCache<>(3,
                    (k, v) -> count.incrementAndGet());

            cache.put("x", 1);
            cache.put("x", 2); // actualización, no desalojo

            assertEquals(0, count.get());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Concurrencia
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("concurrencia")
    class ConcurrencyTests {

        @Test
        @DisplayName("operaciones concurrentes no corrompen el estado")
        void concurrentPutsAndGets() throws InterruptedException {
            final int threads = 16;
            final int opsPerThread = 5_000;
            final int capacity = 128;

            Cache<Integer, Integer> cache = new LruCache<>(capacity);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int offset = t * opsPerThread;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            int key = (offset + i) % (capacity * 4);
                            cache.put(key, key * 10);
                            cache.get(key);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "Timeout alcanzado");
            assertTrue(cache.size() <= capacity,
                    "size=" + cache.size() + " excede capacity=" + capacity);

            pool.shutdownNow();
        }

        @Test
        @DisplayName("lectores simultáneos no bloquean entre sí")
        void concurrentReadersDoNotBlock() throws InterruptedException {
            Cache<Integer, Integer> cache = new LruCache<>(100);
            for (int i = 0; i < 100; i++) {
                cache.put(i, i);
            }

            int readers = 20;
            ExecutorService pool = Executors.newFixedThreadPool(readers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(readers);

            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 100; i++) {
                            cache.get(i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "Timeout en lectores concurrentes");

            pool.shutdownNow();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Capacidad 1 (caso borde)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("casos borde")
    class EdgeCases {

        @Test
        @DisplayName("capacidad 1: reemplaza el único elemento")
        void capacityOne() {
            List<String> evicted = new ArrayList<>();
            Cache<String, String> cache = new LruCache<>(1,
                    (k, v) -> evicted.add(k));

            cache.put("a", "1");
            cache.put("b", "2"); // desaloja "a"

            assertEquals(Optional.empty(), cache.get("a"));
            assertEquals(Optional.of("2"), cache.get("b"));
            assertEquals(1, cache.size());
            assertEquals(List.of("a"), evicted);
        }

        @Test
        @DisplayName("toString refleja el orden LRU")
        void toStringReflectsOrder() {
            Cache<Integer, String> cache = new LruCache<>(3);
            cache.put(1, "a");
            cache.put(2, "b");
            cache.put(3, "c");

            String repr = cache.toString();
            assertTrue(repr.contains("size=3"));
            assertTrue(repr.contains("capacity=3"));
        }
    }
}
