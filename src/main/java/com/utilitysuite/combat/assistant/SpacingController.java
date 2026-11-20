package com.utilitysuite.combat.assistant;

/**
 * Keeps the horizontal distance to the target inside a band (default 2.7-3.1 blocks) with hysteresis: once outside the
 * band it moves until the band's middle is reached, so it does not jitter at the edges. Never steps toward a hazard.
 * Allocation free.
 */
public final class SpacingController {

    public static final int BACK = -1;
    public static final int HOLD = 0;
    public static final int FORWARD = 1;

    private int mode = HOLD;

    /** @return {@link #FORWARD}, {@link #BACK} or {@link #HOLD} */
    public int update(double distance, double min, double max, boolean hazardFront, boolean hazardBack) {
        double mid = (min + max) * 0.5;
        if (distance > max) {
            mode = FORWARD;
        } else if (distance < min) {
            mode = BACK;
        } else if (mode == FORWARD && distance <= mid) {
            mode = HOLD;
        } else if (mode == BACK && distance >= mid) {
            mode = HOLD;
        }
        int out = mode;
        if (out == FORWARD && hazardFront) {
            out = HOLD;
        } else if (out == BACK && hazardBack) {
            out = HOLD;
        }
        return out;
    }

    public void reset() {
        mode = HOLD;
    }

    /** Stateless band check shared with the coach: -1 too close, 0 inside the band, 1 too far. */
    public static int classify(double distance, double min, double max) {
        if (distance < min) {
            return -1;
        }
        return distance > max ? 1 : 0;
    }
}
