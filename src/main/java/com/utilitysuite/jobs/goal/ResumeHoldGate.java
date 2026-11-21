package com.utilitysuite.jobs.goal;

/**
 * Decides whether a goal job that was preempted by an EMERGENCY owner (the creeper back-off) may resume yet. Armed in
 * {@code onPreempted}, consulted every tick while the job waits to resume: it answers {@link Decision#HOLD} while an
 * explosive hazard is still live, then waits a short settle period after the hazard clears, and never holds longer
 * than a hard maximum counted from the preemption, so a job cannot stall forever.
 * <p>
 * Pure and allocation free (primitives only, no Minecraft types); client thread only.
 * <p>
 * Limitation: this only delays the resume. The job then follows its unchanged plan, so if the hazard outlives the
 * maximum hold the route may still pass the blast spot; a planner no-go zone around the hazard would fix that.
 */
public final class ResumeHoldGate {

    /** Result of {@link #decide}. */
    public enum Decision { HOLD, GO }

    /** Default hard cap on a hold, counted from the preemption (5 s). */
    public static final int DEFAULT_MAX_HOLD_TICKS = 100;
    /** Default settle period once the hazard is gone (0.5 s). */
    public static final int DEFAULT_SETTLE_TICKS = 10;

    private final int maxHoldTicks;
    private final int settleTicks;

    private boolean pending;
    private long preemptedTick;
    /** First tick the hazard was seen clear since arming, or -1 while it is (or was last seen) present. */
    private long clearSince = -1;

    public ResumeHoldGate() {
        this(DEFAULT_MAX_HOLD_TICKS, DEFAULT_SETTLE_TICKS);
    }

    public ResumeHoldGate(int maxHoldTicks, int settleTicks) {
        this.maxHoldTicks = Math.max(0, maxHoldTicks);
        this.settleTicks = Math.max(0, settleTicks);
    }

    /**
     * Records a preemption. Only an emergency preemption arms the gate; any other kind disarms it, so a resume after a
     * normal or manual preemption is never delayed.
     */
    public void onPreempted(long tick, boolean byEmergency) {
        if (byEmergency) {
            pending = true;
            preemptedTick = tick;
            clearSince = -1;
        } else {
            pending = false;
        }
    }

    /** Drops any pending hold (Stop-All, user resume, world unload, job finished). */
    public void disarm() {
        pending = false;
        clearSince = -1;
    }

    /** True while a resume must still be checked. The cheap early-out for callers. */
    public boolean pending() {
        return pending;
    }

    /**
     * @param now           current client tick
     * @param hazardPresent an explosive hazard is still live near the player (or near the job's next target)
     * @return {@link Decision#GO} when nothing is pending, the hazard has been clear for the settle period, or the
     *         maximum hold has elapsed; {@link Decision#HOLD} otherwise. GO disarms the gate.
     */
    public Decision decide(long now, boolean hazardPresent) {
        if (!pending) {
            return Decision.GO;
        }
        if (now - preemptedTick >= maxHoldTicks) {
            disarm();
            return Decision.GO;
        }
        if (hazardPresent) {
            clearSince = -1;
            return Decision.HOLD;
        }
        if (clearSince < 0) {
            clearSince = now;
        }
        if (now - clearSince >= settleTicks) {
            disarm();
            return Decision.GO;
        }
        return Decision.HOLD;
    }

    /**
     * Distance-aware variant: the hazard only counts when it is within {@code radius} of the player or the target.
     * Pass a negative distance for "unknown" (counted as near).
     */
    public Decision decide(long now, boolean hazardPresent, double distToPlayer, double distToTarget, double radius) {
        boolean near = hazardPresent
                && (distToPlayer < 0 || distToPlayer <= radius || (distToTarget >= 0 && distToTarget <= radius));
        return decide(now, near);
    }
}
