package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.jobs.goal.ResumeHoldGate.Decision;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.jobs.goal.ResumeHoldGate.EXPLOSION;
import static com.utilitysuite.jobs.goal.ResumeHoldGate.LAVA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeHoldGateTest {

    @Test
    void holdsWhileHazardNearPlayer() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, EXPLOSION);
        for (long t = 1000; t < 1090; t++) {
            assertEquals(Decision.HOLD, g.decide(t, EXPLOSION, 0), "tick " + t);
        }
        assertTrue(g.pending());
        assertEquals(EXPLOSION, g.blockedBy());
    }

    @Test
    void lavaHoldsLikeAnExplosion() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, 0);
        assertEquals(Decision.HOLD, g.decide(0, LAVA, 0));
        assertEquals(LAVA, g.blockedBy());
        assertEquals(Decision.HOLD, g.decide(5, 0, 0));
        assertEquals(Decision.GO, g.decide(15, 0, 0));
    }

    @Test
    void goesAfterSettleOnceClear() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, EXPLOSION);
        assertEquals(Decision.HOLD, g.decide(5, EXPLOSION, 0));
        for (long t = 6; t < 16; t++) {
            assertEquals(Decision.HOLD, g.decide(t, 0, 0), "tick " + t);
        }
        assertEquals(Decision.GO, g.decide(16, 0, 0));
        assertFalse(g.pending());
        assertEquals(Decision.GO, g.decide(17, EXPLOSION, 0), "disarmed after GO");
    }

    @Test
    void defaultSettleIsInTheTwentyToFortyTickWindow() {
        assertTrue(ResumeHoldGate.DEFAULT_SETTLE_TICKS >= 20 && ResumeHoldGate.DEFAULT_SETTLE_TICKS <= 40);
        ResumeHoldGate g = new ResumeHoldGate();
        g.onPreempted(true, 0);
        int s = ResumeHoldGate.DEFAULT_SETTLE_TICKS;
        assertEquals(Decision.HOLD, g.decide(0, 0, 0), "an emergency escape with no hazard still settles");
        assertEquals(Decision.HOLD, g.decide(s - 1, 0, 0));
        assertEquals(Decision.GO, g.decide(s, 0, 0));
    }

    @Test
    void hazardReturningRestartsSettle() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, 0);
        g.decide(1, 0, 0);
        assertEquals(Decision.HOLD, g.decide(8, EXPLOSION, 0));
        assertEquals(Decision.HOLD, g.decide(15, 0, 0));
        assertEquals(Decision.HOLD, g.decide(24, 0, 0));
        assertEquals(Decision.GO, g.decide(25, 0, 0));
    }

    @Test
    void givesUpAtMaxWaitWhileHazardPersists() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, EXPLOSION);
        // the wait counts from the first resume attempt, not from the preemption
        assertEquals(Decision.HOLD, g.decide(500, EXPLOSION, 0));
        assertEquals(Decision.HOLD, g.decide(599, EXPLOSION, 0));
        assertEquals(Decision.GIVE_UP, g.decide(600, EXPLOSION, 0));
        assertEquals(EXPLOSION, g.blockedBy(), "kept for the notice");
        assertFalse(g.pending(), "the caller pauses the job; the gate does not keep holding");
    }

    @Test
    void neverGoesWhileHazardIsLive() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, LAVA);
        for (long t = 0; t < 1000 && g.pending(); t++) {
            Decision d = g.decide(t, LAVA, 0);
            assertFalse(d == Decision.GO, "GO with lava live at tick " + t);
        }
        assertFalse(g.pending());
    }

    @Test
    void flickeringHazardStillGivesUp() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, EXPLOSION);
        Decision last = Decision.HOLD;
        long t = 0;
        // clear for 5 ticks out of every 8: never long enough to settle
        for (; t < 400 && last == Decision.HOLD; t++) {
            last = g.decide(t, (t & 7) < 3 ? EXPLOSION : 0, 0);
        }
        assertEquals(Decision.GIVE_UP, last);
        assertTrue(t <= 100 + 10, "bounded by max wait plus settle, was " + t);
    }

    @Test
    void clearAtMaxWaitStillSettlesThenGoes() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, EXPLOSION);
        assertEquals(Decision.HOLD, g.decide(0, EXPLOSION, 0));
        assertEquals(Decision.HOLD, g.decide(95, 0, 0));
        assertEquals(Decision.HOLD, g.decide(100, 0, 0), "past the max wait but clear: finish settling");
        assertEquals(Decision.GO, g.decide(105, 0, 0));
    }

    @Test
    void hazardNearNextStepHoldsWhenItIsTheKindThatCausedTheEscape() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, EXPLOSION);
        // the player backed out of range, the fuse is still burning where the job wants to go
        assertEquals(Decision.HOLD, g.decide(0, 0, EXPLOSION));
        assertEquals(EXPLOSION, g.blockedBy());
        assertEquals(Decision.HOLD, g.decide(20, 0, EXPLOSION));
        assertEquals(Decision.HOLD, g.decide(21, 0, 0));
        assertEquals(Decision.GO, g.decide(31, 0, 0));
    }

    @Test
    void unrelatedHazardNearNextStepDoesNotHold() {
        ResumeHoldGate g = new ResumeHoldGate(100, 0);
        // fall clutch: no explosion or lava was ever near the player
        g.onPreempted(true, 0);
        assertEquals(0, g.seen());
        assertEquals(Decision.GO, g.decide(0, 0, LAVA | EXPLOSION), "a lava pool by the ore is not this escape's danger");

        // creeper escape: lava by the next step is still unrelated
        g.onPreempted(true, EXPLOSION);
        assertEquals(Decision.GO, g.decide(1, 0, LAVA));
    }

    @Test
    void kindSeenDuringTheHoldMakesTheStepHazardCount() {
        ResumeHoldGate g = new ResumeHoldGate(100, 0);
        g.onPreempted(true, 0);
        assertEquals(Decision.HOLD, g.decide(0, LAVA, 0));
        assertEquals(LAVA, g.seen());
        assertEquals(Decision.HOLD, g.decide(1, 0, LAVA));
        assertEquals(Decision.GO, g.decide(2, 0, 0));
    }

    @Test
    void noHoldWhenPreemptionWasNotEmergency() {
        ResumeHoldGate g = new ResumeHoldGate();
        g.onPreempted(false, EXPLOSION);
        assertFalse(g.pending());
        assertEquals(Decision.GO, g.decide(1, EXPLOSION, EXPLOSION));
        g.onPreempted(true, EXPLOSION);
        g.onPreempted(false, EXPLOSION);
        assertFalse(g.pending(), "a later normal preemption clears an emergency hold");
    }

    @Test
    void disarmClearsHold() {
        ResumeHoldGate g = new ResumeHoldGate();
        g.onPreempted(true, EXPLOSION | LAVA);
        g.decide(0, EXPLOSION, 0);
        g.disarm();
        assertEquals(0, g.seen());
        assertEquals(Decision.GO, g.decide(1, EXPLOSION, LAVA), "user resume and Stop-All are never held");
    }

    @Test
    void rearmingStartsAFreshWait() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(true, EXPLOSION);
        assertEquals(Decision.HOLD, g.decide(0, EXPLOSION, 0));
        assertEquals(Decision.HOLD, g.decide(90, EXPLOSION, 0));
        g.onPreempted(true, LAVA); // a second escape before the first resume
        assertEquals(LAVA, g.seen());
        assertEquals(Decision.HOLD, g.decide(150, LAVA, 0), "the max wait restarts with the new escape");
        assertEquals(Decision.GIVE_UP, g.decide(250, LAVA, 0));
    }

    @Test
    void decideIsAllocationFree() {
        Assumptions.assumeTrue(AllocationProbe.supported(), "thread allocation counters unavailable");
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        long[] tick = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            long t = tick[0]++;
            if (!g.pending()) {
                g.onPreempted(true, (int) (t & 3));
            }
            g.decide(t, (t & 7) < 3 ? EXPLOSION : 0, (t & 15) < 5 ? LAVA : 0);
        });
        assertTrue(bytes < 16 * 1024, "allocated " + bytes);
    }
}
