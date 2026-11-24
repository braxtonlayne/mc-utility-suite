package com.utilitysuite.combat.assistant;

/**
 * Two small follow-ups to our own melee, engine free and allocation free.
 *
 * <p><b>Hit-confirmed sprint reset (W-tap).</b> A swing in reach arms the reset; it only starts once the hit is
 * confirmed (the target's health fell or its hurt time rose) within {@link #CONFIRM_WINDOW} ticks. A swing that
 * missed, was blocked or hit inside the target's invulnerability never drops forward, so pressure is kept.
 *
 * <p><b>Post-crit landing strafe.</b> A swing thrown while airborne (the crit hop) marks the hop; on the tick it
 * lands the next {@link #LANDING_TICKS_MIN}..{@link #LANDING_TICKS_MAX} ground ticks lean to one side, alternating
 * left and right between landings. Callers apply it as a soft bias only, never over a chosen strafe, a hazard or a
 * stage's forced side.
 */
final class HitFollowUp {
    /** Ticks after a swing in which a confirmation still counts (health syncs one to two ticks after the swing). */
    static final int CONFIRM_WINDOW = 3;
    static final int LANDING_TICKS_MIN = 3;
    static final int LANDING_TICKS_MAX = 4;

    private long armedAt = Long.MIN_VALUE;
    private boolean armed;

    private boolean critSwung;
    private int landingLeft;
    private int landingSide;
    private int nextSide = StrafePlanner.RIGHT;
    private int lastHurt;
    private int lastHurtId = -1;

    /** A swing in reach was sent with no keeper plan: wait for its confirmation. */
    void armReset(long tick) {
        armed = true;
        armedAt = tick;
    }

    /**
     * Call once per tick before {@link #confirmed}. Tracks the target's hurt-time rising edge.
     *
     * @return true on the tick the target's hurt time rose (a hit registered)
     */
    boolean hurtRose(int targetId, int hurtTime) {
        boolean rose = targetId == lastHurtId && hurtTime > lastHurt;
        lastHurtId = targetId;
        lastHurt = hurtTime;
        return rose;
    }

    /**
     * A hit on our target registered this tick.
     *
     * @return true when it answers an armed swing: the sprint reset should start now (the arm is consumed)
     */
    boolean confirmed(long tick) {
        if (!armed) {
            return false;
        }
        armed = false;
        return tick - armedAt <= CONFIRM_WINDOW;
    }

    /** Drops an arm whose window ran out (a miss). */
    void expire(long tick) {
        if (armed && tick - armedAt > CONFIRM_WINDOW) {
            armed = false;
        }
    }

    boolean armed() {
        return armed;
    }

    /** A swing was sent; one thrown off the ground is the crit hop's swing. */
    void noteSwing(boolean onGround) {
        if (!onGround) {
            critSwung = true;
        }
    }

    /**
     * Per tick, with the ground state. On the landing edge of a hop that carried a swing, starts the strafe window.
     *
     * @param ticks length of the window (3 to 4, varied by the caller's humanizer)
     */
    void onGround(boolean wasAirborne, boolean onGround, int ticks) {
        if (!onGround) {
            return;
        }
        if (wasAirborne && critSwung) {
            landingLeft = Math.max(LANDING_TICKS_MIN, Math.min(LANDING_TICKS_MAX, ticks));
            landingSide = nextSide;
            nextSide = -nextSide;
        }
        critSwung = false;
    }

    /** True while a hop that carried a swing is in the air (the caller draws the window length only on landing). */
    boolean critPending() {
        return critSwung;
    }

    boolean landingActive() {
        return landingLeft > 0;
    }

    /** {@link StrafePlanner} convention. */
    int landingSide() {
        return landingSide;
    }

    /** One movement tick of the window spent. */
    void spendLanding() {
        if (landingLeft > 0) {
            landingLeft--;
        }
    }

    void endLanding() {
        landingLeft = 0;
    }

    void reset() {
        armed = false;
        critSwung = false;
        landingLeft = 0;
        lastHurtId = -1;
        lastHurt = 0;
    }
}
