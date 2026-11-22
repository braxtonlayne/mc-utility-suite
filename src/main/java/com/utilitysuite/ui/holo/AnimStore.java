package com.utilitysuite.ui.holo;

/**
 * Animated floats keyed by a stable 64-bit widget key, in fixed primitive arrays (open addressing). The state
 * survives rebuilds of the widget tree because it lives here and not in the widgets. Two curves only: {@link #settle}
 * (critically damped follow) and {@link #progress} shaped by {@link #easeOutBack}. With {@link Motion#OFF} every
 * call returns its target at once. Entries not touched for {@link #IDLE_MS} are reclaimed in place.
 * Render thread only; no allocation after construction.
 */
public final class AnimStore {

    public static final float TAU_FAST = 0.045f;
    public static final float TAU_NORMAL = 0.080f;
    public static final float TAU_SLOW = 0.140f;
    public static final long IDLE_MS = 2000L;

    /** The store every Shell layer shares. */
    public static final AnimStore SHARED = new AnimStore(1024);

    private final int mask;
    private long[] keys;
    private float[] values;
    private long[] touched;
    private long[] spareKeys;
    private float[] spareValues;
    private long[] spareTouched;
    private int size;

    /** @param capacity a power of two; the store holds up to half of it */
    public AnimStore(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        mask = capacity - 1;
        keys = new long[capacity];
        values = new float[capacity];
        touched = new long[capacity];
        spareKeys = new long[capacity];
        spareValues = new float[capacity];
        spareTouched = new long[capacity];
    }

    /** A key from a constant or cached string and a small index (a row, a segment). */
    public static long key(String id, int index) {
        return mix(id.hashCode() * 0x9E3779B97F4A7C15L + index);
    }

    public static long key(long a, long b) {
        return mix(a * 0x9E3779B97F4A7C15L + b);
    }

    private static long mix(long h) {
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return h == 0L ? 1L : h; // 0 marks an empty cell
    }

    /**
     * Moves the value of {@code key} toward {@code target} with time constant {@code tauSeconds} and returns it. A
     * key seen for the first time starts at {@code initial}.
     */
    public float settle(long key, float target, float tauSeconds, float initial) {
        if (HoloTheme.motion == Motion.OFF || tauSeconds <= 0f) {
            return target;
        }
        int i = slot(key, initial);
        if (i < 0) {
            return target;
        }
        float v = values[i];
        float d = target - v;
        if (d > -0.001f && d < 0.001f) {
            v = target;
        } else {
            v += d * (1f - (float) Math.exp(-FrameClock.dtSeconds() / tauSeconds));
        }
        values[i] = v;
        return v;
    }

    /** {@link #settle} that starts at the target (no animation the first time the key is seen). */
    public float settle(long key, float target, float tauSeconds) {
        return settle(key, target, tauSeconds, target);
    }

    /**
     * Linear progress 0..1 of {@code key} moving toward 1 while {@code on} and toward 0 otherwise, over
     * {@code durationSeconds}. A new key starts at the end state for {@code on}. Shape it with {@link #easeOutBack}.
     */
    public float progress(long key, boolean on, float durationSeconds) {
        float target = on ? 1f : 0f;
        if (HoloTheme.motion == Motion.OFF || durationSeconds <= 0f) {
            return target;
        }
        int i = slot(key, target);
        if (i < 0) {
            return target;
        }
        float step = FrameClock.dtSeconds() / durationSeconds;
        float v = values[i];
        v = on ? Math.min(1f, v + step) : Math.max(0f, v - step);
        values[i] = v;
        return v;
    }

    /** Sets the value of a key (to start an enter transition from a chosen state). */
    public void set(long key, float value) {
        int i = slot(key, value);
        if (i >= 0) {
            values[i] = value;
        }
    }

    /** The stored value, or {@code fallback} when the key is unknown. Does not count as a touch. */
    public float peek(long key, float fallback) {
        int i = (int) key & mask;
        for (int n = 0; n <= mask; n++) {
            long k = keys[i];
            if (k == key) {
                return values[i];
            }
            if (k == 0L) {
                break;
            }
            i = (i + 1) & mask;
        }
        return fallback;
    }

    public int size() {
        return size;
    }

    /** Ease-out-back with about 8 % overshoot, for brackets locking on and toggles landing. */
    public static float easeOutBack(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        float c = 1.2f;
        float u = t - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    public static float easeOut(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private int slot(long key, float initial) {
        long now = FrameClock.nowMs();
        int i = (int) key & mask;
        for (int n = 0; n <= mask; n++) {
            long k = keys[i];
            if (k == key) {
                touched[i] = now;
                return i;
            }
            if (k == 0L) {
                if (size * 2 > mask) {
                    reclaim(now);
                    if (size * 2 > mask) {
                        return -1; // full of live entries: the caller gets its target, unanimated
                    }
                    return slot(key, initial);
                }
                keys[i] = key;
                values[i] = initial;
                touched[i] = now;
                size++;
                return i;
            }
            i = (i + 1) & mask;
        }
        return -1;
    }

    /** Rebuilds the table without the idle entries, into the spare arrays (swapped, nothing allocated). */
    private void reclaim(long now) {
        java.util.Arrays.fill(spareKeys, 0L);
        int kept = 0;
        for (int i = 0; i <= mask; i++) {
            long k = keys[i];
            if (k == 0L || now - touched[i] >= IDLE_MS) {
                continue;
            }
            int j = (int) k & mask;
            while (spareKeys[j] != 0L) {
                j = (j + 1) & mask;
            }
            spareKeys[j] = k;
            spareValues[j] = values[i];
            spareTouched[j] = touched[i];
            kept++;
        }
        long[] tk = keys;
        keys = spareKeys;
        spareKeys = tk;
        float[] tv = values;
        values = spareValues;
        spareValues = tv;
        long[] tt = touched;
        touched = spareTouched;
        spareTouched = tt;
        size = kept;
    }
}
