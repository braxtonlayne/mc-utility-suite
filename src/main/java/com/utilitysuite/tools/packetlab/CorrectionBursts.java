package com.utilitysuite.tools.packetlab;

/**
 * Detects repeated server position corrections ("rubber-banding"): {@code threshold} or more corrections within
 * {@code windowMillis}. Each detected burst is counted once, however many corrections it contains. Pure Java,
 * allocation free.
 */
public final class CorrectionBursts {

    private final int threshold;
    private final long windowMillis;
    private final long[] recent;
    private int size;
    private int head;
    private long burstUntil = Long.MIN_VALUE;
    private int bursts;
    private long lastBurstStart = Long.MIN_VALUE;

    public CorrectionBursts(int threshold, long windowMillis) {
        this.threshold = Math.max(2, threshold);
        this.windowMillis = windowMillis;
        this.recent = new long[this.threshold];
    }

    /**
     * Records a correction. Returns true when it belongs to a burst (it is at least the {@code threshold}-th
     * correction inside the window, or follows one within the window).
     */
    public boolean record(long millis) {
        recent[head] = millis;
        head = (head + 1) % recent.length;
        if (size < recent.length) {
            size++;
        }
        boolean inBurst = millis <= burstUntil;
        if (size == recent.length) {
            long oldest = recent[head]; // ring is full: head now points at the oldest entry
            if (millis - oldest <= windowMillis) {
                if (!inBurst) {
                    bursts++;
                    lastBurstStart = oldest;
                }
                inBurst = true;
            }
        }
        if (inBurst) {
            burstUntil = millis + windowMillis;
        }
        return inBurst;
    }

    /** True when {@code millis} lies inside the currently tracked burst. */
    public boolean isInBurst(long millis) {
        return millis <= burstUntil && millis >= lastBurstStart;
    }

    public int bursts() {
        return bursts;
    }

    public void clear() {
        size = 0;
        head = 0;
        bursts = 0;
        burstUntil = Long.MIN_VALUE;
        lastBurstStart = Long.MIN_VALUE;
    }
}
