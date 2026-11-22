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

    /** A move-with read above this strength steers the direction. */
    public static final double WITH_THRESHOLD = 0.3;
    /** Minimum ticks in a direction before the move-with preference may switch away from it. */
    public static final int WITH_MIN_HOLD = 6;

    private int dir = RIGHT;
    private int ticksLeft;
    /** Ticks spent in the current direction (any change resets it). Does not affect the 3/4-argument output. */
    private int held;

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
        held++;
        if (ticksLeft <= 0) {
            dir = -dir;
            held = 0;
            ticksLeft = Math.max(1, shaped(nextInterval, dir, opponentBias));
        }
        ticksLeft--;
        return hazardOverride(hazardLeft, hazardRight, nextInterval);
    }

    /**
     * Same as {@link #update(boolean, boolean, int, double)}, with a direction to prefer right now because it moves
     * WITH the opponent ({@link #mirrorOf}): circling the same way round keeps the opponent near the crosshair, so
     * the turn rate the aim needs drops. {@code withStrength} 0..1 is how sure the read is; 0 or
     * {@code withDir == NONE} is the 4-argument behaviour. Hazards still win.
     * <p>
     * Above {@link #WITH_THRESHOLD}: already moving with the opponent, an interval boundary extends the run instead of
     * reversing (intervals stretched up to 1.5x by the strength); moving against it, the planner switches at the next
     * boundary or as soon as the current direction has been held {@link #WITH_MIN_HOLD} ticks, so the preference never
     * changes direction faster than that. A hazard on the preferred side disables the preference for that tick.
     */
    public int update(boolean hazardLeft, boolean hazardRight, int nextInterval, double opponentBias, int withDir,
                      double withStrength) {
        if (withDir == NONE || !(withStrength > WITH_THRESHOLD)) {
            return update(hazardLeft, hazardRight, nextInterval, opponentBias);
        }
        boolean withBlocked = withDir == LEFT ? hazardLeft : hazardRight;
        if (withBlocked) {
            return update(hazardLeft, hazardRight, nextInterval, opponentBias);
        }
        held++;
        if (dir == withDir) {
            if (ticksLeft <= 0) {
                ticksLeft = stretched(nextInterval, opponentBias, withStrength); // keep going with it
            }
        } else if (ticksLeft <= 0 || held >= WITH_MIN_HOLD) {
            dir = withDir;
            held = 0;
            ticksLeft = stretched(nextInterval, opponentBias, withStrength);
        }
        ticksLeft--;
        return hazardOverride(hazardLeft, hazardRight, nextInterval);
    }

    private int hazardOverride(boolean hazardLeft, boolean hazardRight, int nextInterval) {
        if (dir == LEFT && hazardLeft) {
            dir = RIGHT;
            held = 0;
            ticksLeft = Math.max(1, nextInterval);
        } else if (dir == RIGHT && hazardRight) {
            dir = LEFT;
            held = 0;
            ticksLeft = Math.max(1, nextInterval);
        }
        return dir;
    }

    private int stretched(int nextInterval, double opponentBias, double withStrength) {
        double k = 1.0 + 0.5 * Math.min(1.0, withStrength);
        return Math.max(1, (int) Math.round(shaped(nextInterval, dir, opponentBias) * k));
    }

    /**
     * Our strafe direction that moves with an opponent whose lateral sign is {@code opponentLateralSign}
     * ({@code OpponentModel} / {@code StrafePhaseModel} convention: + = to its own right while facing us, which is
     * our left): {@link #LEFT} for +, {@link #RIGHT} for -, {@link #NONE} for 0.
     */
    public static int mirrorOf(int opponentLateralSign) {
        return opponentLateralSign > 0 ? LEFT : (opponentLateralSign < 0 ? RIGHT : NONE);
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
        held = 0;
    }
}
