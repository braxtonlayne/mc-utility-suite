package com.utilitysuite.jobs.goal;

/**
 * Decides whether a goal job that was preempted by an EMERGENCY owner (the creeper back-off, the fall clutch) may
 * resume yet. Armed in {@code onPreempted}, consulted every tick while the job waits to resume:
 * <ul>
 *   <li>{@link Decision#HOLD} while an explosion or lava hazard is live near the player, or near the job's next step,
 *       and for a settle period after the last one cleared (a hazard that comes back restarts the settle);</li>
 *   <li>{@link Decision#GO} once everything has stayed clear for the settle period;</li>
 *   <li>{@link Decision#GIVE_UP} when a hazard is still live after the maximum wait: the caller pauses the job with
 *       a notice instead of walking back in.</li>
 * </ul>
 * A hazard near the next step only counts when its kind was also seen near the player since the gate was armed, so
 * the hold follows the danger that caused the escape and not, say, a lava pool that was always next to the ore.
 * <p>
 * Hazards are bit masks of {@link #EXPLOSION} and {@link #LAVA}. Pure and allocation free (primitives only, no
 * Minecraft types); client thread only.
 */
public final class ResumeHoldGate {

    /** Result of {@link #decide}. */
    public enum Decision { HOLD, GO, GIVE_UP }

    /** Hazard bit: a fuse or primed explosive. */
    public static final int EXPLOSION = 1;
    /** Hazard bit: lava at the feet, on the path, or freshly uncovered. */
    public static final int LAVA = 2;

    /** Default longest wait for a live hazard, counted from the first resume attempt (10 s). */
    public static final int DEFAULT_MAX_HOLD_TICKS = 200;
    /** Default settle period once every hazard is gone (1.5 s). */
    public static final int DEFAULT_SETTLE_TICKS = 30;

    private int maxHoldTicks;
    private int settleTicks;

    private boolean pending;
    /** Tick of the first {@link #decide} since arming, or -1. The maximum wait counts from here. */
    private long waitSince = -1;
    /** First tick everything was seen clear since arming, or -1 while a hazard is (or was last seen) live. */
    private long clearSince = -1;
    /** Hazard kinds seen near the player since arming. */
    private int seen;
    /** Hazard kinds that held the last decision; kept after {@link Decision#GIVE_UP} for the notice. */
    private int blockedBy;

    public ResumeHoldGate() {
        this(DEFAULT_MAX_HOLD_TICKS, DEFAULT_SETTLE_TICKS);
    }

    public ResumeHoldGate(int maxHoldTicks, int settleTicks) {
        configure(maxHoldTicks, settleTicks);
    }

    /** Changes the limits (profile load). Takes effect on the next {@link #decide}. */
    public void configure(int maxHoldTicks, int settleTicks) {
        this.maxHoldTicks = Math.max(0, maxHoldTicks);
        this.settleTicks = Math.max(0, settleTicks);
    }

    /**
     * Records a preemption. Only an emergency preemption arms the gate; any other kind disarms it, so a resume after a
     * normal or manual preemption, or Stop-All, is never delayed.
     *
     * @param nearPlayer hazard kinds live near the player right now
     */
    public void onPreempted(boolean byEmergency, int nearPlayer) {
        disarm();
        if (byEmergency) {
            pending = true;
            seen = nearPlayer;
        }
    }

    /** Drops any pending hold (Stop-All, user resume, world unload, job finished). */
    public void disarm() {
        pending = false;
        waitSince = -1;
        clearSince = -1;
        seen = 0;
    }

    /** True while a resume must still be checked. The cheap early-out for callers. */
    public boolean pending() {
        return pending;
    }

    /**
     * Hazard kinds seen near the player since the gate was armed. While this is 0 a hazard near the next step cannot
     * count, so callers may skip looking for one.
     */
    public int seen() {
        return seen;
    }

    /** Hazard kinds that held the most recent HOLD or GIVE_UP decision (0 while only settling). */
    public int blockedBy() {
        return blockedBy;
    }

    /**
     * @param now        current client tick
     * @param nearPlayer hazard kinds live near the player
     * @param nearStep   hazard kinds live near the job's next step (0 when unknown or too far to matter)
     * @return GO when nothing is pending or everything has been clear for the settle period; GIVE_UP when a hazard is
     *         still live after the maximum wait; HOLD otherwise. GO and GIVE_UP disarm the gate.
     */
    public Decision decide(long now, int nearPlayer, int nearStep) {
        if (!pending) {
            return Decision.GO;
        }
        if (waitSince < 0) {
            waitSince = now;
        }
        seen |= nearPlayer;
        int live = nearPlayer | (nearStep & seen);
        blockedBy = live;
        if (live != 0) {
            clearSince = -1;
            if (now - waitSince >= maxHoldTicks) {
                disarm();
                return Decision.GIVE_UP;
            }
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
}
