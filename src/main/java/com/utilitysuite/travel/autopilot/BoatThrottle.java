package com.utilitysuite.travel.autopilot;

/**
 * Boat speed profile, engine-free. A boat has only an on/off paddle, so the profile yields a throttle in [0, 1] that
 * {@link DutyCycle} turns into FORWARD key holds. The throttle is the minimum of three factors:
 * <ul>
 *   <li><b>Heading error</b>: full ahead within {@value #STRAIGHT_DEG} degrees, falling linearly to
 *       {@value #MIN_TURN_THROTTLE} at {@value #SHARP_DEG} degrees or more (turn before accelerating).</li>
 *   <li><b>Upcoming corner</b>: inside the braking distance before a route corner, the throttle eases from 1 toward the
 *       corner's own turn factor, so the boat arrives at a sharp corner slow and at a gentle one nearly at speed. The
 *       braking distance grows with speed.</li>
 *   <li><b>Arrival</b>: within {@value #ARRIVAL_SLOWDOWN} blocks of the destination, down to
 *       {@value #MIN_ARRIVAL_THROTTLE} at the stop.</li>
 * </ul>
 */
public final class BoatThrottle {

    public static final double STRAIGHT_DEG = 10.0;
    public static final double SHARP_DEG = 90.0;
    public static final double MIN_TURN_THROTTLE = 0.15;
    public static final double ARRIVAL_SLOWDOWN = 12.0;
    public static final double MIN_ARRIVAL_THROTTLE = 0.2;
    /** Braking distance in blocks per (block/tick) of speed, plus a floor. */
    public static final double BRAKE_BLOCKS_PER_SPEED = 30.0;
    public static final double MIN_BRAKE_DISTANCE = 4.0;

    private BoatThrottle() {
    }

    /** Throttle for steering with the given absolute heading error. */
    public static double turnFactor(double headingErrorDeg) {
        double e = Math.abs(headingErrorDeg);
        if (e <= STRAIGHT_DEG) {
            return 1.0;
        }
        if (e >= SHARP_DEG) {
            return MIN_TURN_THROTTLE;
        }
        double t = (e - STRAIGHT_DEG) / (SHARP_DEG - STRAIGHT_DEG);
        return 1.0 - t * (1.0 - MIN_TURN_THROTTLE);
    }

    /** Braking distance before a corner at the given speed (blocks per tick). */
    public static double brakeDistance(double speed) {
        return Math.max(MIN_BRAKE_DISTANCE, speed * BRAKE_BLOCKS_PER_SPEED);
    }

    /**
     * @param headingErrorDeg   current heading error
     * @param distToCorner      blocks to the next route corner (Double.POSITIVE_INFINITY when none)
     * @param cornerTurnDeg     turn angle at that corner
     * @param distToDestination blocks to the final stop
     * @param speed             current speed in blocks per tick
     */
    public static double throttle(double headingErrorDeg, double distToCorner, double cornerTurnDeg,
                                  double distToDestination, double speed) {
        double f = turnFactor(headingErrorDeg);
        double brake = brakeDistance(speed);
        if (distToCorner < brake) {
            double cornerFactor = turnFactor(cornerTurnDeg);
            double t = Math.max(0.0, distToCorner) / brake;
            f = Math.min(f, cornerFactor + (1.0 - cornerFactor) * t);
        }
        if (distToDestination < ARRIVAL_SLOWDOWN) {
            double t = Math.max(0.0, distToDestination) / ARRIVAL_SLOWDOWN;
            f = Math.min(f, MIN_ARRIVAL_THROTTLE + (1.0 - MIN_ARRIVAL_THROTTLE) * t);
        }
        return f;
    }
}
