package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.ArenaField;

/**
 * Player-style terrain tactics on top of the normal spacing/strafe/swing loop. Pure and allocation free; reads the
 * terrain probes of a {@link CombatSnapshot}.
 * <ul>
 *   <li><b>{@link Tactic#HOLD_GAP} (gap ambush):</b> a drop (canyon, ravine, pit) lies between the player and the target.
 *       Do not chase into it: walk up to a safe stand-off at the near edge, stop strafing, and wait. If the target
 *       tries the jump and comes into reach while over the drop, swing at once (no crit wait) so the knockback sends
 *       it back into the pit. Works both ways: when the user ran and jumped the gap, it holds the far side.</li>
 *   <li><b>{@link Tactic#EDGE_KNOCK}:</b> the target stands within two blocks of a deep drop or lava. Circle around it
 *       (strafe) until the player is on the opposite side, so a hit knocks it toward the drop, then close in with a
 *       sprint hit for the extra knockback.</li>
 *   <li><b>{@link #swingNow}:</b> whatever the tactic, a target in reach and airborne over a deep drop gets hit
 *       immediately.</li>
 * </ul>
 * Minecraft facts used: every melee hit knocks back (0.4 base), a sprinting hit adds a stronger push; an airborne
 * entity keeps that push, so a hit mid-jump over a gap usually drops it.
 */
public final class TacticsPlanner {

    public enum Tactic {
        NONE, HOLD_GAP, EDGE_KNOCK,
        /**
         * Knockback vectoring from the arena read ({@link CombatSnapshot#knockValue}): approach from the side that
         * sends the target toward lava, a drop, a cobweb or a wall. Generalises {@link #EDGE_KNOCK} (which keeps
         * priority): used when edge knock is on, the old probe found nothing and the arena read scores at least
         * {@link #KNOCK_VECTOR_MIN_VALUE}.
         */
        KNOCK_VECTOR
    }

    /** A drop at least this deep counts (fall damage starts past 3 blocks; deeper is a pit). */
    public static final int GAP_DEPTH = 3;
    /** Open blocks under an airborne target that mean "over the pit" (a normal jump clears about 1.25). */
    public static final int OVER_DROP = 4;
    /** Stand this far (horizontal, centre to edge) from the near edge while holding a gap. */
    public static final double EDGE_STANDOFF_MIN = 0.75;
    public static final double EDGE_STANDOFF_MAX = 1.3;
    /** Edge knock: on the right side when the bearing error is below this. */
    public static final double KNOCK_ALIGNED_DEG = 25.0;
    /** Minimum {@link CombatSnapshot#knockValue} that is worth circling for. */
    public static final double KNOCK_VECTOR_MIN_VALUE = 0.35;
    /** Knock tactics only while the target stands within this height of us (blocks). */
    public static final double KNOCK_LEVEL_DY = 1.5;
    /** Minimum attack charge for a forced swing (a weaker hit still knocks back, but this keeps it human). */
    public static final float FORCED_SWING_CHARGE = 0.6f;

    private Tactic tactic = Tactic.NONE;
    private int forward;
    private int side;
    private boolean sprint;
    private boolean swingNow;

