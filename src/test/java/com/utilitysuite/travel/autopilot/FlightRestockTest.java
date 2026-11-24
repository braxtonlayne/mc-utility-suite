package com.utilitysuite.travel.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FlightRestockTest {

    @Test
    void swapsWhenStackAvailableAndHighEnough() {
        FlightRestock r = new FlightRestock();
        assertEquals(FlightRestock.Action.SWAP, r.decide(32, true, false));
        assertEquals(1, r.restocks());
    }

    @Test
    void landsWhenNothingLeftInMainInventory() {
        assertEquals(FlightRestock.Action.ABORT, new FlightRestock().decide(0, true, false));
    }

    @Test
    void waitsForSettleThenJudgesAgain() {
        FlightRestock r = new FlightRestock();
        r.decide(16, true, false);
        for (int i = 0; i < FlightRestock.SETTLE_TICKS; i++) {
            assertEquals(FlightRestock.Action.WAIT, r.decide(0, true, false), "swap still in transit");
            r.tick();
        }
        // the stack arrived in the hotbar; main is empty and the hotbar count (judged by the caller) is fine, but
        // if it were still low there is nothing more to take
        assertEquals(FlightRestock.Action.ABORT, r.decide(0, true, false));
    }

    @Test
    void tooLowDefersThenAborts() {
        FlightRestock r = new FlightRestock();
        for (int i = 0; i < FlightRestock.MAX_DEFER_TICKS; i++) {
            assertEquals(FlightRestock.Action.WAIT, r.decide(10, false, false));
        }
        assertEquals(FlightRestock.Action.ABORT, r.decide(10, false, false));
    }

    @Test
    void busyScreenOrQueueDefersButCanRecover() {
        FlightRestock r = new FlightRestock();
        assertEquals(FlightRestock.Action.WAIT, r.decide(10, true, true));
        assertEquals(FlightRestock.Action.SWAP, r.decide(10, true, false));
    }

    @Test
    void clearDeferResetsTheWait() {
        FlightRestock r = new FlightRestock();
        for (int i = 0; i < FlightRestock.MAX_DEFER_TICKS; i++) {
            r.decide(10, false, false);
        }
        r.clearDefer();
        assertEquals(FlightRestock.Action.WAIT, r.decide(10, false, false));
    }

    @Test
    void restockCapAborts() {
        FlightRestock r = new FlightRestock();
        for (int i = 0; i < FlightRestock.MAX_RESTOCKS; i++) {
            assertEquals(FlightRestock.Action.SWAP, r.decide(64, true, false));
            for (int t = 0; t < FlightRestock.SETTLE_TICKS; t++) {
                r.tick();
            }
        }
        assertEquals(FlightRestock.Action.ABORT, r.decide(64, true, false));
        r.reset();
        assertEquals(FlightRestock.Action.SWAP, r.decide(64, true, false));
    }

    @Test
    void pickSourceTakesLargestMainStackIgnoringHotbar() {
        int[] counts = new int[36];
        counts[3] = 64; // hotbar: ignored
        counts[10] = 8;
        counts[20] = 40;
        counts[35] = 12;
        assertEquals(20, FlightRestock.pickSource(counts));
        assertEquals(60, FlightRestock.sumMain(counts));
        counts[10] = counts[20] = counts[35] = 0;
        assertEquals(-1, FlightRestock.pickSource(counts));
    }

    @Test
    void pickTargetPrefersEmptyThenOtherNeverRocketOrSelected() {
        int[] kinds = new int[9];
        java.util.Arrays.fill(kinds, FlightRestock.OTHER);
        kinds[4] = FlightRestock.ROCKET;
        kinds[6] = FlightRestock.EMPTY;
        assertEquals(6, FlightRestock.pickTarget(kinds, 0));
        kinds[6] = FlightRestock.OTHER;
        assertEquals(1, FlightRestock.pickTarget(kinds, 0));
        assertEquals(0, FlightRestock.pickTarget(kinds, 1));
        java.util.Arrays.fill(kinds, FlightRestock.ROCKET);
        assertEquals(-1, FlightRestock.pickTarget(kinds, 0));
    }

    @Test
    void spareElytraOnlyWhenClearlyHealthier() {
        int[] left = new int[36];
        java.util.Arrays.fill(left, -1);
        assertEquals(-1, FlightRestock.spareElytraSlot(left, 15, 100));
        left[12] = 90; // gain of 75: not worth it
        assertEquals(-1, FlightRestock.spareElytraSlot(left, 15, 100));
        left[12] = 300;
        left[30] = 400;
        assertEquals(30, FlightRestock.spareElytraSlot(left, 15, 100));
    }
}
