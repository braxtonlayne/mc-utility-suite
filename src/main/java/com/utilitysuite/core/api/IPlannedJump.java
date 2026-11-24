package com.utilitysuite.core.api;

/**
 * The movement layer's jump of this tick and the run-up to a verified planned jump, for edge guards (SafeWalk, bridge,
 * autopilot) that must let a valid jump go first: a guard only holds an edge no valid jump uses.
 * <p>
 * A planned takeoff may come on the tick after the body stepped past the edge (vanilla resolves Y before X/Z, so the
 * step-off tick still ends on the ground and the next tick can jump): a guard that clamps the step-off move ruins the
 * jump. Plans are only reported when they land without fall damage while SafeWalk is on (the user's own jump press is
 * not a plan and never needs this). Both values are set in {@code MovementInputUpdateEvent} and hold for the move of
 * the same tick. Client thread only, allocation free.
 */
public interface IPlannedJump {

    /** A planned takeoff at most this many ticks away counts as the run-up (the step-off tick plus a little jitter). */
    int RUN_UP_TICKS = 3;

    /** No movement layer: never jumping, never running up. */
    IPlannedJump NONE = new IPlannedJump() {
        @Override
        public boolean jumpingThisTick() {
            return false;
        }

        @Override
        public int ticksToPlannedTakeoff() {
            return -1;
        }
    };

    /** The movement layer presses jump on this tick. */
    boolean jumpingThisTick();

    /**
     * Ticks from this tick to the takeoff of the verified plan the movement layer is committed to and driving
     * (0 = this tick), or -1 without one.
     */
    int ticksToPlannedTakeoff();

    /** Running up to a planned jump: its takeoff is at most {@link #RUN_UP_TICKS} ticks away. */
    default boolean runningUpToPlannedJump() {
        int t = ticksToPlannedTakeoff();
        return t >= 0 && t <= RUN_UP_TICKS;
    }
}
