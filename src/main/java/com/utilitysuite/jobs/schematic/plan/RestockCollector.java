package com.utilitysuite.jobs.schematic.plan;

/**
 * Collects the next few materials a build has run out of, in plan order: the first {@link #capacity()} distinct keys
 * (palette indices, or any int the caller uses for scaffolding) seen by {@link #add}, each with the number of
 * remaining placements that need it. Later keys are ignored once full; known keys keep counting.
 * <p>
 * Engine-agnostic and allocation-free after construction (client thread only).
 */
public final class RestockCollector {

    /** Default number of item types per restock round. */
    public static final int DEFAULT_CAPACITY = 5;

    private final int[] keys;
    private final int[] counts;
    private int size;

    public RestockCollector() {
        this(DEFAULT_CAPACITY);
    }

    public RestockCollector(int capacity) {
        keys = new int[capacity];
        counts = new int[capacity];
    }

    public void reset() {
        size = 0;
    }

    /** Counts one more placement that needs {@code key}; false when the key is new and the collector is full. */
    public boolean add(int key) {
        for (int i = 0; i < size; i++) {
            if (keys[i] == key) {
                counts[i]++;
                return true;
            }
        }
        if (size == keys.length) {
            return false;
        }
        keys[size] = key;
        counts[size] = 1;
        size++;
        return true;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return keys.length;
    }

    public int key(int i) {
        return keys[i];
    }

    public int count(int i) {
        return counts[i];
    }
}
