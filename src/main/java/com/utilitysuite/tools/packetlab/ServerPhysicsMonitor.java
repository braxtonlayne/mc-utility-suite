package com.utilitysuite.tools.packetlab;

/**
 * Passive server-physics and anti-cheat calibration monitor.
 * <p>
 * Tracks rolling RTT median/p95, client tick cadence jitter, and {@code PositionCorrection} setback bursts.
 * Pure Java, zero Minecraft imports, zero allocations on hot paths.
 */
public final class ServerPhysicsMonitor {

    private static final int SAMPLE_CAPACITY = 32;
    private static final long IDEAL_TICK_NS = 50_000_000L;
    private static final long SETBACK_WINDOW_MS = 10_000L;
    private static final long STEP_DOWN_COOLDOWN_MS = 15_000L;
    private static final double MAX_EXTRA_LEAD_TICKS = 2.0;
    private static final double MAX_SAFETY_MARGIN = 0.15;

    private static final ServerPhysicsMonitor NO_OP = new ServerPhysicsMonitor(true);
    private static volatile ServerPhysicsMonitor current = NO_OP;

    private final boolean noOp;
    private final double[] rttSamples = new double[SAMPLE_CAPACITY];
    private final double[] rttScratch = new double[SAMPLE_CAPACITY];
    private int rttHead;
    private int rttCount;

    private final double[] tickJitterSamplesMs = new double[SAMPLE_CAPACITY];
    private int tickHead;
    private int tickCount;

    private long prevSetbackMs = Long.MIN_VALUE;
    private long cooldownUntilMs = Long.MIN_VALUE;
    private boolean stepDownPending;

    public ServerPhysicsMonitor() {
        this(false);
    }

    private ServerPhysicsMonitor(boolean noOp) {
        this.noOp = noOp;
    }

    /** Read-only active monitor (returns a zero/false no-op instance when PacketLab is disabled or uninitialized). */
    public static ServerPhysicsMonitor current() {
        return current;
    }

    /** Sets the active monitor (passing {@code null} restores the no-op instance). */
    public static void setCurrent(ServerPhysicsMonitor monitor) {
        current = monitor != null ? monitor : NO_OP;
    }

    public synchronized void recordRttMs(double rttMs) {
        if (noOp || !Double.isFinite(rttMs) || rttMs < 0.0) {
            return;
        }
        rttSamples[rttHead] = rttMs;
        rttHead = (rttHead + 1) % SAMPLE_CAPACITY;
        if (rttCount < SAMPLE_CAPACITY) {
            rttCount++;
        }
    }

    public synchronized void recordTickIntervalNs(long intervalNs) {
        if (noOp || intervalNs <= 0L) {
            return;
        }
        double devMs = Math.abs(intervalNs - IDEAL_TICK_NS) / 1_000_000.0;
        tickJitterSamplesMs[tickHead] = devMs;
        tickHead = (tickHead + 1) % SAMPLE_CAPACITY;
        if (tickCount < SAMPLE_CAPACITY) {
            tickCount++;
        }
    }

    public synchronized void recordSetback(long nowMs) {
        if (noOp) {
            return;
        }
        if (prevSetbackMs != Long.MIN_VALUE
                && nowMs - prevSetbackMs >= 0L
                && nowMs - prevSetbackMs <= SETBACK_WINDOW_MS
                && nowMs >= cooldownUntilMs) {
            stepDownPending = true;
            cooldownUntilMs = nowMs + STEP_DOWN_COOLDOWN_MS;
        }
        prevSetbackMs = nowMs;
    }

    public synchronized double rttMedianMs() {
        if (noOp || rttCount == 0) {
            return 0.0;
        }
        sortRttScratch();
        int mid = rttCount / 2;
        if ((rttCount & 1) == 1) {
            return rttScratch[mid];
        }
        return (rttScratch[mid - 1] + rttScratch[mid]) * 0.5;
    }

    public synchronized double rttP95Ms() {
        if (noOp || rttCount == 0) {
            return 0.0;
        }
        sortRttScratch();
        int idx = (int) Math.floor(0.95 * rttCount);
        if (idx < 0) {
            idx = 0;
        } else if (idx >= rttCount) {
            idx = rttCount - 1;
        }
        return rttScratch[idx];
    }

    public synchronized double tickJitterMs() {
        if (noOp || tickCount == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < tickCount; i++) {
            sum += tickJitterSamplesMs[i];
        }
        return sum / tickCount;
    }

    /** Velocity-lead compensation in ticks derived from RTT jitter (p95 - median), capped safely. */
    public synchronized double extraLeadTicks() {
        if (noOp || rttCount < 2) {
            return 0.0;
        }
        double jitterMs = Math.max(0.0, rttP95Ms() - rttMedianMs());
        double ticks = jitterMs / 50.0;
        return Math.min(MAX_EXTRA_LEAD_TICKS, Math.max(0.0, ticks));
    }

    /** Edge/tick-phase safety margin in blocks derived from tick cadence jitter and RTT jitter. */
    public synchronized double movementSafetyMargin() {
        if (noOp) {
            return 0.0;
        }
        double tJitter = tickJitterMs();
        double rJitter = rttCount >= 2 ? Math.max(0.0, rttP95Ms() - rttMedianMs()) : 0.0;
        double margin = (tJitter / 50.0) * 0.05 + (rJitter / 50.0) * 0.03;
        return Math.min(MAX_SAFETY_MARGIN, Math.max(0.0, margin));
    }

    /**
     * Returns {@code true} once when {@code >= 2} setbacks occur within 10s, subject to a 15s cooldown.
     */
    public synchronized boolean shouldStepDownPreset() {
        if (noOp || !stepDownPending) {
            return false;
        }
        stepDownPending = false;
        return true;
    }

    public synchronized void clear() {
        rttHead = 0;
        rttCount = 0;
        tickHead = 0;
        tickCount = 0;
        prevSetbackMs = Long.MIN_VALUE;
        cooldownUntilMs = Long.MIN_VALUE;
        stepDownPending = false;
    }

    private void sortRttScratch() {
        System.arraycopy(rttSamples, 0, rttScratch, 0, rttCount);
        for (int i = 1; i < rttCount; i++) {
            double key = rttScratch[i];
            int j = i - 1;
            while (j >= 0 && rttScratch[j] > key) {
                rttScratch[j + 1] = rttScratch[j];
                j--;
            }
            rttScratch[j + 1] = key;
        }
    }
}
