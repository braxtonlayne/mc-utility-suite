package com.utilitysuite.core.api;

/**
 * Starts a travel route to a destination from outside the travel package (e.g. the highway builder handing an ice
 * road over to the boat driver). Implemented by the travel autopilot, which keeps its own lease and safety rules; the
 * autopilot module must be switched on. Client thread only.
 */
public interface ITravel {

    enum RouteMode {
        /** Boat when riding one, elytra when wearing one, else overland. */
        AUTO,
        OVERLAND,
        BOAT,
        ELYTRA
    }

    /** Starts (or replaces) a route to the block position. False when it could not start (disabled, wrong vehicle). */
    boolean startRoute(int x, int y, int z, RouteMode mode);

    /** True while a route is running. */
    boolean isRouteRunning();

    /** Ends the running route, if any. */
    void stopRoute();
}
