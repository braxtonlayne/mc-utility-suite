package com.utilitysuite.tools.packetlab;

/**
 * Ring of discrete, labeled timeline events (lease grants/ends, job changes, position corrections, Stop-All,
 * diagnostics). Client thread only. Labels are built when the (rare) event is posted, never per frame.
 */
public final class TimelineEvents {

    public enum Kind { LEASE_GRANTED, LEASE_ENDED, JOB_CHANGED, CORRECTION, STOP_ALL, DIAGNOSTIC, CHUNK_BURST }

    private static final Kind[] KINDS = Kind.values();

    private final int capacity;
    private final long[] millis;
    private final byte[] kind;
    private final String[] label;
    private long written;

    public TimelineEvents(int capacity) {
        this.capacity = capacity;
        this.millis = new long[capacity];
        this.kind = new byte[capacity];
        this.label = new String[capacity];
    }

    public void add(long timeMillis, Kind k, String text) {
        int i = (int) (written % capacity);
        millis[i] = timeMillis;
        kind[i] = (byte) k.ordinal();
        label[i] = text;
        written++;
    }

    /** Number of retained events. */
    public int size() {
        return (int) Math.min(written, capacity);
    }

    /** {@code index} 0 = oldest retained. */
    public long millis(int index) {
        return millis[slot(index)];
    }

    public Kind kind(int index) {
        return KINDS[kind[slot(index)]];
    }

    public String label(int index) {
        return label[slot(index)];
    }

    private int slot(int index) {
        long start = written - size();
        return (int) ((start + index) % capacity);
    }

    public void clear() {
        written = 0;
        java.util.Arrays.fill(label, null);
    }
}
