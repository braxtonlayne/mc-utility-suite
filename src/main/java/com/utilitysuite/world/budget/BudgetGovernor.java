package com.utilitysuite.world.budget;

/**
 * Engine-free tier state machine for the FPS governor. Fed one 1%-low reading per evaluation (about once a second):
 * it steps down one tier (more savings) after {@code downEvals} consecutive readings under the target, and back up one
 * tier after {@code upEvals} consecutive readings above the target plus the hysteresis margin. Readings between the
 * two thresholds reset both streaks, so the tier holds still. NaN readings (not enough frames) are ignored.
 */
public final class BudgetGovernor {

    private int tier;
    private int maxTier = 3;
    private int below;
    private int above;
    private int downEvals = 2;
    private int upEvals = 4;
    private double hysteresis = 0.15;

    public void configure(int maxTier, double hysteresis, int downEvals, int upEvals) {
        this.maxTier = Math.max(0, maxTier);
        this.hysteresis = Math.max(0.0, hysteresis);
        this.downEvals = Math.max(1, downEvals);
        this.upEvals = Math.max(1, upEvals);
        if (tier > this.maxTier) {
            tier = this.maxTier;
        }
    }

    /** Feeds one reading; returns the (possibly changed) tier. */
    public int evaluate(double lowFps, double targetFps) {
        if (Double.isNaN(lowFps) || targetFps <= 0) {
            return tier;
        }
        if (lowFps < targetFps) {
            above = 0;
            if (++below >= downEvals && tier < maxTier) {
                tier++;
                below = 0;
            }
        } else if (lowFps > targetFps * (1.0 + hysteresis)) {
            below = 0;
            if (++above >= upEvals && tier > 0) {
                tier--;
                above = 0;
            }
        } else {
            below = 0;
            above = 0;
        }
        return tier;
    }

    public int tier() {
        return tier;
    }

    public void reset() {
        tier = 0;
        below = 0;
        above = 0;
    }
}
