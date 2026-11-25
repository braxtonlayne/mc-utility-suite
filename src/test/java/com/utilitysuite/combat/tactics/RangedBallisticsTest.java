package com.utilitysuite.combat.tactics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RangedBallisticsTest {

    private final RangedBallistics b = new RangedBallistics();

    /** Independent whole-tick sim: height at the horizontal distance, linear between ticks. */
    private static double reference(double elevDeg, double speed, double dist) {
        double r = Math.toRadians(elevDeg);
        double vh = Math.cos(r) * speed, vy = Math.sin(r) * speed, h = 0, y = 0;
        for (int i = 0; i < 200; i++) {
            if (h + vh >= dist) {
                return y + vy * (dist - h) / vh;
            }
            h += vh;
            y += vy;
            vh *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return Double.NaN;
    }

    @Test
    void bowPowerMatchesVanilla() {
        assertEquals(0.0, RangedBallistics.bowPower(0), 1e-9);
        assertEquals((0.25 + 1.0) / 3.0, RangedBallistics.bowPower(10), 1e-9);
        assertEquals(1.0, RangedBallistics.bowPower(20), 1e-9);
        assertEquals(1.0, RangedBallistics.bowPower(40), 1e-9);
        assertEquals(3.0, RangedBallistics.bowSpeed(25), 1e-9);
    }

    @Test
    void flatShotAimsSlightlyUpAndHits() {
        assertTrue(b.solve(30, 0, 3.0, RangedBallistics.ARROW_WATER_DRAG, false));
        assertTrue(b.elevationDeg() > 0 && b.elevationDeg() < 10, "elev " + b.elevationDeg());
        assertEquals(0.0, reference(b.elevationDeg(), 3.0, 30), 0.02);
        assertTrue(b.flightTicks() > 9 && b.flightTicks() < 12, "ticks " + b.flightTicks());
        assertEquals((float) -b.elevationDeg(), b.mcPitch());
    }

    @Test
    void elevatedTargetNeedsMoreElevation() {
        assertTrue(b.solve(30, 0, 3.0, 0.6, false));
        double flat = b.elevationDeg();
        assertTrue(b.solve(30, 10, 3.0, 0.6, false));
        assertTrue(b.elevationDeg() > flat + 10);
        assertEquals(10.0, reference(b.elevationDeg(), 3.0, 30), 0.02);
        assertTrue(b.solve(30, -10, 3.0, 0.6, false));
        assertTrue(b.elevationDeg() < flat);
    }

    @Test
    void outOfRange() {
        assertFalse(b.solve(400, 0, 3.0, 0.6, false));
        assertFalse(b.solve(20, 60, 2.5, 0.99, false)); // trident cannot climb 60 blocks over 20
        assertTrue(b.solve(40, 0, RangedBallistics.TRIDENT_SPEED, 0.99, false));
    }

    @Test
    void movingTargetIsLedByFlightTime() {
        // Target 30 blocks along +x, walking +z at 0.2 blocks/tick.
        assertTrue(b.solveLead(0, 0, 0, 30, 0, 0, 0, 0, 0.2, 3.0, 0.6, false));
        double t = b.flightTicks();
        assertEquals(0.2 * t, b.aimZ(), 0.05);
        assertEquals(30.0, b.aimX(), 1e-9);
        // The led point is consistent: solving straight at it gives the same flight time.
        double d = Math.sqrt(30 * 30 + b.aimZ() * b.aimZ());
        RangedBallistics c = new RangedBallistics();
        assertTrue(c.solve(d, 0, 3.0, 0.6, false));
        assertEquals(t, c.flightTicks(), 0.1);
    }

    @Test
    void waterDragShortensRange() {
        assertTrue(b.solve(5, 0, 3.0, 0.6, true)); // 3 * sum(0.6^k) = 7.5 blocks max
        assertFalse(b.solve(8, 0, 3.0, 0.6, true));
        assertFalse(b.solve(30, 0, 3.0, RangedBallistics.ARROW_WATER_DRAG, true));
        assertTrue(b.solve(30, 0, 2.5, RangedBallistics.TRIDENT_WATER_DRAG, true));
    }
}
