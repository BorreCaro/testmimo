package com.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementación genérica y concurrente de una caché LRU (Least Recently Used).
 *
 * <p>Utiliza composición con un {@link ConcurrentHashMap} para acceso O(1) por llave
 * y una lista doblemente enlazada personalizada para mantener el orden de desalojo.
 * La seguridad entre hilos se garantiza exclusivamente mediante
 * {@link ReentrantReadWriteLock}, optimizando para múltiples lectores simultáneos.</p>
 *
 * @param <K> tipo de las llaves (no nulas)
 * @param <V> tipo de los valores (no nulos)
 */
public final class LruCache<K, V> implements Cache<K, V> {

    // ──────────────────────────────────────────────────────────────────────
    //  Nodo de la lista doblemente enlazada
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Nodo privado y estático de la lista doblemente enlazada.
     * Cada nodo almacena la llave, el valor y referencias a sus vecinos.
     *
     * @param <K> tipo de la llave
     * @param <V> tipo del valor
     */
    private static final class CacheNode<K, V> {
        final K key;
        V value;
        CacheNode<K, V> prev;
        CacheNode<K, V> next;

        CacheNode(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Estado interno (completamente encapsulado)
    // ──────────────────────────────────────────────────────────────────────

    /** Capacidad máxima de la caché. */
    private final int capacity;

    /** Índice O(1) de llaves a nodos. */
    private final ConcurrentHashMap<K, CacheNode<K, V>> map;

    /**
     * Centinela ficticio de la cabeza.
     * {@code head.next} es siempre el nodo más recientemente usado.
     */
    private final CacheNode<K, V> head;

    /**
     * Centinela ficticio de la cola.
     * {@code tail.prev} es siempre el nodo menos recientemente usado (candidato a desalojo).
     */
    private final CacheNode<K, V> tail;

    /** Lock de lectura/escritura para concurrencia. */
    private final ReentrantReadWriteLock rwLock;

    /** Lista inmutable de listeners registrados. */
    private final List<EvictionListener<K, V>> listeners;

    // ──────────────────────────────────────────────────────────────────────
    //  Constructor
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Crea una nueva caché LRU con la capacidad y los listeners dados.
     *
     * @param capacity capacidad máxima (debe ser &gt; 0)
     * @param listeners listeners que serán notificados de forma síncrona
     *                   cada vez que un elemento sea desalojado
     * @throws IllegalArgumentException si capacity &le; 0
     */
    @SafeVarargs
    public LruCache(int capacity, EvictionListener<K, V>... listeners) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("La capacidad debe ser mayor a 0, se recibió: " + capacity);
        }

        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);
        this.rwLock = new ReentrantReadWriteLock();

        // Centinelas para simplificar la manipulación de la lista enlazada.
        this.head = new CacheNode<>(null, null);
        this.tail = new CacheNode<>(null, null);
        head.next = tail;
        tail.prev = head;

        // Copia defensiva inmutable de los listeners.
        List<EvictionListener<K, V>> copy = new ArrayList<>();
        for (EvictionListener<K, V> listener : listeners) {
            if (listener != null) {
                copy.add(listener);
            }
        }
        this.listeners = Collections.unmodifiableList(copy);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Operaciones de la lista enlazada (privadas, sin sincronización propia)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Inserta un nodo justo después del centinela de cabeza (posición MRU).
     * <p>Precondición: el lock de escritura está adquirido.</p>
     */
    private void linkAfterHead(CacheNode<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /**
     * Desenlaza un nodo de su posición actual en la lista.
     * <p>Precondición: el lock de escritura está adquirido.</p>
     */
    private void unlink(CacheNode<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    /**
     * Mueve un nodo existente a la posición MRU (justo después de la cabeza).
     * <p>Precondición: el lock de escritura está adquirido.</p>
     */
    private void moveToHead(CacheNode<K, V> node) {
        unlink(node);
        linkAfterHead(node);
    }

    /**
     * Extrae y desenlaza el nodo menos recientemente usado (justo antes de la cola).
     *
     * @return el nodo desalojado
     * <p>Precondición: el lock de escritura está adquirido y la lista no está vacía.</p>
     */
    private CacheNode<K, V> evictTail() {
        CacheNode<K, V> victim = tail.prev;
        unlink(victim);
        return victim;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Notificación a listeners
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Notifica de forma síncrona a todos los listeners registrados.
     * Se invoca dentro del lock de escritura para garantizar consistencia.
     */
    private void notifyEviction(K key, V value) {
        for (EvictionListener<K, V> listener : listeners) {
            listener.onEviction(key, value);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Implementación de Cache<K, V>
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "La llave no puede ser nula");
        Objects.requireNonNull(value, "El valor no puede ser nulo");

        rwLock.writeLock().lock();
        try {
            CacheNode<K, V> existing = map.get(key);

            if (existing != null) {
                // Actualizar valor y mover a MRU.
                existing.value = value;
                moveToHead(existing);
            } else {
                // Crear nodo, insertar en mapa y en cabeza de la lista.
                CacheNode<K, V> newNode = new CacheNode<>(key, value);
                map.put(key, newNode);
                linkAfterHead(newNode);

                // Si se excede la capacidad, desalojar el LRU.
                if (map.size() > capacity) {
                    CacheNode<K, V> evicted = evictTail();
                    map.remove(evicted.key);
                    notifyEviction(evicted.key, evicted.value);
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "La llave no puede ser nula");

        // Fase 1: lectura rápida bajo read lock.
        // Si la llave no existe, retornamos inmediatamente sin adquirir write lock.
        rwLock.readLock().lock();
        try {
            CacheNode<K, V> node = map.get(key);
            if (node == null) {
                return Optional.empty();
            }
        } finally {
            rwLock.readLock().unlock();
        }

        // Fase 2: la llave existe — necesitamos write lock para promover el nodo a MRU.
        rwLock.writeLock().lock();
        try {
            // Doble verificación: el nodo pudo haber sido desalojado entre locks.
            CacheNode<K, V> node = map.get(key);
            if (node == null) {
                return Optional.empty();
            }
            moveToHead(node);
            return Optional.of(node.value);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        rwLock.readLock().lock();
        try {
            return map.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Representación legible del estado de la caché (útil para depuración).
     */
    @Override
    public String toString() {
        rwLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder("LruCache{size=").append(map.size());
            sb.append(", capacity=").append(capacity).append(", order=[");
            CacheNode<K, V> current = head.next;
            boolean first = true;
            while (current != tail) {
                if (!first) sb.append(", ");
                sb.append(current.key).append("=").append(current.value);
                first = false;
                current = current.next;
            }
            sb.append("]}");
            return sb.toString();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
