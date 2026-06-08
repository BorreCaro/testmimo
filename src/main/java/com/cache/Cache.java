package com.cache;

import java.util.Optional;

/**
 * Interfaz genérica que define las operaciones básicas de una caché.
 *
 * @param <K> tipo de las llaves
 * @param <V> tipo de los valores
 */
public interface Cache<K, V> {

    /**
     * Asocia el valor especificado con la llave especificada.
     * Si la caché ya contiene un valor para la llave, el valor anterior
     * es reemplazado.
     *
     * @param key   llave con la que se asocia el valor
     * @param value valor a asociar con la llave
     * @throws IllegalArgumentException si key o value son nulos
     */
    void put(K key, V value);

    /**
     * Retorna el valor asociado con la llave especificada, si está presente.
     * Si la llave existe, el elemento se marca como recientemente utilizado.
     *
     * @param key llave cuyo valor asociado se desea obtener
     * @return un {@link Optional} con el valor, o vacío si no está presente
     * @throws IllegalArgumentException si key es nula
     */
    Optional<V> get(K key);

    /**
     * Retorna el número actual de elementos en la caché.
     *
     * @return cantidad de elementos
     */
    int size();
}