    /**
     * Plans one tick.
     *
     * @param holdGap   the gap-ambush tactic is enabled
     * @param edgeKnock the edge-knock tactic is enabled
     * @return the active tactic; {@link Tactic#NONE} leaves movement to the normal spacing/strafe loop
     */
    public Tactic plan(CombatSnapshot s, boolean holdGap, boolean edgeKnock) {
        tactic = Tactic.NONE;
        forward = SpacingController.HOLD;
        side = StrafePlanner.NONE;
        sprint = false;
        swingNow = (holdGap || edgeKnock) && !s.targetOnGround && s.targetDropBelow >= OVER_DROP && s.inReach()
                && s.crosshairOnTarget && s.attackStrength >= FORCED_SWING_CHARGE;

        double dist = s.horizontalDistance();
        if (holdGap && s.gapNear >= 0 && s.gapFar > s.gapNear && s.gapNear < dist) {
            tactic = Tactic.HOLD_GAP;
            if (s.gapNear > EDGE_STANDOFF_MAX && !s.hazardFront) {
                forward = SpacingController.FORWARD; // walk (never sprint) up to the edge
            } else if (s.gapNear < EDGE_STANDOFF_MIN && !s.hazardBack) {
                forward = SpacingController.BACK;
            }
            return tactic;
        }
        // knocking it off needs us at its level: from a ravine floor below a target on the rim, circling never lands a
        // hit (arena, 2026-11-23: a 240 s stand-off spent "circling" 8 blocks below), so leave it to the stalemate breaker
        if (Math.abs(s.ty - s.py) > KNOCK_LEVEL_DY) {
            return tactic;
        }
        if (edgeKnock && s.knockDepth >= GAP_DEPTH) {
            tactic = Tactic.EDGE_KNOCK;
            circleAndCloseIn(s, s.knockX, s.knockZ, dist, true);
        } else if (edgeKnock && s.knockValue >= KNOCK_VECTOR_MIN_VALUE) {
            tactic = Tactic.KNOCK_VECTOR;
            // a wall cannot be knocked into: keep the aligned side at normal spacing, no harder sprint-in
            circleAndCloseIn(s, s.knockFieldX, s.knockFieldZ, dist, s.knockKind != ArenaField.KNOCK_WALL);
        }
        return tactic;
    }

    /** Circles to the side opposite the knock direction (kx, kz), then closes in (sprinting when {@code sprintIn}). */
    private void circleAndCloseIn(CombatSnapshot s, double kx, double kz, double dist, boolean sprintIn) {
        double err = bearingErrorDeg(s, kx, kz);
        if (Math.abs(err) > KNOCK_ALIGNED_DEG) {
            // LEFT turns the player counter-clockwise around the target (see bearingErrorDeg)
            int want = err > 0 ? StrafePlanner.LEFT : StrafePlanner.RIGHT;
            boolean blocked = want == StrafePlanner.LEFT ? s.hazardLeft : s.hazardRight;
            side = blocked ? StrafePlanner.NONE : want;
            // keep a little distance while circling so the target does not get free hits
            forward = dist < 2.2 && !s.hazardBack ? SpacingController.BACK
                    : (dist > 3.4 && !s.hazardFront ? SpacingController.FORWARD : SpacingController.HOLD);
        } else if (!s.hazardFront) {
            if (sprintIn) {
                // lined up: close in sprinting so the swing is a sprint hit toward the hazard
                forward = s.inReach() && dist < 2.0 ? SpacingController.HOLD : SpacingController.FORWARD;
                sprint = forward == SpacingController.FORWARD;
            } else {
                forward = dist > 2.8 ? SpacingController.FORWARD : SpacingController.HOLD;
            }
        }
    }

    /**
     * Signed angle (degrees, -180..180) from where the player is around the target to where it should be: the side
     * opposite the drop. Angles are {@code atan2(z, x)} of the vector target to player; strafing LEFT while facing
     * the target increases it (Minecraft's left of a forward vector (fx, fz) is (fz, -fx)).
     */
    static double bearingErrorDeg(CombatSnapshot s) {
        return bearingErrorDeg(s, s.knockX, s.knockZ);
    }

    static double bearingErrorDeg(CombatSnapshot s, double kx, double kz) {
        double rx = s.px - s.tx;
        double rz = s.pz - s.tz;
        double have = Math.toDegrees(Math.atan2(rz, rx));
        double want = Math.toDegrees(Math.atan2(-kz, -kx));
        double d = (want - have) % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    public Tactic tactic() {
        return tactic;
    }

    public int forward() {
        return forward;
    }

    public int side() {
        return side;
    }

    public boolean sprint() {
        return sprint;
    }

    public boolean swingNow() {
        return swingNow;
    }

    public void reset() {
        tactic = Tactic.NONE;
        swingNow = false;
    }
}
