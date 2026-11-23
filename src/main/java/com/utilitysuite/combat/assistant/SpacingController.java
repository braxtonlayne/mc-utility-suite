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

    /**
     * Horizontal centre distance below which stepping in only crowds the opponent (blocks). Inside it the bearing to
     * an opponent strafing at sprint speed swings faster than a speed-capped camera turns (about 200 deg/s at 1.5
     * blocks), so the crosshair falls off and the fighters run through each other. Arena benchmark, 2026-11-23: two
     * thirds of the charged-but-held ticks were within 1.5 blocks, yaw 30-100 degrees behind, with W held.
     */
    public static final double CROWD_DISTANCE = 1.6;
    /** Below this the opponent is inside our model: step back, not just stop. */
    public static final double OVERLAP_DISTANCE = 1.0;

    /**
     * The don't-crowd guard over any planned step: no forward step inside {@link #CROWD_DISTANCE}, and a step back
     * inside {@link #OVERLAP_DISTANCE} (unless a hazard is behind). Stateless, allocation free.
     */
    public static int uncrowd(int forward, double distance, boolean hazardBack) {
        if (distance < OVERLAP_DISTANCE) {
            return hazardBack ? HOLD : BACK;
        }
        if (distance < CROWD_DISTANCE && forward > 0) {
            return HOLD;
        }
        return forward;
    }

    /** Stateless band check shared with the coach: -1 too close, 0 inside the band, 1 too far. */
    public static int classify(double distance, double min, double max) {
        if (distance < min) {
            return -1;
        }
        return distance > max ? 1 : 0;
    }
}
