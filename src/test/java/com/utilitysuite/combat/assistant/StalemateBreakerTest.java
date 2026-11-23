package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.StalemateSearch;
import com.utilitysuite.combat.tactics.StalemateWorlds;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IRouteRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Escalation order, timeouts and hand-backs of the stalemate breaker, engine free. */
class StalemateBreakerTest {

    static final int STALL = 80;

    /** Records the routes handed over; the test sets the run's state. */
    static final class FakeRunner implements StalemateBreaker.Runner {
        boolean accept = true;
        IRouteRunner.State state = IRouteRunner.State.IDLE;
        int follows;
        int stops;
        long lastEnd;
        int lastLength;

        @Override
        public boolean follow(long[] cells, int count) {
            if (!accept) {
                return false;
            }
            follows++;
            lastEnd = cells[count - 1];
            lastLength = count;
            state = IRouteRunner.State.RUNNING;
            return true;
        }

        @Override
        public IRouteRunner.State state() {
            return state;
        }

        @Override
        public void stop() {
            stops++;
            state = IRouteRunner.State.IDLE;
        }
    }

    final StalemateBreaker b = new StalemateBreaker();
    final FakeRunner runner = new FakeRunner();
    final CombatSnapshot s = new CombatSnapshot();
    int lastAct;

    StalemateBreakerTest() {
        s.health = 20f;
        s.maxHealth = 20f;
        s.targetHealth = 20f;
    }

    void place(double px, double py, double pz, double tx, double ty, double tz) {
        s.px = px;
        s.py = py;
        s.pz = pz;
        s.tx = tx;
        s.ty = ty;
        s.tz = tz;
    }

    int tick(CombatVoxels w) {
        b.observe(s, false);
        lastAct = b.drive(s, w, runner, true, STALL);
        return lastAct;
    }

    /** Ticks until the phase is {@code p} (or fails after {@code max} ticks). */
    int until(CombatVoxels w, StalemateBreaker.Phase p, int max) {
        for (int t = 1; t <= max; t++) {
            tick(w);
            if (b.phase() == p) {
                return t;
            }
        }
        fail("phase " + p + " not reached in " + max + " ticks; at " + b.phase());
        return -1;
    }

    @Test
    void inTheTrenchWithTheTargetAboveItRoutesOutByTheStairsInsteadOfPlayingPeekaboo() {
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        for (int t = 0; t < STALL - 1; t++) {
            assertEquals(StalemateBreaker.ACT_NONE, tick(w), "the normal fight runs until the stall time");
        }
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
        tick(w);
        assertEquals(StalemateBreaker.Phase.SEARCH, b.phase(), "stall detected at " + STALL + " ticks");
        assertEquals(StalemateBreaker.Phase.SEARCH, b.notePhase());
        int t = until(w, StalemateBreaker.Phase.APPROACH, 40);
        assertTrue(t < 20, "the search is quick: " + t + " ticks");
        assertEquals(StalemateBreaker.ACT_ROUTE, lastAct);
        assertEquals(1, runner.follows);
        assertTrue(Math.abs(StalemateSearch.unpackY(runner.lastEnd) - 64) <= 1, "the route ends at its level");
        assertEquals(StalemateBreaker.Phase.APPROACH, b.notePhase());
        assertTrue(b.noteDy() > StalemateDetector.JUMP_RISE, "routing up");
        assertFalse(b.noteFall());
        // walking: the runner drives, the breaker keeps answering ROUTE
        for (int i = 0; i < 30; i++) {
            assertEquals(StalemateBreaker.ACT_ROUTE, tick(w));
        }
        // at its level within reach: hand back to the normal fight
        place(6.5, 64, 2.0, 7.5, 64, 0.5);
        tick(w);
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
        assertEquals(StalemateBreaker.ACT_NONE, lastAct);
        assertEquals(1, b.breaks());
        assertTrue(runner.stops >= 1);
    }

