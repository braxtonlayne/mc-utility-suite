package com.utilitysuite.world.waypoints;

import com.utilitysuite.world.BlockPosPacking;

/**
 * Ring buffer of packed block positions recorded behind the player. A sample is taken only when the block position
 * changed, and only stored when it is at least {@code minSpacing} blocks (Euclidean) from the last stored point.
 * Allocation-free per sample. Client thread.
 */
public final class BreadcrumbTrail {

    private long[] ring;
    private long[] ages;
    private int head;
    private int size;
    private long lastSampled = Long.MIN_VALUE;
    private long lastStored = Long.MIN_VALUE;
    private int minSpacingSq;
    private String dimension;

    public BreadcrumbTrail(int capacity, int minSpacing) {
        this.ring = new long[Math.max(2, capacity)];
        this.ages = new long[ring.length];
        setMinSpacing(minSpacing);
    }

    public void setMinSpacing(int minSpacing) {
        int s = Math.max(1, minSpacing);
        this.minSpacingSq = s * s;
    }

    /** Resizes the buffer, keeping the newest points. */
    public void setCapacity(int capacity) {
        int cap = Math.max(2, capacity);
        if (cap == ring.length) {
            return;
        }
        long[] pts = toArray();
        long[] ts = agesArray();
        ring = new long[cap];
        ages = new long[cap];
        head = 0;
        size = 0;
        int from = Math.max(0, pts.length - cap);
        for (int i = from; i < pts.length; i++) {
            push(pts[i], ts[i]);
        }
    }

    public int capacity() {
        return ring.length;
    }

    public int size() {
        return size;
    }

    public String dimension() {
        return dimension;
    }

    /**
     * Offers the player's current block position. Changing dimension clears the trail.
     *
     * @return true when a point was stored
     */
    public boolean sample(String dim, int x, int y, int z, long gameTime) {
        if (dimension == null || !dimension.equals(dim)) {
            clear();
            dimension = dim;
        }
        long p = BlockPosPacking.pack(x, y, z);
        if (p == lastSampled) {
            return false;
        }
        lastSampled = p;
        if (lastStored != Long.MIN_VALUE && BlockPosPacking.distSq(p, lastStored) < minSpacingSq) {
            return false;
        }
        push(p, gameTime);
        lastStored = p;
        return true;
    }

    private void push(long p, long time) {
        ring[head] = p;
        ages[head] = time;
        head = (head + 1) % ring.length;
        if (size < ring.length) {
            size++;
        }
    }

    /** i-th point, 0 = oldest. */
    public long get(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
        int start = (head - size + ring.length) % ring.length;
        return ring[(start + i) % ring.length];
    }

    /** Game time when the i-th point (0 = oldest) was stored, for fading. */
    public long timeOf(int i) {
        int start = (head - size + ring.length) % ring.length;
        return ages[(start + i) % ring.length];
    }

    public long newest() {
        return size == 0 ? Long.MIN_VALUE : get(size - 1);
    }

    /** Oldest first. */
    public long[] toArray() {
        long[] out = new long[size];
        for (int i = 0; i < size; i++) {
            out[i] = get(i);
        }
        return out;
    }

    private long[] agesArray() {
        long[] out = new long[size];
        for (int i = 0; i < size; i++) {
            out[i] = timeOf(i);
        }
        return out;
    }

    /** Newest first: the way back. */
    public long[] reversed() {
        long[] out = new long[size];
        for (int i = 0; i < size; i++) {
            out[i] = get(size - 1 - i);
        }
        return out;
    }

    public void clear() {
        head = 0;
        size = 0;
        lastSampled = Long.MIN_VALUE;
        lastStored = Long.MIN_VALUE;
    }
}
