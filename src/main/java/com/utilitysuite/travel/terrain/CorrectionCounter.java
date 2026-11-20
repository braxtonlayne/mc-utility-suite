package com.utilitysuite.travel.terrain;

/**
 * Capability check for surface traversal: counts server position corrections while the mode is active. When
 * {@code threshold} corrections arrive within {@code windowTicks}, the server is evidently rejecting the movement and
 * the mode must be switched off instead of fighting it. Corrections farther than {@code teleportDistance} are treated
 * as teleports (commands, respawn, portals) and ignored. Fixed-size ring buffer, no allocation after construction.
 */
public final class CorrectionCounter {

    public static final int MAX_THRESHOLD = 64;

    private final long[] ring = new long[MAX_THRESHOLD];
    private int head;
    private int size;
    private int threshold = 3;
    private int windowTicks = 100;
    private double teleportDistance = 8.0;

    public void configure(int threshold, int windowTicks, double teleportDistance) {
        this.threshold = Math.max(1, Math.min(MAX_THRESHOLD, threshold));
        this.windowTicks = Math.max(1, windowTicks);
        this.teleportDistance = teleportDistance;
        reset();
    }

    /**
     * Records a correction of the given size at {@code tick}. Returns true when the count inside the window reached the
     * threshold, i.e. the mode should be disabled now.
     */
    public boolean record(long tick, double distance) {
        if (distance > teleportDistance) {
            return false;
        }
        ring[head] = tick;
        head = (head + 1) % MAX_THRESHOLD;
        if (size < MAX_THRESHOLD) {
            size++;
        }
        return count(tick) >= threshold;
    }

    /** Corrections recorded within the window ending at {@code now}. */
    public int count(long now) {
        int n = 0;
        long oldest = now - windowTicks;
        for (int i = 0; i < size; i++) {
            int idx = (head - 1 - i + MAX_THRESHOLD) % MAX_THRESHOLD;
            if (ring[idx] > oldest) {
                n++;
            }
        }
        return n;
    }

    public void reset() {
        head = 0;
        size = 0;
    }

    public int threshold() {
        return threshold;
    }

    public int windowTicks() {
        return windowTicks;
    }
}
