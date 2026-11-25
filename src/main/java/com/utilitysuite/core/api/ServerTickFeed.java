package com.utilitysuite.core.api;

/**
 * Shared server-tick heartbeat: the time of the last server time-sync packet (vanilla sends one every 20 server ticks,
 * so about once a second on a healthy server). Written by the PacketLab time-packet hook, read by modules that should
 * hold still while the server is stalled (the AFK grinder). Lock-free, allocation free.
 */
public final class ServerTickFeed {

    /** Vanilla time-sync period in milliseconds (20 ticks). */
    public static final long PERIOD_MS = 1000L;

    private static volatile long lastPacketMs = Long.MIN_VALUE;

    private ServerTickFeed() {
    }

    public static void onTimePacket(long nowMs) {
        lastPacketMs = nowMs;
    }

    /**
     * Milliseconds the server is overdue: time since the last time packet minus the normal period, 0 when on time or
     * when no packet was seen yet (single player before the first sync, or the hook is not active).
     */
    public static long overdueMs(long nowMs) {
        long last = lastPacketMs;
        if (last == Long.MIN_VALUE) {
            return 0L;
        }
        return Math.max(0L, nowMs - last - PERIOD_MS);
    }

    public static void reset() {
        lastPacketMs = Long.MIN_VALUE;
    }
}
