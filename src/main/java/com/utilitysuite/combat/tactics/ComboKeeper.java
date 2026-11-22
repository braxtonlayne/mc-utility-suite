package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.api.IBodySimProvider;

/**
 * Keeps a combo going: right after one of our hits is sent it predicts the opponent's knockback arc (where and when
 * it comes down) and picks the sprint reset (W-release or S-tap, or none) and the steps that put us in reach,
 * sprinting and at full attack charge on the tick the opponent's hurt immunity ends. Keys only; the aim stays on the
 * target. Engine free (bodies from {@link IBodySimProvider}), allocation free after construction.
 * <p>
 * CONTRACT STUB: signatures are fixed for the volley; the bodies are filled in by the combo-keeper task. Until then
 * {@link #begin} answers false and {@link #active()} stays false, so callers keep their fixed sprint reset.
 */
public final class ComboKeeper {

    public static final int RESET_NONE = 0;
    /** Release W for {@link #resetTicks()} ticks, then sprint again. */
    public static final int RESET_W_RELEASE = 1;
    /** Tap S for {@link #resetTicks()} ticks (also opens the distance a little), then sprint again. */
    public static final int RESET_S_TAP = 2;
    /** A plan never runs longer than this (one full sword cooldown plus slack). */
    public static final int MAX_PLAN_TICKS = 20;

    public ComboKeeper(IBodySimProvider sims) {
    }

    /**
     * Plans the follow-up to a hit that was just sent. {@code in} holds both fighters BEFORE the knockback is
     * applied; the keeper applies it itself (0.4, plus the 0.5 sprint bonus along our facing when
     * {@code sprintHit}) and rolls both bodies forward.
     *
     * @param allowBackTap false when a hazard is behind us (S-tap is then never chosen)
     * @return true when a plan exists; false = no follow-up worth steering (the caller keeps its default reset)
     */
    public boolean begin(TradeInput in, boolean sprintHit, boolean allowBackTap) {
        return false;
    }

    /**
     * Advances the plan one tick against the live state and refreshes {@link #forward()}.. {@link #sprint()}.
     * Ends the plan (returns false) when the next hit was due, the opponent left the predicted arc by more than a
     * tolerance, or {@link #MAX_PLAN_TICKS} passed.
     */
    public boolean step(TradeInput in) {
        return false;
    }

    public boolean active() {
        return false;
    }

    public void reset() {
    }

    /** {@link #RESET_NONE}, {@link #RESET_W_RELEASE} or {@link #RESET_S_TAP}. */
    public int resetKind() {
        return RESET_NONE;
    }

    /** Ticks the reset is held (1..3). */
    public int resetTicks() {
        return 0;
    }

    /** Forward key this tick: +1, 0 or -1. */
    public int forward() {
        return 0;
    }

    /** Strafe this tick: +1 = left key, -1 = right key, 0 = none (same convention as {@link HitTradeSolver}). */
    public int strafe() {
        return 0;
    }

    public boolean sprint() {
        return false;
    }

    /** Ticks from now until the planned next hit (0 = swing now if the swing gate agrees). */
    public int hitInTicks() {
        return 0;
    }

    /** Where the opponent is predicted to be when it can be hit again (feet). */
    public double landX() {
        return 0.0;
    }

    public double landY() {
        return 0.0;
    }

    public double landZ() {
        return 0.0;
    }

    /** Ticks from the hit until the opponent touches the ground again (0 when it never left it). */
    public int airTicks() {
        return 0;
    }
}
