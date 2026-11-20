package com.utilitysuite.travel.autopilot;

/**
 * Turns a throttle in [0, 1] into a per-tick on/off decision whose average equals the throttle (error-diffusion), so an
 * on/off key approximates a continuous speed without jitter patterns.
 */
public final class DutyCycle {

    private double accumulator;

    /** True when the key should be held this tick. */
    public boolean next(double throttle) {
        double t = throttle < 0 ? 0 : (throttle > 1 ? 1 : throttle);
        accumulator += t;
        if (accumulator >= 1.0 - 1.0e-9) {
            accumulator -= 1.0;
            return true;
        }
        return false;
    }

    public void reset() {
        accumulator = 0.0;
    }
}
