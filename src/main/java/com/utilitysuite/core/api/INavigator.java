package com.utilitysuite.core.api;

import java.util.Optional;

/**
 * Pathing as seen by modules other than the goal engine (travel autopilot, schematic build, waypoints). Implemented by
 * the Baritone adapter; no Baritone type leaks through this interface. Client thread only.
 * <p>
 * Modules should start runs with {@link #goToLeased} / {@link #followPathLeased}: the plain {@link #goTo} /
 * {@link #followPath} hold no {@link ControlLease}, so such a run would ignore manual input and higher-priority
 * automation. Stop-All cancels every navigator run regardless ({@link #cancelAll}).
 */
public interface INavigator {

    boolean isAvailable();

    /** Paths to within {@code range} blocks of the position. Unleased: prefer {@link #goToLeased}. */
    void goTo(int x, int y, int z, int range);

    /** Follows the given packed positions in order (e.g. breadcrumbs). Unleased: prefer {@link #followPathLeased}. */
    void followPath(long[] packedPositions);

    NavigationStatus status();

    void pause();

    void resume();

    void cancel();

    /**
     * Leased {@link #goTo}: requests MOVEMENT + CAMERA + WORLD_INTERACT at {@code priority} for {@code moduleId} (this
     * replaces that module's own current lease), then paths. The lease is released when the run ends; the path is
     * cancelled when the lease is preempted, overridden by the user or revoked by Stop-All. Empty when pathing is
     * unavailable or the lease is refused.
     */
    default Optional<NavigationRun> goToLeased(String moduleId, ControlPriority priority, int x, int y, int z,
                                               int range) {
        return LeasedNavigation.begin(Services.arbiter(), this, moduleId, priority, () -> goTo(x, y, z, range))
                .map(r -> r);
    }

    /** Leased {@link #followPath}; same lease semantics as {@link #goToLeased}. */
    default Optional<NavigationRun> followPathLeased(String moduleId, ControlPriority priority,
                                                     long[] packedPositions) {
        if (packedPositions == null || packedPositions.length == 0) {
            return Optional.empty();
        }
        return LeasedNavigation.begin(Services.arbiter(), this, moduleId, priority,
                () -> followPath(packedPositions)).map(r -> r);
    }

    /** Stops every pathing process and run, whoever started it (Stop-All). Defaults to {@link #cancel()}. */
    default void cancelAll() {
        cancel();
    }
}
