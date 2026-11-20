package com.utilitysuite.tools.packetlab;

import java.util.random.RandomGenerator;

/**
 * Computes delivery times for the local test latency simulator: {@code latency ± jitter} (uniform), never earlier than
 * the previous packet in the same direction, so packet order is always preserved (TCP never reorders, and Minecraft's
 * protocol depends on order). Pure Java, allocation free. Confined to one thread (the channel's event loop).
 */
public final class LatencyScheduler {

    private final RandomGenerator random;
    private final long[] lastDue = {Long.MIN_VALUE, Long.MIN_VALUE};

    public LatencyScheduler(RandomGenerator random) {
        this.random = random;
    }

    /**
     * @param nowNanos  current time
     * @param direction {@link PacketRingBuffer#INBOUND} or {@link PacketRingBuffer#OUTBOUND}
     * @return absolute due time in nanoseconds, {@code >= nowNanos} and {@code >=} the previous due time
     */
    public long nextDue(long nowNanos, byte direction, int latencyMs, int jitterMs) {
        double jitter = jitterMs <= 0 ? 0.0 : (random.nextDouble() * 2.0 - 1.0) * jitterMs;
        double delayMs = Math.max(0.0, latencyMs + jitter);
        long due = nowNanos + (long) (delayMs * 1_000_000.0);
        long prev = lastDue[direction];
        if (prev != Long.MIN_VALUE && due < prev) {
            due = prev;
        }
        lastDue[direction] = due;
        return due;
    }

    /** Due time for passing a packet through while delayed ones are still queued (keeps order when switching off). */
    public long drainDue(long nowNanos, byte direction) {
        long prev = lastDue[direction];
        long due = prev == Long.MIN_VALUE ? nowNanos : Math.max(nowNanos, prev);
        lastDue[direction] = due;
        return due;
    }
}
