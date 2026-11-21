package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.jobs.goal.ResumeHoldGate.Decision;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeHoldGateTest {

    @Test
    void holdsWhileHazardPresent() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(1000, true);
        for (long t = 1000; t < 1090; t++) {
            assertEquals(Decision.HOLD, g.decide(t, true), "tick " + t);
        }
        assertTrue(g.pending());
    }

    @Test
    void goesAfterSettleOnceClear() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(0, true);
        assertEquals(Decision.HOLD, g.decide(5, true));
        for (long t = 6; t < 16; t++) {
            assertEquals(Decision.HOLD, g.decide(t, false), "tick " + t);
        }
        assertEquals(Decision.GO, g.decide(16, false));
        assertFalse(g.pending());
        assertEquals(Decision.GO, g.decide(17, true), "disarmed after GO");
    }

    @Test
    void hazardReturningRestartsSettle() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(0, true);
        g.decide(1, false);
        assertEquals(Decision.HOLD, g.decide(8, true));
        assertEquals(Decision.HOLD, g.decide(15, false));
        assertEquals(Decision.GO, g.decide(25, false));
    }

    @Test
    void goesAtMaxHoldEvenIfHazardPersists() {
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        g.onPreempted(50, true);
        assertEquals(Decision.HOLD, g.decide(149, true));
        assertEquals(Decision.GO, g.decide(150, true));
        assertFalse(g.pending());
    }

    @Test
    void noHoldWhenPreemptionWasNotEmergency() {
        ResumeHoldGate g = new ResumeHoldGate();
        g.onPreempted(0, false);
        assertFalse(g.pending());
        assertEquals(Decision.GO, g.decide(1, true));
        g.onPreempted(0, true);
        g.onPreempted(2, false);
        assertFalse(g.pending(), "a later normal preemption clears an emergency hold");
    }

    @Test
    void disarmClearsHold() {
        ResumeHoldGate g = new ResumeHoldGate();
        g.onPreempted(0, true);
        g.disarm();
        assertEquals(Decision.GO, g.decide(1, true));
    }

    @Test
    void distanceVariantIgnoresFarHazard() {
        ResumeHoldGate g = new ResumeHoldGate(100, 0);
        g.onPreempted(0, true);
        assertEquals(Decision.HOLD, g.decide(1, true, 3.0, 20.0, 7.0));
        assertEquals(Decision.HOLD, g.decide(2, true, 20.0, 4.0, 7.0), "near the next target");
        assertEquals(Decision.GO, g.decide(3, true, 20.0, 20.0, 7.0));
    }

    @Test
    void decideIsAllocationFree() {
        Assumptions.assumeTrue(AllocationProbe.supported(), "thread allocation counters unavailable");
        ResumeHoldGate g = new ResumeHoldGate(100, 10);
        long[] tick = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            long t = tick[0]++;
            if (!g.pending()) {
                g.onPreempted(t, true);
            }
            g.decide(t, (t & 7) < 3);
        });
        assertTrue(bytes < 16 * 1024, "allocated " + bytes);
    }
}
