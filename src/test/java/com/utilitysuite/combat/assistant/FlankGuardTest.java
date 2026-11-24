package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlankGuardTest {

    // We stand at the origin facing +x toward the target at (3, 0); our left is -z.

    @Test
    void secondAttackerOnTheLeftSendsUsRight() {
        FlankGuard g = new FlankGuard();
        assertTrue(g.plan(0, 0, 3, 0, 1, -3));
        assertEquals(StrafePlanner.RIGHT, g.side(), "moving right lines the target up between us and it");
        assertTrue(g.strength() > StrafePlanner.WITH_THRESHOLD);
    }

    @Test
    void secondAttackerOnTheRightSendsUsLeft() {
        FlankGuard g = new FlankGuard();
        assertTrue(g.plan(0, 0, 3, 0, 1, 3));
        assertEquals(StrafePlanner.LEFT, g.side());
    }

    @Test
    void secondAttackerStraightBehindTheTargetNeedsNoPreference() {
        FlankGuard g = new FlankGuard();
        g.plan(0, 0, 3, 0, 6, 0);
        assertTrue(g.strength() < 1.0e-6, "already covered: both sides open the angle equally");
    }

    @Test
    void theFartherOffTheLineTheSecondAttackerTheStrongerThePreference() {
        FlankGuard g = new FlankGuard();
        g.plan(0, 0, 3, 0, 6, -0.3);
        double nearLine = g.strength();
        g.plan(0, 0, 3, 0, 4, -3);
        assertTrue(g.strength() > nearLine);
    }

    @Test
    void degenerateInputsGiveNoPreference() {
        FlankGuard g = new FlankGuard();
        assertFalse(g.plan(0, 0, 0.1, 0, 1, 1), "target on top of us");
        assertFalse(g.plan(0, 0, 3, 0, 3, 0), "second attacker on the target");
        assertEquals(StrafePlanner.NONE, g.side());
    }

    @Test
    void planAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        FlankGuard g = new FlankGuard();
        Runnable body = () -> g.plan(0, 0, 3, 0, 1, -3);
        AllocationProbe.measure(50_000, body);
        long bytes = AllocationProbe.measure(50_000, body);
        assertTrue(bytes < 1_024, "FlankGuard.plan allocated " + bytes);
    }
}
