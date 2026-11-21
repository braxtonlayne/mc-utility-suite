package com.utilitysuite.combat.hud;

/**
 * Totem pops per entity id in fixed primitive arrays (entity event 35). Capacity-bounded; the oldest slot is reused
 * when full. Pure and allocation free.
 */
public final class PopCounter {

    public static final int CAPACITY = 32;

    private final int[] ids = new int[CAPACITY];
    private final int[] counts = new int[CAPACITY];
    private int size;
    private int next;

    public void pop(int entityId) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == entityId) {
                counts[i]++;
                return;
            }
        }
        int slot;
        if (size < CAPACITY) {
            slot = size++;
        } else {
            slot = next;
            next = (next + 1) % CAPACITY;
        }
        ids[slot] = entityId;
        counts[slot] = 1;
    }

    public int count(int entityId) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == entityId) {
                return counts[i];
            }
        }
        return 0;
    }

    public void forget(int entityId) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == entityId) {
                size--;
                ids[i] = ids[size];
                counts[i] = counts[size];
                if (next > size) {
                    next = 0;
                }
                return;
            }
        }
    }

    public void clear() {
        size = 0;
        next = 0;
    }
}
