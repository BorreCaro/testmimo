package com.cache;

/**
 * Interfaz funcional para ser notificado cuando un elemento
 * es desalojado de la caché por exceder la capacidad máxima.
 *
 * @param <K> tipo de las llaves
 * @param <V> tipo de los valores
 */
@FunctionalInterface
public interface EvictionListener<K, V> {

    /**
     * Invocado de forma síncrona cuando un elemento es eliminado de la caché.
     *
     * @param key   llave del elemento desalojado
     * @param value valor del elemento desalojado
     */
    void onEviction(K key, V value);
}
