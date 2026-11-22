package com.utilitysuite.ui.holo;

/**
 * Frame time for every Shell animation. Sampled once per rendered frame by the first Shell layer that draws (calling
 * {@link #sample()} again in the same frame is harmless: it only shortens that step). Motion is frame-time driven,
 * never tick driven. Render thread only, allocation free, no Minecraft imports.
 */
public final class FrameClock {

    /** Longest step an animation ever sees, so a hitch never makes things jump. */
    public static final float MAX_DT = 0.05f;

    private static long lastNanos;
    private static float dt;
    private static long nowMs;
    private static int frame;

    private FrameClock() {
    }

    public static void sample() {
        sampleAt(System.nanoTime());
    }

    /** Test hook and the body of {@link #sample()}. */
    public static void sampleAt(long nanos) {
        if (lastNanos == 0L) {
            dt = 0f;
        } else {
            float d = (nanos - lastNanos) * 1.0e-9f;
            dt = d < 0f ? 0f : Math.min(d, MAX_DT);
        }
        lastNanos = nanos;
        nowMs = nanos / 1_000_000L;
        frame++;
    }

    /** Seconds since the previous sample, clamped to {@link #MAX_DT}. */
    public static float dtSeconds() {
        return dt;
    }

    public static long nowMs() {
        return nowMs;
    }

    /** Counts samples; animation entries use it to know when they were last touched. */
    public static int frame() {
        return frame;
    }

    public static void resetForTests() {
        lastNanos = 0L;
        dt = 0f;
        nowMs = 0L;
        frame = 0;
    }
}
