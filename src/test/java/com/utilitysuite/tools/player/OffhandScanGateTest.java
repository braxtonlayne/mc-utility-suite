package com.utilitysuite.tools.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffhandScanGateTest {

    private final OffhandScanGate gate = new OffhandScanGate();

    @Test
    void withoutAPopScansFollowTheInterval() {
        int scans = 0;
        for (int clock = 1; clock <= OffhandManager.SCAN_INTERVAL * 4; clock++) {
            boolean due = gate.due(false, clock);
            assertEquals(clock % OffhandManager.SCAN_INTERVAL == 0, due, "tick " + clock);
            scans += due ? 1 : 0;
        }
        assertEquals(4, scans);
    }

    @Test
    void aHealthOrGlideChangeScansAtOnce() {
        assertTrue(gate.due(true, 1));
        assertFalse(gate.due(false, 2));
    }

    @Test
    void aPopScansOnTheVeryNextTickWithoutWaitingForTheInterval() {
        assertFalse(gate.due(false, 1), "one tick after an interval scan: nothing due");
        gate.popped();
        assertTrue(gate.due(false, 2), "the next tick scans");
    }

    @Test
    void thePopWindowScansEveryTickUntilTheIntervalHasTakenOver() {
        // the emptied offhand slot and the new health can arrive a tick or two after the pop event
        gate.popped();
        for (int i = 0; i < OffhandScanGate.POP_WINDOW; i++) {
            assertTrue(gate.inPopWindow());
            assertTrue(gate.due(false, 1), "window tick " + i);
        }
        assertFalse(gate.inPopWindow());
        assertFalse(gate.due(false, 1), "back to the interval");
        assertTrue(OffhandScanGate.POP_WINDOW >= OffhandManager.SCAN_INTERVAL,
                "no tick between the window and the next interval scan goes unscanned");
    }

    @Test
    void aPlannedSwapEndsTheWindow() {
        gate.popped();
        assertTrue(gate.due(false, 1));
        gate.clear(); // the refill was planned; its reaction delay runs in the manager
        assertFalse(gate.inPopWindow());
        assertFalse(gate.due(false, 2));
    }

    @Test
    void aSecondPopRestartsTheWindow() {
        gate.popped();
        gate.due(false, 1);
        gate.due(false, 2);
        gate.popped();
        for (int i = 0; i < OffhandScanGate.POP_WINDOW; i++) {
            assertTrue(gate.due(false, 1));
        }
        assertFalse(gate.due(false, 1));
    }
}
