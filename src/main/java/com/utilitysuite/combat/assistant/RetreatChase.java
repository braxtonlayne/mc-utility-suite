package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.ArenaField;

/**
 * Keys for chasing a retreating opponent (setting "Chase a retreating opponent"): it faces away, runs and does not
 * swing, so it is no threat and every tick out of reach is a hit we are not landing. Sprint straight at it, sprint-jump
 * on open ground while it is a few blocks ahead (faster than a flat sprint), and when the arena read shows a wall
 * near it, drift to the side that lines the wall up behind it so the next knockback pins it there. Engine free and
 * allocation free: call {@link #plan} once per tick, read the keys.
 */
public final class RetreatChase {

    /** Sprint-jump only this far behind it (centre to centre): closer, a hop costs more control than it gains. */
    public static final double JUMP_MIN_DISTANCE = 3.5;
    /** Headroom (open blocks) a sprint-jump needs. */
    public static final int JUMP_HEADROOM = 3;
    /** Drift toward the wall line only while closer than this and more than {@link #PIN_ALIGNED_DEG} off it. */
    public static final double PIN_MAX_DISTANCE = 6.0;
    public static final double PIN_ALIGNED_DEG = 35.0;

    private int forward;
    private int side;
    private boolean jump;

    /**
     * Plans this tick's chase keys.
     *
     * @return false when there is nothing to chase toward (the caller runs its normal movement)
     */
    public boolean plan(CombatSnapshot s) {
        forward = SpacingController.HOLD;
        side = StrafePlanner.NONE;
        jump = false;
        double d = s.horizontalDistance();
        if (!(d > 1.0e-3)) {
            return false;
        }
        forward = SpacingController.FORWARD;
        if (s.knockValue > 0.0 && s.knockKind == ArenaField.KNOCK_WALL && d <= PIN_MAX_DISTANCE) {
            double err = TacticsPlanner.bearingErrorDeg(s, s.knockFieldX, s.knockFieldZ);
            if (Math.abs(err) > PIN_ALIGNED_DEG) {
                side = err > 0 ? StrafePlanner.LEFT : StrafePlanner.RIGHT;
            }
        }
        jump = side == StrafePlanner.NONE && d >= JUMP_MIN_DISTANCE && s.onGround && s.headroom >= JUMP_HEADROOM
                && !s.hazardFront && !s.inWater && !s.climbing;
        return true;
    }

    /** {@link SpacingController#FORWARD} while chasing. */
    public int forward() {
        return forward;
    }

    /** {@link StrafePlanner} side toward the wall line, or NONE. */
    public int side() {
        return side;
    }

    /** Tap jump this tick (a sprint-jump). */
    public boolean jump() {
        return jump;
    }
}
