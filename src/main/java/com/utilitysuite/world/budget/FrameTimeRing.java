package com.utilitysuite.world.budget;

/**
 * Preallocated ring of frame times (nanoseconds) with their end timestamps. {@link #onePercentLowFps} reports the
 * "1% low": the FPS equivalent of the mean of the slowest 1% of frames inside a recent time window. Engine-free and
 * allocation-free after construction (the worst-k selection uses a preallocated scratch array).
 */
public final class FrameTimeRing {

    private final long[] frameNanos;
    private final long[] endNanos;
    private final long[] worst;
    private int head;
    private int size;

    /** @param capacity frames kept (e.g. 1024 covers ~2 s up to 500 FPS) */
    public FrameTimeRing(int capacity) {
        frameNanos = new long[capacity];
        endNanos = new long[capacity];
        worst = new long[Math.max(1, (capacity + 99) / 100)];
    }

    public void add(long endNanosStamp, long frameTime) {
        frameNanos[head] = frameTime;
        endNanos[head] = endNanosStamp;
        head = head + 1 == frameNanos.length ? 0 : head + 1;
        if (size < frameNanos.length) {
            size++;
        }
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    public int size() {
        return size;
    }

    /** Frames that ended within {@code windowNanos} of {@code now}. */
    public int framesInWindow(long now, long windowNanos) {
        int n = 0;
        for (int i = 0; i < size; i++) {
            int idx = Math.floorMod(head - 1 - i, frameNanos.length);
            if (now - endNanos[idx] > windowNanos) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * 1% low FPS over the window ending at {@code now}, or NaN when fewer than {@code minFrames} frames are in it.
     * At least one frame is always counted as "the worst 1%".
     */
    public double onePercentLowFps(long now, long windowNanos, int minFrames) {
        int n = framesInWindow(now, windowNanos);
        if (n < Math.max(1, minFrames)) {
            return Double.NaN;
        }
        int k = Math.min(worst.length, Math.max(1, n / 100));
        int filled = 0;
        for (int i = 0; i < n; i++) {
            long v = frameNanos[Math.floorMod(head - 1 - i, frameNanos.length)];
            if (filled < k) {
                worst[filled++] = v;
            } else {
                int minAt = 0;
                for (int j = 1; j < k; j++) {
                    if (worst[j] < worst[minAt]) {
                        minAt = j;
                    }
                }
                if (v > worst[minAt]) {
                    worst[minAt] = v;
                }
            }
        }
        long sum = 0;
        for (int j = 0; j < filled; j++) {
            sum += worst[j];
        }
        double meanNanos = sum / (double) filled;
        return meanNanos <= 0 ? Double.POSITIVE_INFINITY : 1.0e9 / meanNanos;
    }
}
