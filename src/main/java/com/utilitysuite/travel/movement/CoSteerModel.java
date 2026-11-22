package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.IInputSmoother;

/**
 * The planner's copy of the core's yaw co-steer follower ({@code core.input.YawCoSteer}) with a still mouse and full
 * authority: where the view will actually be on each tick when a plan asks for a heading.
 * <p>
 * The follower is smooth on purpose (no head snapping): its speed is the smallest of the level's rate, a critically
 * damped tail ({@link #OMEGA_PER_SEC} x the error left) and a braking limit, and it changes by at most
 * {@link #ACCEL_DEG_PER_S2}. A plan that assumes the view moves at the full rate toward a new heading is wrong by
 * several degrees for several ticks: a script rolled that way leaves its predicted line in the client (found in the
 * lane run of 2026-11-22). A script that turns the view is rolled with this model instead, one request per tick,
 * applied over the frames after that tick's physics.
 * <p>
 * Frames: the model integrates {@link #FRAMES_PER_TICK} equal frames per tick. The follower's braking term is exact in
 * discrete time, so other frame rates give nearly the same view (tested against the real follower at 40 to 160 fps).
 * Engine-free, allocation free. The constants must match {@code YawCoSteer}; {@code CoSteerModelTest} holds them to it.
 */
final class CoSteerModel {

    static final double ACCEL_DEG_PER_S2 = 1500.0;
    static final double OMEGA_PER_SEC = 8.0;
    static final double ON_TARGET_DEG = 0.01;
    static final int FRAMES_PER_TICK = 3;
    private static final double TICK_SECONDS = 0.05;

    private double velocity;
    private double total;

    void reset() {
        velocity = 0.0;
        total = 0.0;
    }

    /** Degrees added since {@link #reset}. */
    double total() {
        return total;
    }

    /** A tick without a request: the follower lapses and stands still. */
    void lapse() {
        velocity = 0.0;
    }

    /**
     * The frames of one tick toward {@code ideal}.
     *
     * @param yaw            the view before the frames (the yaw this tick's physics used)
     * @param rateDegPerSec  the level's co-steer rate
     * @param maxTotalDeg    the level's allowance since {@link #reset}
     * @return the view after the frames (the yaw the next tick's physics will use)
     */
    float tick(float yaw, float ideal, double rateDegPerSec, double maxTotalDeg) {
        if (!Float.isFinite(ideal) || !(rateDegPerSec > 0.0) || !(maxTotalDeg > 0.0)) {
            lapse();
            return yaw;
        }
        double rate = Math.min(rateDegPerSec, IInputSmoother.MAX_TURN_DEG_PER_SEC);
        double dt = TICK_SECONDS / FRAMES_PER_TICK;
        for (int k = 0; k < FRAMES_PER_TICK; k++) {
            yaw += (float) frame(yaw, ideal, rate, maxTotalDeg, dt);
        }
        return yaw;
    }

    private double frame(float yaw, float ideal, double rate, double maxTotal, double dt) {
        double err = wrap((double) ideal - yaw);
        double absErr = Math.abs(err);
        double left = maxTotal - total;
        if (left <= 0.0) {
            velocity = 0.0;
            return 0.0;
        }
        if (absErr <= ON_TARGET_DEG) {
            double maxDv = ACCEL_DEG_PER_S2 * dt;
            velocity = Math.abs(velocity) <= maxDv ? 0.0 : velocity - Math.signum(velocity) * maxDv;
            return 0.0;
        }
        double reach = Math.min(absErr, left);
        double brake = ACCEL_DEG_PER_S2 * (Math.sqrt(dt * dt * 0.25 + 2.0 * reach / ACCEL_DEG_PER_S2) - dt * 0.5);
        double targetSpeed = Math.min(rate, Math.min(OMEGA_PER_SEC * reach, brake));
        double target = err > 0.0 ? targetSpeed : -targetSpeed;
        double dv = target - velocity;
        double maxDv = ACCEL_DEG_PER_S2 * dt;
        velocity += dv > maxDv ? maxDv : (dv < -maxDv ? -maxDv : dv);
        double step = velocity * dt;
        if ((step > 0.0) != (err > 0.0)) {
            velocity = 0.0;
            return 0.0;
        }
        double abs = Math.abs(step);
        double cap = IInputSmoother.MAX_TURN_DEG_PER_SEC * dt;
        if (abs > absErr) {
            abs = absErr;
        }
        if (abs > left) {
            abs = left;
        }
        if (abs > cap) {
            abs = cap;
        }
        total += abs;
        return err > 0.0 ? abs : -abs;
    }

    private static double wrap(double d) {
        d = d % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
