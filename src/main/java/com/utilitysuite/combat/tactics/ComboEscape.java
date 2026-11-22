package com.utilitysuite.combat.tactics;

/**
 * Getting out of a combo (upgrade pass 2.7, "combo breaker"). Engine-free and allocation free.
 * <p>
 * Trigger: {@link #TRIGGER_HITS} hits taken from the opponent without landing one, the last of them just now. For the
 * escape window ({@link #WINDOW_TICKS}) the built-in movement stops: the player strafes out of the knockback line,
 * perpendicular to it (the line runs from the opponent through the player, and the aim stays on the opponent, so a
 * plain left or right strafe is perpendicular), toward the side with no hazard. Never a straight backpedal: that is
 * the line the combo runs on. Pinned against a wall (hazard or blocked behind and on one side): slide along the wall
 * toward the open side. Both sides closed: step in (forward) unless the front is closed too, then hold.
 * <p>
 * Hand-back: as soon as a hit of ours lands, or when the window ends. The side is chosen once per escape (sticky) and
 * only changes when a hazard appears on it.
 * <p>
 * Side values match {@code StrafePlanner}: {@link #LEFT} -1, {@link #NONE} 0, {@link #RIGHT} +1.
 */
public final class ComboEscape {

    public static final int LEFT = -1;
    public static final int NONE = 0;
    public static final int RIGHT = 1;

    public static final int TRIGGER_HITS = 2;
    /** Ticks the escape drives the movement (about half a second). */
    public static final int WINDOW_TICKS = 10;
    /** The last hit must be this fresh to start an escape (a stale pair from earlier does not trigger). */
    public static final int FRESH_TICKS = 2;

    private boolean active;
    private long startedAt;
    private long lastTriggerHit = Long.MIN_VALUE;
    private int side = NONE;
    private int forward;
    private boolean pinned;
    private int escapes;

    /**
     * One tick.
     *
     * @param unanswered    hits taken from the opponent since our last landed hit
     * @param lastTakenAt   fight-clock tick of the last hit taken (-1 = none)
     * @param lastLandedAt  fight-clock tick of our last landed hit (-1 = none)
     * @param preferredSide side to take when both are open ({@link #LEFT} / {@link #RIGHT}; NONE = right)
     * @return true while the escape drives the movement this tick ({@link #forward()}, {@link #side()})
     */
    public boolean update(long clock, int unanswered, long lastTakenAt, long lastLandedAt, boolean hazardFront,
                          boolean hazardBack, boolean hazardLeft, boolean hazardRight, int preferredSide) {
        if (active && (lastLandedAt >= startedAt || clock - startedAt >= WINDOW_TICKS)) {
            active = false; // our hit landed (the combo is broken) or the window is over: hand back
        }
        if (!active && unanswered >= TRIGGER_HITS && lastTakenAt >= 0 && clock - lastTakenAt <= FRESH_TICKS
                && lastTakenAt != lastTriggerHit) {
            active = true;
            startedAt = clock;
            lastTriggerHit = lastTakenAt;
            side = NONE;
            escapes++;
        }
        if (!active) {
            return false;
        }
        pinned = hazardBack && (hazardLeft || hazardRight);
        if (side == NONE || (side == LEFT && hazardLeft) || (side == RIGHT && hazardRight)) {
            side = pick(hazardLeft, hazardRight, preferredSide);
        }
        if (side == NONE) {
            forward = hazardFront ? 0 : 1; // boxed in on both sides: step in, never back along the line
        } else {
            forward = 0;
        }
        return true;
    }

    private static int pick(boolean hazardLeft, boolean hazardRight, int preferred) {
        if (hazardLeft && hazardRight) {
            return NONE;
        }
        if (hazardLeft) {
            return RIGHT;
        }
        if (hazardRight) {
            return LEFT;
        }
        return preferred == LEFT ? LEFT : RIGHT;
    }

    public boolean active() {
        return active;
    }

    /** -1 / 0 / +1; never -1 (no backpedal). */
    public int forward() {
        return forward;
    }

    public int side() {
        return side;
    }

    /** Hazard behind and on one side this tick (the slide along the wall). */
    public boolean pinned() {
        return pinned;
    }

    /** Escapes started (diagnostics, tests). */
    public int escapes() {
        return escapes;
    }

    public void reset() {
        active = false;
        side = NONE;
        forward = 0;
        pinned = false;
        lastTriggerHit = Long.MIN_VALUE;
    }
}
