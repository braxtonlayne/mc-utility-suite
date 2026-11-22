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

    /**
     * Same as {@link #update(boolean, boolean, int, double)}, with a direction to prefer right now because it moves
     * WITH the opponent ({@link #mirrorOf}): circling the same way round keeps the opponent near the crosshair, so
     * the turn rate the aim needs drops. {@code withStrength} 0..1 is how sure the read is; 0 or
     * {@code withDir == NONE} is the 4-argument behaviour. Hazards still win.
     * <p>
     * CONTRACT STUB: the signature is fixed for the volley; the preference is filled in by the anticipatory-aim task
     * (until then {@code withDir} is ignored).
     */
    public int update(boolean hazardLeft, boolean hazardRight, int nextInterval, double opponentBias, int withDir,
                      double withStrength) {
        return update(hazardLeft, hazardRight, nextInterval, opponentBias);
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
    }
}
