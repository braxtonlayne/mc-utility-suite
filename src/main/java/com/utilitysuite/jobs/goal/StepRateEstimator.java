package com.utilitysuite.jobs.goal;

/**
 * Live ETA for a running job: an exponential moving average of how long a plan step takes, with paused time left
 * out. {@link #etaSeconds} = the remaining steps after the current one at the average duration, plus what the average
 * leaves of the current step. Pure arithmetic on primitive fields: no allocation, safe to call every tick.
 */
public final class StepRateEstimator {

    /** Weight of the newest step duration (0.3: a few steps settle the average, one slow step does not swing it). */
    static final double ALPHA = 0.3;
    /** Steps shorter than this are bookkeeping (a craft from the inventory); they don't pull the average down. */
    static final long MIN_STEP_MS = 250;

    private double avgMs;
    private int samples;
    private long stepStartMs = -1;
    private long pausedAtMs = -1;

    /** A job (re)started from its first step, or a different job became active. */
    public void reset(long nowMs) {
        avgMs = 0;
        samples = 0;
        stepStartMs = nowMs;
        pausedAtMs = -1;
    }

    /** The current step finished at {@code nowMs}; the next one starts now. */
    public void stepCompleted(long nowMs) {
        if (stepStartMs >= 0 && pausedAtMs < 0) {
            long d = nowMs - stepStartMs;
            if (d >= MIN_STEP_MS) {
                avgMs = samples == 0 ? d : avgMs + ALPHA * (d - avgMs);
                samples++;
            }
        }
        stepStartMs = nowMs;
        if (pausedAtMs >= 0) {
            pausedAtMs = nowMs;
        }
    }

    /** The job paused (user, preemption, hazard hold): the clock of the current step stops. */
    public void pause(long nowMs) {
        if (pausedAtMs < 0) {
            pausedAtMs = nowMs;
        }
    }

    /** The job runs again: the paused time is not counted against the current step. */
    public void resume(long nowMs) {
        if (pausedAtMs >= 0) {
            if (stepStartMs >= 0) {
                stepStartMs += nowMs - pausedAtMs;
            }
            pausedAtMs = -1;
        }
    }

    /** Steps measured so far. */
    public int samples() {
        return samples;
    }

    /** Average step duration in milliseconds (0 before the first measured step). */
    public double averageStepMs() {
        return avgMs;
    }

    /**
     * Seconds left for {@code remainingSteps} steps (the current one included), or -1 while unknown (no measured step
     * yet, or nothing left).
     */
    public int etaSeconds(int remainingSteps, long nowMs) {
        if (samples == 0 || remainingSteps <= 0 || stepStartMs < 0) {
            return -1;
        }
        long now = pausedAtMs >= 0 ? pausedAtMs : nowMs;
        double inCurrent = Math.max(0.0, avgMs - (now - stepStartMs));
        double ms = (remainingSteps - 1) * avgMs + inCurrent;
        return (int) Math.ceil(ms / 1000.0);
    }
}
