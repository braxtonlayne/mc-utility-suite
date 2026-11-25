package com.utilitysuite.combat.tactics;

/**
 * Fixed ring buffer of an opponent's recent poses (feet position and the time it was recorded) for the afterimage
 * trail: each pose fades out linearly over {@link #LIFE_MS}. Primitive arrays only; pushing and reading allocate
 * nothing. Engine free; client thread.
 */
public final class Afterimages {

    public static final int CAPACITY = 10;
    public static final long LIFE_MS = 500L;

    private final double[] x = new double[CAPACITY];
    private final double[] y = new double[CAPACITY];
    private final double[] z = new double[CAPACITY];
    private final long[] t = new long[CAPACITY];
    private int head; // next write slot
    private int size;
    private int owner = -1;

    /** Records a pose. A different {@code entityId} than the last one clears the buffer first. */
    public void push(int entityId, double px, double py, double pz, long nowMs) {
        if (entityId != owner) {
            clear();
            owner = entityId;
        }
        x[head] = px;
        y[head] = py;
        z[head] = pz;
        t[head] = nowMs;
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    public void clear() {
        size = 0;
        head = 0;
        owner = -1;
    }

    public int owner() {
        return owner;
    }

    /** Poses stored (live or faded). */
    public int size() {
        return size;
    }

    /** Slot of the i-th pose, oldest first (0 .. size - 1). */
    private int slot(int i) {
        return (head - size + i + CAPACITY * 2) % CAPACITY;
    }

    public double x(int i) {
        return x[slot(i)];
    }

    public double y(int i) {
        return y[slot(i)];
    }

    public double z(int i) {
        return z[slot(i)];
    }

    public long time(int i) {
        return t[slot(i)];
    }

    /** Opacity of the i-th pose at {@code nowMs}: 1 when just recorded, 0 at {@link #LIFE_MS} and older. */
    public float alpha(int i, long nowMs) {
        long age = nowMs - time(i);
        if (age <= 0L) {
            return 1f;
        }
        if (age >= LIFE_MS) {
            return 0f;
        }
        return 1f - age / (float) LIFE_MS;
    }
}
