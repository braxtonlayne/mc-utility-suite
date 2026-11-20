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
        if (hazardLeft && hazardRight) {
            return NONE;
        }
        if (ticksLeft <= 0) {
            dir = -dir;
            ticksLeft = Math.max(1, nextInterval);
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

    public int direction() {
        return dir;
    }

    public void reset() {
        dir = RIGHT;
        ticksLeft = 0;
    }
}
