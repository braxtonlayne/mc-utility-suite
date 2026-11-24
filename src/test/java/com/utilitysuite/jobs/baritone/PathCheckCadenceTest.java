package com.utilitysuite.jobs.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The protected-area path check runs on a new path executor's first tick, not only on the 10-tick poll. */
class PathCheckCadenceTest {

    @Test
    void newExecutorIsInspectedImmediatelyAndOnlyOnce() {
        BaritoneAdapter a = new BaritoneAdapter((d, p) -> false, null);
        Object first = new Object();
        a.tick(); // ticks = 1, not a poll tick
        assertTrue(a.pathCheckDue(first), "new executor checked on its first tick");
        assertFalse(a.pathCheckDue(first), "same executor is not re-checked off-cadence");
        Object second = new Object();
        assertTrue(a.pathCheckDue(second), "replacement executor checked immediately");
    }

    @Test
    void noExecutorDoesNotTrigger() {
        BaritoneAdapter a = new BaritoneAdapter((d, p) -> false, null);
        a.tick();
        assertFalse(a.pathCheckDue(null));
    }

    @Test
    void pollStillRunsEveryTenthTick() {
        BaritoneAdapter a = new BaritoneAdapter((d, p) -> false, null);
        Object e = new Object();
        for (int i = 0; i < 9; i++) {
            a.tick();
        }
        a.pathCheckDue(e);
        a.tick(); // tick 10
        assertTrue(a.pathCheckDue(e));
    }
}
