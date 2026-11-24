package com.utilitysuite.jobs.baritone;

import com.utilitysuite.core.api.IRoutePlanner.PlanState;

import java.util.List;
import java.util.function.LongPredicate;

/**
 * The narrow set of pathing calls the adapter needs, free of Baritone types so {@link BaritoneAdapter} can be loaded
 * when Baritone is absent. Implemented only by {@code BaritoneBridge}.
 */
interface PathBackend {

    /** Mines any of the blocks with loaded-chunk scanning, no quantity limit (the executor counts yield). */
    void mine(List<String> blockIds);

    /** Paths to within {@code range} blocks (0 = exact block). */
    void goTo(int x, int y, int z, int range);

    /** Paths next to a block so it can be reached (for interaction). */
    void goToBlock(int x, int y, int z);

    void cancel();

    boolean isMining();

    /** Custom goal process still owns pathing. */
    boolean isGoalActive();

    /** Player feet are inside the current custom goal. */
    boolean isInGoal();

    boolean isPathing();

    /**
     * First packed position of the current path (feet or head block) matching the test, or {@link Long#MIN_VALUE}.
     * Used to keep routes out of protected areas.
     */
    long firstPathPos(LongPredicate test);

    /**
     * Identity of the current path executor (null when none). A different reference means a new path started; the
     * adapter compares it by reference every tick so a new route is checked on its first tick. Must not allocate.
     */
    default Object currentPathToken() {
        return null;
    }

    /**
     * Legit mining: only ore the player could see, strip-mining at {@code yLevel} to find more. Off: the backend's
     * loaded-chunk scan targets ore anywhere.
     */
    void setLegitMining(boolean legit, int yLevel);

    /** Digs out the box between the corners (inclusive). */
    void clearArea(int x1, int y1, int z1, int x2, int y2, int z2);

    /** A {@link #clearArea} is still running. */
    boolean isBuilding();

    /** Applies suite-wide Baritone settings (quiet chat, auto-tool on). */
    void configure();

    // ---- plan-only routes (the library plans, the suite drives) ----------------------------------------------------
    // The defaults describe a backend without plan support (never READY), so test fakes override only what they use.

    /**
     * Starts computing a route to within {@code range} blocks (0 = exact block) without executing it: the library
     * never moves, turns, breaks or places for a plan. Replaces a plan that is still running.
     */
    default void planTo(int x, int y, int z, int range) {
    }

    /** Starts computing a route that ends at least {@code distance} blocks away from the position (fleeing). */
    default void planAway(int x, int y, int z, int distance) {
    }

    default PlanState planState() {
        return PlanState.NONE;
    }

    /** Bumped every time a new route becomes READY. */
    default int planSerial() {
        return 0;
    }

    /**
     * Copies the READY route as packed feet positions ({@code BlockPos.asLong}), start first. Allocation free.
     *
     * @return positions written (at most {@code out.length}); 0 when no route is READY
     */
    default int copyPlannedPath(long[] out) {
        return 0;
    }

    /** Drops the plan request and the route. Never cancels a path the library is executing for a job. */
    default void cancelPlan() {
    }
}
