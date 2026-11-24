package com.utilitysuite.travel.movement;

/**
 * Plans jumps that constant air keys cannot make: around a corner, a neo around a wall, under a low ceiling, with
 * 45-degree momentum. {@link MovementAssistBrain} asks while ARMED on the ground with a locked, safe target and no
 * plain plan that lands; a returned {@link FlightScript} is replayed tick by tick.
 * <p>
 * Budgeted and allocation free: one call rolls at most {@link #MAX_SIM_TICKS_PER_CALL} replica ticks and keeps a
 * cursor, so a search spreads over the ticks of the approach and the best script so far is kept between calls for
 * the same target. Client thread only.
 */
public interface TechniqueSource {

    int MAX_SIM_TICKS_PER_CALL = 600;

    /** Never finds a plan. */
    TechniqueSource NONE = new TechniqueSource() {
        private final FlightScript empty = new FlightScript();

        @Override
        public boolean plan(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                            SupportRegion target, boolean yawAllowed, float maxYawDegPerTick, boolean safeOnly,
                            float health, MovementPhysics.Voxels w) {
            return false;
        }

        @Override
        public FlightScript script() {
            return empty;
        }

        @Override
        public int lastSimTicks() {
            return 0;
        }

        @Override
        public void reset() {
        }
    };

    /**
     * One budgeted step of the search from the current body state toward the target top.
     *
     * @param now              the body (not modified)
     * @param userForward      the user's own forward axis; a script is only offered while the user holds forward
     *                         (letting go of W always stops)
     * @param target           the locked target's real top (valid, built by the caller)
     * @param yawAllowed       scripts may turn the view (co-steer; false on LEGIT and in Corrections)
     * @param maxYawDegPerTick view change per tick a script may ask for when {@code yawAllowed}
     * @param safeOnly         a script must land without costing health
     * @return true when {@link #script()} holds a verified script that starts at {@code now} (index 0 = next tick)
     */
    boolean plan(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                 SupportRegion target, boolean yawAllowed, float maxYawDegPerTick, boolean safeOnly, float health,
                 MovementPhysics.Voxels w);

    /** The best script of the last {@link #plan} call that returned true. Owned by the source; copy to keep. */
    FlightScript script();

    /** Replica ticks the last call rolled (at most {@link #MAX_SIM_TICKS_PER_CALL}). */
    int lastSimTicks();

    /** The most a script may turn the view from where it starts (degrees; the level's co-steer total). */
    /**
     * Share of the co-steer the commitment grants before the takeoff ({@code armed}) and in the air ({@code flight}):
     * the core's follower turns the view that much slower ({@link JumpCommit#authority()}).
     */
    default void setAuthority(float armed, float flight) {
    }

    default void setMaxTurn(float degrees) {
    }

    /**
     * The straight line to the target is blocked by a wall (the intent estimator's roll ran into one): the families
     * made for a way around are walked first.
     */
    default void setLineBlocked(boolean blocked) {
    }

    /** Forget the search (target changed, world change). */
    void reset();
}
