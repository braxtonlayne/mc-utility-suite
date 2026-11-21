package com.utilitysuite.combat.assistant;

/**
 * Lateral strafing around the target. Alternates direction after a caller-supplied (randomised) interval and inverts
 * immediately when the side it is moving toward has a hazard (cliff, lava, fire). With hazards on both sides it stops
 * strafing. Allocation free.
 */
public final class StrafePlanner {

    public static final int LEFT = -1;
    public static final int NONE = 0;
    public static final int RIGHT = 1;

    private int dir = RIGHT;
    private int ticksLeft;

    /**
     * @param nextInterval ticks until the next voluntary direction change, used whenever a new interval starts
     * @return {@link #LEFT}, {@link #RIGHT} or {@link #NONE}
     */
    public int update(boolean hazardLeft, boolean hazardRight, int nextInterval) {
        return update(hazardLeft, hazardRight, nextInterval, 0.0);
    }

    /** An opponent strafe bias beyond this share starts to shape the strafe intervals. */
    public static final double BIAS_THRESHOLD = 0.4;

    /**
     * Same as {@link #update(boolean, boolean, int)}, shaped by the opponent's strafe bias
     * ({@code OpponentModel.strafeBias}, + = to its own right). Facing each other, both strafing to their own right
     * orbits the pair without giving up the angle, so intervals in the mirrored direction run up to 1.5x longer and the
     * others shorter. Bias 0 is the plain alternation.
     */
    public int update(boolean hazardLeft, boolean hazardRight, int nextInterval, double opponentBias) {
        if (hazardLeft && hazardRight) {
            return NONE;
        }
        if (ticksLeft <= 0) {
            dir = -dir;
            ticksLeft = Math.max(1, shaped(nextInterval, dir, opponentBias));
        }
        ticksLeft--;
        if (dir == LEFT && hazardLeft) {
            dir = RIGHT;
            ticksLeft = Math.max(1, nextInterval);
        } else if (dir == RIGHT && hazardRight) {
            dir = LEFT;
            ticksLeft = Math.max(1, nextInterval);
        }
        return dir;
    }

    static int shaped(int interval, int dir, double bias) {
        double b = Math.max(-1.0, Math.min(1.0, bias));
        if (Math.abs(b) < BIAS_THRESHOLD) {
            return interval;
        }
        int preferred = b > 0 ? RIGHT : LEFT;
        double k = (Math.abs(b) - BIAS_THRESHOLD) / (1.0 - BIAS_THRESHOLD) * 0.5;
        return (int) Math.round(interval * (dir == preferred ? 1.0 + k : 1.0 - k));
    }

    public int direction() {
        return dir;
    }

    public void reset() {
        dir = RIGHT;
        ticksLeft = 0;
    }
}
