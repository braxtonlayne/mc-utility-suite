package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.Angles;

/**
 * Early braking and pre-turning for slippery surfaces, engine-free. On packed or blue ice a boat keeps its speed far
 * longer than on water, so the water-tuned {@link BoatThrottle} profile overshoots a sharp route corner by many
 * blocks. This looks ahead to the corner: when the stopping distance {@code speed^2 / (2 * decel)} exceeds the
 * distance to the corner and the turn is over {@value #MIN_TURN_DEG} degrees, FORWARD is released early, BACK is
 * held if even coasting would overshoot, and the heading is blended toward the next leg so the boat is already
 * pointing along it when it arrives.
 */
public final class BoatIceBraking {

    public static final double MIN_TURN_DEG = 35.0;
    /** Deceleration estimates in blocks per tick squared, conservative (calibrated near 1.5 blocks/tick). */
    public static final double DECEL_WATER = 0.06;
    public static final double DECEL_ICE = 0.075;
    public static final double DECEL_BLUE_ICE = 0.07;
    /** Slack (blocks) kept on the stopping distance. */
    public static final double MARGIN = 1.5;
    /** BACK is held when the stopping distance exceeds the corner distance by this factor. */
    public static final double BACK_RATIO = 1.4;
    public static final double BACK_MIN_SPEED = 0.15;

    public static final int NONE = 0;
    public static final int COAST = 1;
    public static final int COAST_AND_BACK = 2;

    private BoatIceBraking() {
    }

    public static double stoppingDistance(double speed, double decel) {
        return speed * speed / (2.0 * decel);
    }

    /**
     * @param onIce        the boat is on packed or blue ice (the water profile is left alone otherwise)
     * @param speed        blocks per tick
     * @param decel        surface deceleration estimate
     * @param distToCorner blocks to the corner (current stop)
     * @param cornerTurn   absolute turn angle at the corner in degrees
     * @return {@link #NONE}, {@link #COAST} (release FORWARD) or {@link #COAST_AND_BACK}
     */
    public static int command(boolean onIce, double speed, double decel, double distToCorner, double cornerTurn) {
        if (!onIce || cornerTurn <= MIN_TURN_DEG) {
            return NONE;
        }
        double stop = stoppingDistance(speed, decel) + MARGIN;
        if (stop <= distToCorner) {
            return NONE;
        }
        if (speed > BACK_MIN_SPEED && stop > distToCorner * BACK_RATIO) {
            return COAST_AND_BACK;
        }
        return COAST;
    }

    /**
     * Heading while braking: the bearing to the corner blended toward the outgoing leg as the corner nears, fully
     * pre-turned once the boat is within about a quarter of its stopping distance. Continuous in distance, so the
     * steering input stays smooth.
     */
    public static double preTurnHeading(double bearingToCorner, double outgoingYaw, double distToCorner,
                                        double stopDist) {
        if (stopDist <= 0.0) {
            return Angles.wrap(bearingToCorner);
        }
        double t = 1.0 - Math.min(1.0, Math.max(0.0, distToCorner / stopDist));
        t = Math.min(1.0, t * 1.33);
        return Angles.wrap(bearingToCorner + t * Angles.delta(bearingToCorner, outgoingYaw));
    }
}
