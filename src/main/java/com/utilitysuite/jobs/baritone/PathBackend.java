package com.utilitysuite.jobs.baritone;

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

    /** Applies suite-wide Baritone settings (quiet chat, auto-tool on). */
    void configure();
}
