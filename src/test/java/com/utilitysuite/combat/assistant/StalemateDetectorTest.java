package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StalemateDetectorTest {

    final StalemateDetector d = new StalemateDetector();
    final CombatSnapshot s = new CombatSnapshot();

    StalemateDetectorTest() {
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

    @Test
    void pacingAtTheTopOfARavineWeAreInIsAStall() {
        // we are 8 blocks down; it paces back and forth along the rim, 3 blocks to the side
        for (int t = 0; t < 120; t++) {
            double pace = Math.sin(t * 0.15) * 2.0;
            place(10.5, 56, 0.5, 7.5, 64, 0.5 + pace);
            s.tdz = Math.cos(t * 0.15) * 0.3;
            s.tdx = 0.0;
            d.observe(s, false);
        }
        assertTrue(d.unreachableNow());
        assertEquals(120, d.stallTicks(), "every tick counts: no hits, no approach, no progress");
    }

    @Test
    void staringAcrossAGapAtTheSameLevelIsAStallToo() {
        for (int t = 0; t < 100; t++) {
            double sway = Math.sin(t * 0.2) * 0.8; // both sway in and out a little: the distance oscillates
            place(0.5 + sway, 64, 0.5, 7.5, 64, 0.5);
            s.tdx = Math.cos(t * 0.3) * 0.2;
            d.observe(s, false);
        }
        assertFalse(d.unreachableNow());
        assertEquals(100, d.stallTicks());
    }

    @Test
    void aSpacingDanceInsideReachIsNoStallEvenWithoutHits() {
        for (int t = 0; t < 200; t++) {
            double r = 3.0 + Math.sin(t * 0.25) * 0.4; // hovering at the edge of reach, strafing round
            double a = t * 0.05;
            place(r * Math.cos(a), 64, r * Math.sin(a), 0, 64, 0);
            s.tdx = 0.1 * Math.sin(t * 0.7);
            s.tdz = 0.1 * Math.cos(t * 0.7);
            d.observe(s, false);
            assertEquals(0, d.stallTicks(), "tick " + t);
        }
    }

    @Test
    void oneSidedPressureIsNoStall() {
        // it hits us from above every second; we cannot answer: damage keeps restarting the count
        float hp = 20f;
        int max = 0;
        for (int t = 0; t < 200; t++) {
            place(0.5, 64, 0.5, 2.5, 66.5, 0.5);
            if (t % 20 == 19) {
                hp -= 1f;
            }
            s.health = hp;
            d.observe(s, false);
            max = Math.max(max, d.stallTicks());
        }
        assertTrue(max < 20, "max " + max);
        // and our hits landing restart it the same way
        StalemateDetector d2 = new StalemateDetector();
        s.health = 20f;
        float th = 20f;
        max = 0;
        for (int t = 0; t < 200; t++) {
            place(0.5, 64, 0.5, 6.5, 64, 0.5);
            if (t % 30 == 29) {
                th -= 2f;
            }
            s.targetHealth = th;
            d2.observe(s, false);
            max = Math.max(max, d2.stallTicks());
        }
        assertTrue(max < 30, "max " + max);
    }

    @Test
    void aTargetWalkingInOrAChaseThatGainsIsNoStall() {
        for (int t = 0; t < 120; t++) {
            place(0.5, 64, 0.5, 20.5 - t * 0.15, 64, 0.5);
            s.tdx = -0.15; // it walks in
            d.observe(s, false);
        }
        assertEquals(0, d.stallTicks());
        StalemateDetector d2 = new StalemateDetector();
        s.tdx = 0.0;
        for (int t = 0; t < 120; t++) {
            place(t * 0.1, 64, 0.5, 20.5, 64, 0.5); // we close in on a still target
            d2.observe(s, false);
        }
        assertTrue(d2.stallTicks() < 15, "progress restarts the count: " + d2.stallTicks());
    }

    @Test
    void busyRestartsTheCount() {
        for (int t = 0; t < 60; t++) {
            place(0.5, 56, 0.5, 3.5, 64, 0.5);
            d.observe(s, t == 50);
        }
        assertEquals(9, d.stallTicks());
    }

    @Test
    void reachabilityRule() {
        assertTrue(StalemateDetector.unreachable(8.0));
        assertTrue(StalemateDetector.unreachable(-8.0));
        assertFalse(StalemateDetector.unreachable(1.0)); // a jump
        assertFalse(StalemateDetector.unreachable(-3.0)); // a safe drop
    }

    @Test
    void thePerTickDetectorAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        int[] t = {0};
        Runnable body = () -> {
            t[0]++;
            place(10.5, 56, 0.5, 7.5, 64, 0.5 + (t[0] & 7) * 0.25);
            s.tdz = (t[0] & 1) == 0 ? 0.2 : -0.2;
            d.observe(s, (t[0] & 1023) == 0);
        };
        long bytes = AllocationProbe.measure(100_000, body);
        assertTrue(bytes < 1024, "detector allocated " + bytes + " bytes over 100k ticks");
    }
}
