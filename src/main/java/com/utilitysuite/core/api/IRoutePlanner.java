package com.utilitysuite.core.api;

/**
 * Plan-only pathing: asks the path library for a route without letting it move the player. The caller walks the
 * returned corridor itself (see {@link IRouteRunner}). One plan at a time; a new request replaces the previous one.
 * No path-library type leaks through this interface. Client thread only.
 */
public interface IRoutePlanner {

    enum PlanState {
        /** No request, or the last one was cancelled. */
        NONE,
        /** The search is still running (it runs off-thread inside the path library). */
        CALCULATING,
        /** A route is ready for {@link #copyPath}. It may be partial: it ends at the best node found so far. */
        READY,
        /** No route was found. */
        FAILED
    }

    /** False when the path library is absent or busy executing a path of its own (a job, travel). */
    boolean isAvailable();

    /**
     * Starts planning a route from the player to within {@code range} blocks of the position.
     *
     * @return false when unavailable (nothing was started)
     */
    boolean planTo(int x, int y, int z, int range);

    /**
     * Starts planning a route that ends at least {@code distance} blocks away from the position (fleeing).
     *
     * @return false when unavailable
     */
    boolean planAway(int x, int y, int z, int distance);

    PlanState state();

    /** Bumped every time a new route becomes READY, so callers can tell a fresh route from the one they hold. */
    int planSerial();

    /**
     * Copies the READY route as packed feet positions ({@code BlockPos.asLong} layout), start first.
     * Allocation free.
     *
     * @return the number of positions written (at most {@code out.length}); 0 when no route is ready
     */
    int copyPath(long[] out);

    /** Drops the request and the route. Never touches a path the library is executing for someone else. */
    void cancelPlan();
}
