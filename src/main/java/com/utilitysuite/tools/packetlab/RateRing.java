package com.utilitysuite.tools.packetlab;

/**
 * Per-second counters over a sliding window ({@code seconds} buckets x {@code channels} counters), primitive arrays
 * only. A bucket is lazily reset when a new second reuses it. One writer thread per instance; readers tolerate races
 * (diagnostics only). Allocation free.
 */
public final class RateRing {

    private final int seconds;
    private final int channels;
    private final long[] bucketSecond;
    private final int[] values;

    public RateRing(int seconds, int channels) {
        this.seconds = seconds;
        this.channels = channels;
        this.bucketSecond = new long[seconds];
        java.util.Arrays.fill(bucketSecond, Long.MIN_VALUE);
        this.values = new int[seconds * channels];
    }

    public int seconds() {
        return seconds;
    }

    public void add(long epochSecond, int channel, int amount) {
        int b = (int) Math.floorMod(epochSecond, (long) seconds);
        if (bucketSecond[b] != epochSecond) {
            int base = b * channels;
            for (int c = 0; c < channels; c++) {
                values[base + c] = 0;
            }
            bucketSecond[b] = epochSecond;
        }
        values[b * channels + channel] += amount;
    }

    /** Value of a channel in that second, 0 when the bucket holds another second (or none). */
    public int get(long epochSecond, int channel) {
        int b = (int) Math.floorMod(epochSecond, (long) seconds);
        return bucketSecond[b] == epochSecond ? values[b * channels + channel] : 0;
    }

    /** Sum of a channel over {@code [toSecond - window + 1, toSecond]}. */
    public int sum(long toSecond, int window, int channel) {
        int s = 0;
        for (int i = 0; i < window; i++) {
            s += get(toSecond - i, channel);
        }
        return s;
    }

    public void clear() {
        java.util.Arrays.fill(bucketSecond, Long.MIN_VALUE);
    }
}
