package com.utilitysuite.core.api;

/**
 * Shared server-tick heartbeat: the time of the last server time-sync packet whose game time moved on (the server sends
 * one every 20 server ticks, so about once a second on a healthy server). Written by the time-packet hooks (vanilla
 * {@code ClientboundSetTimePacket}, and NeoForge's own time payload, which replaces it when both sides run NeoForge),
 * read by modules that should hold still while the server is stalled (the AFK grinder). A server whose game time
 * stands still keeps sending the packet ({@code /tick freeze}: the server loop runs, the levels do not), so the
 * heartbeat only counts a packet whose game time differs from the previous one. Lock-free, allocation free.
 */
public final class ServerTickFeed {

    /** Vanilla time-sync period in milliseconds (20 ticks). */
    public static final long PERIOD_MS = 1000L;

    private static volatile long lastAdvanceMs = Long.MIN_VALUE;
    private static volatile long lastGameTime = Long.MIN_VALUE;

    private ServerTickFeed() {
    }

    /**
     * A time packet arrived at {@code nowMs} carrying the server's {@code gameTime}. Only a packet whose game time
     * changed (or the first one) counts as a heartbeat.
     */
    public static void onTimePacket(long nowMs, long gameTime) {
        if (lastAdvanceMs == Long.MIN_VALUE || gameTime != lastGameTime) {
            lastAdvanceMs = nowMs;
        }
        lastGameTime = gameTime;
    }

    /**
     * Milliseconds the server is overdue: time since the last packet with an advancing game time minus the normal
     * period, 0 when on time or when no packet was seen yet (single player before the first sync, or no hook active).
     */
    public static long overdueMs(long nowMs) {
        long last = lastAdvanceMs;
        if (last == Long.MIN_VALUE) {
            return 0L;
        }
        return Math.max(0L, nowMs - last - PERIOD_MS);
    }

    public static void reset() {
        lastAdvanceMs = Long.MIN_VALUE;
        lastGameTime = Long.MIN_VALUE;
    }
}
