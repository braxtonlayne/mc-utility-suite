package com.utilitysuite.core.api;

/**
 * Pathing as seen by modules other than the goal engine (travel autopilot, schematic build, waypoints). Implemented by
 * the Baritone adapter; no Baritone type leaks through this interface. Client thread only.
 */
public interface INavigator {

    boolean isAvailable();

    /** Paths to within {@code range} blocks of the position. */
    void goTo(int x, int y, int z, int range);

    /** Follows the given packed positions in order (e.g. breadcrumbs). */
    void followPath(long[] packedPositions);

    NavigationStatus status();

    void pause();

    void resume();

    void cancel();
}