    @Test
    void theReverseLayoutRoutesDownTheStairsAndArrivalHandsBack() {
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(8.4, 64, 0.6, 9.3, 56, 0.3); // the live finding: on top, the target 8 straight below
        until(w, StalemateBreaker.Phase.APPROACH, STALL + 40);
        assertEquals(56, StalemateSearch.unpackY(runner.lastEnd));
        assertTrue(b.noteDy() < -StalemateDetector.SAFE_DROP, "routing down");
        runner.state = IRouteRunner.State.ARRIVED;
        tick(w);
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase(), "arrived: the normal fight takes over");
    }

    @Test
    void noStairsButHealthToSpareDropsDownASurvivableFall() {
        CombatVoxels w = StalemateWorlds.ravine(false);
        place(8.4, 64, 0.6, 9.3, 56, 0.3);
        until(w, StalemateBreaker.Phase.APPROACH, STALL + 60);
        assertTrue(b.noteFall(), "the fall is the approach");
        assertEquals(56, StalemateSearch.unpackY(runner.lastEnd));
    }

    @Test
    void noStairsAndLowHealthNeverTakesTheFall() {
        CombatVoxels w = StalemateWorlds.ravine(false);
        s.health = 12f; // 2 to spare over the reserve: a 5-block drop at most, the ravine is 8
        place(8.4, 64, 0.6, 9.3, 56, 0.3);
        for (int t = 0; t < STALL + 200; t++) {
            tick(w);
            assertNotEquals(StalemateBreaker.Phase.APPROACH, b.phase());
        }
        assertEquals(5, StalemateBreaker.survivableDrop(s));
    }

    @Test
    void escalatesApproachThenMeetThenBaitThenHoldsOffEachOnItsTimeout() {
        CombatVoxels w = StalemateWorlds.pillarAndLedge();
        // we are on the ledge (3 up), it is on the pillar (2 up): we cannot climb to it, it will not come
        place(9.5, 67, 0.5, 0.5, 66, 0.5);
        until(w, StalemateBreaker.Phase.MEET, STALL + 60); // the approach search finds nothing: straight to MEET
        assertEquals(1, runner.follows);
        assertEquals(64, StalemateSearch.unpackY(runner.lastEnd), "down to the ground it can reach");
        assertEquals(StalemateBreaker.ACT_ROUTE, lastAct);
        // the runner gets us there: hold still facing it, and wait
        runner.state = IRouteRunner.State.ARRIVED;
        place(StalemateSearch.unpackX(runner.lastEnd) + 0.5, 64, StalemateSearch.unpackZ(runner.lastEnd) + 0.5,
                0.5, 66, 0.5);
        assertEquals(StalemateBreaker.ACT_HOLD, tick(w));
        int held = 1;
        while (b.phase() == StalemateBreaker.Phase.MEET) {
            tick(w);
            held++;
            assertTrue(held <= StalemateBreaker.MEET_TIMEOUT_TICKS + 2, "the wait is bounded");
        }
        assertTrue(held > StalemateBreaker.MEET_TIMEOUT_TICKS / 2, "it waited: " + held);
        until(w, StalemateBreaker.Phase.BAIT, 40);
        assertEquals(2, runner.follows);
        runner.state = IRouteRunner.State.ARRIVED;
        int baitTicks = 0;
        while (b.phase() == StalemateBreaker.Phase.BAIT) {
            tick(w);
            baitTicks++;
            assertTrue(baitTicks <= StalemateBreaker.BAIT_TIMEOUT_TICKS + 2);
        }
        assertEquals(StalemateBreaker.Phase.HOLD_OFF, b.phase(), "nothing left: the normal fight runs a while");
        assertEquals(StalemateBreaker.Phase.HOLD_OFF, b.notePhase());
        for (int t = 0; t < StalemateBreaker.HOLD_OFF_TICKS; t++) {
            assertEquals(StalemateBreaker.ACT_NONE, tick(w));
        }
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
    }

    @Test
    void aMeetEndsWhenItWalksIn() {
        CombatVoxels w = StalemateWorlds.pillarAndLedge();
        place(9.5, 67, 0.5, 0.5, 66, 0.5);
        until(w, StalemateBreaker.Phase.MEET, STALL + 60);
        runner.state = IRouteRunner.State.ARRIVED;
        place(5.5, 64, 0.5, 0.5, 66, 0.5);
        tick(w);
        for (int t = 0; t < 60 && b.phase() == StalemateBreaker.Phase.MEET; t++) {
            s.tx += 0.1; // it comes down and walks in
            s.ty = 64;
            s.tdx = 0.2;
            tick(w);
        }
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
    }

    @Test
    void anApproachThatTimesOutMovesOnAndARouteFailureReplansFirst() {
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        until(w, StalemateBreaker.Phase.APPROACH, STALL + 40);
        runner.state = IRouteRunner.State.FAILED; // knocked off the route
        tick(w);
        assertEquals(StalemateBreaker.Phase.SEARCH, b.phase(), "re-plans the same option from here");
        until(w, StalemateBreaker.Phase.APPROACH, 40);
        assertEquals(2, runner.follows);
        // the runner never arrives (stuck): the approach times out and the breaker moves on
        int t = 0;
        while (b.phase() == StalemateBreaker.Phase.APPROACH) {
            tick(w);
            assertTrue(++t <= StalemateBreaker.APPROACH_MAX_TICKS + 2);
        }
        assertNotEquals(StalemateBreaker.Phase.IDLE, b.phase(), "next option, not back to the start");
    }

    @Test
    void theApproachReplansWhenTheTargetMoves() {
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        until(w, StalemateBreaker.Phase.APPROACH, STALL + 40);
        long firstEnd = runner.lastEnd;
        s.tx = 4.5;
        s.tz = -8.5; // walked off toward the other end of the ravine
        for (int t = 0; t < StalemateBreaker.REPLAN_MIN_TICKS + 30; t++) {
            assertEquals(StalemateBreaker.ACT_ROUTE, tick(w), "the held route runs while the new one is planned");
        }
        assertEquals(2, runner.follows);
        assertNotEquals(firstEnd, runner.lastEnd);
        assertTrue(StalemateSearch.unpackZ(runner.lastEnd) < 0, "toward where it went");
    }

    @Test
    void aHitEitherWayEndsItAndBusyAborts() {
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        until(w, StalemateBreaker.Phase.APPROACH, STALL + 40);
        s.health = 15f; // an arrow from above
        tick(w);
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
        assertEquals(StalemateBreaker.ACT_NONE, lastAct);

        until(w, StalemateBreaker.Phase.APPROACH, STALL + 40); // the stall comes back; a hit resets the memory
        b.abort(runner); // the assistant aborts when it eats or backs off
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
        assertEquals(IRouteRunner.State.IDLE, runner.state);
    }

    @Test
    void aStallThatComesBackRightAfterAnArrivalStartsAtTheNextOption() {
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        until(w, StalemateBreaker.Phase.APPROACH, STALL + 40);
        runner.state = IRouteRunner.State.ARRIVED;
        tick(w);
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
        // ...but it is back in the trench with us gone up, no hit in between: same stall again
        runner.state = IRouteRunner.State.IDLE;
        for (int t = 0; t < STALL + 60 && b.phase() != StalemateBreaker.Phase.APPROACH
                && b.phase() != StalemateBreaker.Phase.HOLD_OFF && b.phase() != StalemateBreaker.Phase.BAIT; t++) {
            tick(w);
        }
        assertNotEquals(StalemateBreaker.Phase.APPROACH, b.phase(), "not the same approach again");
        assertTrue(b.option() > 0 || b.phase() == StalemateBreaker.Phase.HOLD_OFF);
    }

    @Test
    void offWithoutVoxelsOrWithoutARunnerItDoesNothing() {
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        for (int t = 0; t < STALL * 3; t++) {
            b.observe(s, false);
            assertEquals(StalemateBreaker.ACT_NONE, b.drive(s, null, runner, true, STALL));
            assertEquals(StalemateBreaker.ACT_NONE, b.drive(s, StalemateWorlds.ravine(true), runner, false, STALL));
        }
        assertEquals(StalemateBreaker.Phase.IDLE, b.phase());
        assertEquals(0, runner.follows);
    }

    @Test
    void aRunnerThatRefusesRoutesEndsInHoldOff() {
        runner.accept = false;
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        until(w, StalemateBreaker.Phase.HOLD_OFF, STALL + 200);
    }

    @Test
    void wholeEscalationCyclesAllocateNothingAfterTheFirstSearch() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        CombatVoxels w = StalemateWorlds.ravine(true);
        place(10.5, 56, 0.5, 7.5, 64, 0.5);
        until(w, StalemateBreaker.Phase.APPROACH, STALL + 40); // the search arrays are allocated once, here
        // detection, searches, approach, timeouts, the next options and the hold-off, over and over
        long bytes = AllocationProbe.measure(50_000, () -> {
            b.observe(s, false);
            b.drive(s, w, runner, true, STALL);
        });
        assertTrue(b.breaks() == 0 && runner.follows > 2, "it cycled through the options");
        assertTrue(bytes < 1024, "breaker ticks allocated " + bytes + " bytes");
    }
}
