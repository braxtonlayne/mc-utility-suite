package com.utilitysuite.core.api;

/**
 * Runs the player along a planned route like a person would: corners cut, flats sprint-jumped, gaps jumped. The route
 * comes from {@link IRoutePlanner}; inside short range the runner plans locally. Used for pursuit and fleeing.
 * <p>
 * The caller owns the {@link ControlLease} (MOVEMENT, plus CAMERA when the runner should also look along the route)
 * and passes it with every call; the runner drives keys and turns only through {@link IInputSmoother} with that lease,
 * so manual input, preemption and Stop-All end the run like any other automation. One run at a time. Client thread only.
 */
public interface IRouteRunner {

    enum State {
        IDLE,
        /** Waiting for a route; the runner already moves on its local plan when it has one. */
        PLANNING,
        RUNNING,
        /** Inside the stop range (pursuit) or beyond the wanted distance (flee). */
        ARRIVED,
        /** No route, or the lease ended. */
        FAILED
    }

    /** False when neither a route planner nor a local plan can serve a run right now. */
    boolean isAvailable();

    /**
     * Starts or retargets a pursuit toward a (moving) position; call again whenever the target moved. Cheap when the
     * target barely moved: the route is only replanned on a cadence or when the target left it. Allocation free.
     *
     * @param stopRange      horizontal distance at which the run is ARRIVED and the keys are released
     * @param lookAlongRoute also turn the camera along the route (needs CAMERA on the lease); false = keys only
     * @return false when the run could not start (unavailable or the lease does not cover MOVEMENT)
     */
    boolean pursue(ControlLease lease, double x, double y, double z, double stopRange, boolean lookAlongRoute);

    /** Starts or retargets a run away from the position until at least {@code minDistance} blocks away. */
    boolean flee(ControlLease lease, double fromX, double fromY, double fromZ, double minDistance,
                 boolean lookAlongRoute);

    /**
     * Starts or replaces a run along a route the caller planned itself (packed feet cells, {@code BlockPos.asLong}
     * layout, start first; copied). The runner walks it like a planned route (corners cut, steps jumped, edge guard)
     * without asking a planner; ARRIVED once the last cell is reached, FAILED when the body leaves the route. Used by
     * combat's stalemate breaker, whose own walking search knows the cells (e.g. up a ravine's stairs).
     * Allocation free.
     *
     * @return false when the run could not start (unavailable, an empty route, or the lease does not cover MOVEMENT);
     * the default implementation supports no caller routes
     */
    default boolean follow(ControlLease lease, long[] cells, int count, boolean lookAlongRoute) {
        return false;
    }

    /** Ends the run and releases the keys the runner holds for this lease. No-op for another lease. */
    void stop(ControlLease lease);

    State state();

    /** True while a run is PLANNING or RUNNING for this lease. */
    boolean isRunning(ControlLease lease);
}
