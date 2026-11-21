package com.utilitysuite.travel.movement;

/**
 * Early staging (idea A2): how likely the player is to go over a harmful drop in the next second, from a few cheap
 * facts the caller samples on the ground. Engine free and allocation free.
 * <p>
 * Risk is the drop's severity times the strongest of three triggers:
 * <ul>
 *   <li><b>approach</b>: moving toward the edge fast enough to reach it within {@link #APPROACH_TICKS};</li>
 *   <li><b>knockback</b>: hurt in the last {@link #KNOCKBACK_TICKS} ticks while close to the edge;</li>
 *   <li><b>fight on a ledge</b>: a hostile within reach while close to the edge.</li>
 * </ul>
 * {@link #update} adds hysteresis: staging starts at {@link #RISE} and ends only after the risk stayed below
 * {@link #FALL} for {@link #HOLD_TICKS} ticks, so the hotbar does not flicker at an edge. Staging only PREPARES the
 * clutch (item in the hotbar, slot preselected); it never moves the camera.
 */
public final class FallRiskEstimator {

    public static final double RISE = 0.5;
    public static final double FALL = 0.25;
    public static final int HOLD_TICKS = 20;
    public static final int APPROACH_TICKS = 12;
    public static final int KNOCKBACK_TICKS = 10;
    /** Drops at most this deep hurt nothing (fall damage starts above 3 blocks). */
    public static final double SAFE_DROP = 3.5;
    /** A drop this much deeper than safe counts as fully severe. */
    public static final double SEVERE_SPAN = 8.0;
    /** Edge distance below which knockback or a fight counts. */
    public static final double LEDGE_DISTANCE = 2.5;

    private boolean staged;
    private int calmTicks;
    private double lastRisk;

    /**
     * Instant risk in [0, 1].
     *
     * @param edgeDistance      horizontal blocks to the nearest harmful drop (large when none)
     * @param dropDepth         depth of that drop in blocks
     * @param speedTowardEdge   blocks per tick toward the edge (negative: moving away)
     * @param ticksSinceHurt    ticks since the player was last hurt (large when never)
     * @param hostilesInReach   hostile mobs or players within about 4 blocks
     */
    public static double risk(double edgeDistance, double dropDepth, double speedTowardEdge, int ticksSinceHurt,
                              int hostilesInReach) {
        if (dropDepth <= SAFE_DROP || edgeDistance > 6.0) {
            return 0.0;
        }
        double severity = Math.min(1.0, 0.4 + (dropDepth - SAFE_DROP) / SEVERE_SPAN);
        double approach = 0.0;
        if (speedTowardEdge > 0.02) {
            double ticks = edgeDistance / speedTowardEdge;
            approach = ticks >= APPROACH_TICKS ? 0.0 : 1.0 - ticks / APPROACH_TICKS * 0.5;
        }
        double near = Math.max(0.0, 1.0 - edgeDistance / LEDGE_DISTANCE);
        double knock = ticksSinceHurt <= KNOCKBACK_TICKS ? 0.5 + 0.5 * near : 0.0;
        if (edgeDistance > LEDGE_DISTANCE) {
            knock = 0.0;
        }
        double fight = hostilesInReach > 0 && edgeDistance <= LEDGE_DISTANCE ? 0.4 + 0.5 * near : 0.0;
        return severity * Math.max(approach, Math.max(knock, fight));
    }

    /** Feeds one tick; returns whether the clutch should be staged now. */
    public boolean update(double edgeDistance, double dropDepth, double speedTowardEdge, int ticksSinceHurt,
                          int hostilesInReach) {
        double r = risk(edgeDistance, dropDepth, speedTowardEdge, ticksSinceHurt, hostilesInReach);
        lastRisk = r;
        if (r >= RISE) {
            staged = true;
            calmTicks = 0;
        } else if (staged) {
            if (r < FALL) {
                if (++calmTicks >= HOLD_TICKS) {
                    staged = false;
                    calmTicks = 0;
                }
            } else {
                calmTicks = 0;
            }
        }
        return staged;
    }

    public boolean staged() {
        return staged;
    }

    public double lastRisk() {
        return lastRisk;
    }

    public void reset() {
        staged = false;
        calmTicks = 0;
        lastRisk = 0.0;
    }
}
