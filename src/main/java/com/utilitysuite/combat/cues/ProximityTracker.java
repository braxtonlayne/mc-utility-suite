package com.utilitysuite.combat.cues;

/**
 * Engine-free "who is in range" set over entity ids, in fixed arrays (no allocation). {@link #observe} reports ids that
 * were not in range last sweep; {@link #sweep} forgets ids not observed since the previous sweep, so leaving and coming
 * back counts as new. Holds at most {@code capacity} ids; extra ids are ignored (never reported).
 */
public final class ProximityTracker {

    private final int[] ids;
    private final long[] seen;
    private int size;

    public ProximityTracker(int capacity) {
        ids = new int[capacity];
        seen = new long[capacity];
    }

    /** Marks {@code id} in range at {@code tick}; true when it was not in range before. */
    public boolean observe(int id, long tick) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == id) {
                seen[i] = tick;
                return false;
            }
        }
        if (size == ids.length) {
            return false;
        }
        ids[size] = id;
        seen[size] = tick;
        size++;
        return true;
    }

    /** Drops every id not observed at {@code tick}. */
    public void sweep(long tick) {
        int w = 0;
        for (int i = 0; i < size; i++) {
            if (seen[i] == tick) {
                ids[w] = ids[i];
                seen[w] = seen[i];
                w++;
            }
        }
        size = w;
    }

    public int size() {
        return size;
    }

    public void clear() {
        size = 0;
    }
}
