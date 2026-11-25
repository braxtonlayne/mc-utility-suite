package com.utilitysuite.tools.packetlab;

/**
 * Engine-free lag-spike / TPS-drop detector with hysteresis. Inputs:
 * <ul>
 *   <li>{@link #onServerTime(long, long)}: a server time packet (vanilla sends one every 20 server ticks) with the
 *       server game time; consecutive packets give the TPS ({@code game ticks / wall seconds});</li>
 *   <li>{@link #tick(long, double)} once per client tick with the client tick-cadence jitter from
 *       {@link ServerPhysicsMonitor#tickJitterMs()} (0 when the packet lab is off).</li>
 * </ul>
 * Unhealthy when no server time packet came for {@code stallMs}, the TPS fell below {@code minTps}, or the client tick
 * jitter exceeded {@code maxJitterMs}. Once unhealthy it stays paused until {@code stableTicks} consecutive healthy
 * client ticks have passed. Nothing is decided before the first packet. Allocation free; client thread only.
 */
public final class ServerStallDetector {

    public static final long DEFAULT_STALL_MS = 2_000L;
    public static final double DEFAULT_MIN_TPS = 15.0;
    public static final double DEFAULT_MAX_JITTER_MS = 60.0;
    public static final int DEFAULT_STABLE_TICKS = 40;
    /** Weight of the newest TPS sample. */
    public static final double TPS_ALPHA = 0.5;

    public long stallMs = DEFAULT_STALL_MS;
    public double minTps = DEFAULT_MIN_TPS;
    public double maxJitterMs = DEFAULT_MAX_JITTER_MS;
    public int stableTicks = DEFAULT_STABLE_TICKS;

    private long lastPacketNs = Long.MIN_VALUE;
    private long lastGameTime = Long.MIN_VALUE;
    private double tps = 20.0;
    private long sinceMs;
    private boolean paused;
    private int healthyRun;
    private int pauses;
    private String reason = "ok";

    /** A server time packet arrived at {@code nowNs} carrying {@code gameTime}. */
    public void onServerTime(long nowNs, long gameTime) {
        if (lastPacketNs != Long.MIN_VALUE && lastGameTime != Long.MIN_VALUE) {
            long dtNs = nowNs - lastPacketNs;
            long dGame = gameTime - lastGameTime;
            if (dtNs > 0 && dGame >= 0) {
                // dGame == 0: packets keep coming but the world does not advance (a frozen or wedged tick loop still
                // answers on the network thread: /tick freeze, a stuck level tick): that is 0 TPS, not "no sample"
                double sample = Math.min(20.0, dGame / (dtNs / 1.0e9));
                tps = tps + TPS_ALPHA * (sample - tps);
            } else if (dGame < 0) {
                tps = 20.0; // the world changed (dimension, server): start over
            }
        }
        lastPacketNs = nowNs;
        lastGameTime = gameTime;
    }

    /**
     * One client tick.
     *
     * @return true while high-risk automation should pause
     */
    public boolean tick(long nowNs, double clientJitterMs) {
        if (lastPacketNs == Long.MIN_VALUE) {
            sinceMs = 0L;
            reason = "no data";
            paused = false;
            return false;
        }
        sinceMs = (nowNs - lastPacketNs) / 1_000_000L;
        boolean stalled = sinceMs > stallMs;
        boolean slow = tps < minTps;
        boolean jitter = clientJitterMs > maxJitterMs;
        boolean healthy = !stalled && !slow && !jitter;
        if (!healthy) {
            reason = stalled ? "stall" : slow ? "low tps" : "jitter";
            if (!paused) {
                pauses++;
            }
            paused = true;
            healthyRun = 0;
            return true;
        }
        if (paused && ++healthyRun >= stableTicks) {
            paused = false;
            reason = "ok";
        }
        return paused;
    }

    /** Forget everything (world unload, disconnect). */
    public void reset() {
        lastPacketNs = Long.MIN_VALUE;
        lastGameTime = Long.MIN_VALUE;
        tps = 20.0;
        sinceMs = 0L;
        paused = false;
        healthyRun = 0;
        reason = "ok";
    }

    public boolean paused() {
        return paused;
    }

    public double tps() {
        return tps;
    }

    public long msSinceServerTick() {
        return sinceMs;
    }

    public int pauses() {
        return pauses;
    }

    /** Why the guard is paused (constant strings). */
    public String reason() {
        return reason;
    }
}
