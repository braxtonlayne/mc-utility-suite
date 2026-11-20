package com.utilitysuite.tools.packetlab;

/**
 * Fixed-size ring of packet records in primitive arrays: timestamp, type id, direction and size. Only types and sizes
 * are stored, never payloads, so chat, authentication and identifying content cannot end up in it.
 * <p>
 * Threading: exactly one writer (the connection's Netty event loop handles every inbound read and outbound write) and
 * any number of readers (the timeline screen on the client thread). The writer publishes each record by advancing the
 * volatile {@link #written} counter after filling the slot; {@link #snapshot} copies the newest records and discards
 * any that were overwritten while it copied. {@link #record} and {@link #addSizeToLast} allocate nothing.
 */
public final class PacketRingBuffer {

    public static final byte INBOUND = 0;
    public static final byte OUTBOUND = 1;
    /** Size of a packet whose wire size is unknown (single-player memory connections are never serialized). */
    public static final int UNKNOWN_SIZE = -1;

    private final int capacity;
    private final int mask;
    private final long[] timeMillis;
    private final int[] type;
    private final int[] size;
    private final byte[] direction;
    private volatile long written;
    private long lastOutbound = -1;

    /** @param capacityPow2 rounded up to a power of two */
    public PacketRingBuffer(int capacityPow2) {
        int cap = Integer.highestOneBit(Math.max(2, capacityPow2 - 1)) << 1;
        this.capacity = cap;
        this.mask = cap - 1;
        this.timeMillis = new long[cap];
        this.type = new int[cap];
        this.size = new int[cap];
        this.direction = new byte[cap];
    }

    public int capacity() {
        return capacity;
    }

    /** Total records ever written (monotonic). */
    public long written() {
        return written;
    }

    /** Writer thread only. Returns the record's sequence number. */
    public long record(long millis, int typeId, byte dir, int bytes) {
        long seq = written;
        int i = (int) (seq & mask);
        timeMillis[i] = millis;
        type[i] = typeId;
        size[i] = bytes;
        direction[i] = dir;
        if (dir == OUTBOUND) {
            lastOutbound = seq;
        }
        written = seq + 1;
        return seq;
    }

    /**
     * Writer thread only: adds encoded bytes to the newest outbound record (the encoder runs after the packet-level
     * probe on the way out). Ignored when that record was already overwritten.
     */
    public void addSizeToLast(int bytes) {
        long seq = lastOutbound;
        if (seq < 0 || written - seq > capacity) {
            return;
        }
        int i = (int) (seq & mask);
        size[i] = size[i] == UNKNOWN_SIZE ? bytes : size[i] + bytes;
    }

    /**
     * Copies up to {@code max} newest records (oldest first) into the given arrays and returns how many were copied.
     * Records the writer overwrote during the copy are dropped from the front.
     */
    public int snapshot(int max, long[] outTime, int[] outType, int[] outSize, byte[] outDir) {
        long end = written;
        int n = (int) Math.min(Math.min(max, capacity), end);
        long start = end - n;
        for (int k = 0; k < n; k++) {
            int i = (int) ((start + k) & mask);
            outTime[k] = timeMillis[i];
            outType[k] = type[i];
            outSize[k] = size[i];
            outDir[k] = direction[i];
        }
        long after = written;
        long overwritten = after - capacity - start; // records with seq < after - capacity may be torn
        if (overwritten > 0) {
            int drop = (int) Math.min(n, overwritten);
            System.arraycopy(outTime, drop, outTime, 0, n - drop);
            System.arraycopy(outType, drop, outType, 0, n - drop);
            System.arraycopy(outSize, drop, outSize, 0, n - drop);
            System.arraycopy(outDir, drop, outDir, 0, n - drop);
            n -= drop;
        }
        return n;
    }

    /** Writer thread (or when no writer is attached). */
    public void clear() {
        written = 0;
        lastOutbound = -1;
    }
}
