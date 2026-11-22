package com.utilitysuite.combat.tactics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

class PearlLandingSimTest {

    /** Solid floor: every cell with y <= 0 (top face at y = 1). */
    private static final CombatVoxels FLOOR = (x, y, z) -> y <= 0 ? CombatVoxels.SOLID : CombatVoxels.AIR;

    @Test
    void throwLandsOnFloorAtSteppedDistance() {
        double speed = 1.5;
        for (int deg : new int[] {0, 15, 30, 45}) {
            double pitch = Math.toRadians(deg);
            double vx = Math.cos(pitch) * speed;
            double vy = Math.sin(pitch) * speed;
            // Reference: whole-tick stepping, first tick whose end point is below the floor top.
            double x = 0, y = 5, cvx = vx, cvy = vy;
            int tick = 0;
            double prevX = x;
            while (true) {
                tick++;
                prevX = x;
                x += cvx;
                y += cvy;
                cvx *= 0.99;
                cvy = cvy * 0.99 - 0.03;
                if (y < 1.0) {
                    break;
                }
                assertTrue(tick < 200);
            }
            // y after the tick is post-move (the loop moved before testing), so x is the end-of-tick position.
            double endX = x;
            PearlLandingSim sim = new PearlLandingSim();
            assertTrue(sim.simulate(0, 5, 0, vx, vy, 0, FLOOR, 200), "deg " + deg);
            assertEquals(tick, sim.flightTicks(), "deg " + deg);
            assertEquals(1.0, sim.landY(), 1e-9, "snapped to the floor top, deg " + deg);
            assertTrue(sim.landX() >= prevX - PearlLandingSim.BACK_OFF - 0.01 && sim.landX() <= endX, "deg " + deg + " landX " + sim.landX()
                    + " in [" + prevX + "," + endX + "]");
        }
    }

    @Test
    void wallHitLandsInFrontOfWall() {
        CombatVoxels wall = (x, y, z) -> x >= 10 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        PearlLandingSim sim = new PearlLandingSim();
        assertTrue(sim.simulate(0.5, 50, 0.5, 1.2, 0, 0, wall, 200));
        assertTrue(sim.landX() < 10.0 && sim.landX() > 9.3, "landX " + sim.landX());
        assertTrue(sim.flightTicks() >= 7 && sim.flightTicks() <= 9, "ticks " + sim.flightTicks());
    }

    @Test
    void ledgeTopLandsOnTop() {
        CombatVoxels ledge = (x, y, z) -> (x >= 3 && x <= 8 && y <= 2) ? CombatVoxels.SOLID : CombatVoxels.AIR;
        PearlLandingSim sim = new PearlLandingSim();
        assertTrue(sim.simulate(5.5, 8, 0.5, 0.0, 0.0, 0.0, ledge, 200));
        assertEquals(3.0, sim.landY(), 1e-9);
        assertEquals(5.5, sim.landX(), 0.5);
    }

    @Test
    void unknownCellReturnsFalse() {
        CombatVoxels unknown = (x, y, z) -> x >= 5 ? CombatVoxels.UNKNOWN : CombatVoxels.AIR;
        assertFalse(new PearlLandingSim().simulate(0.5, 50, 0.5, 1.0, 0, 0, unknown, 200));
    }

    @Test
    void lavaReturnsFalse() {
        CombatVoxels lava = (x, y, z) -> y <= 0 ? CombatVoxels.LAVA : CombatVoxels.AIR;
        assertFalse(new PearlLandingSim().simulate(0.5, 5, 0.5, 0.5, 0, 0, lava, 200));
    }

    @Test
    void voidReturnsFalse() {
        CombatVoxels empty = (x, y, z) -> CombatVoxels.AIR;
        assertFalse(new PearlLandingSim().simulate(0.5, 5, 0.5, 0.5, 0, 0, empty, 200));
    }

    @Test
    void waterSlowsThePearl() {
        CombatVoxels pool = (x, y, z) -> y <= 0 ? CombatVoxels.SOLID
                : (y <= 6 ? CombatVoxels.WATER : CombatVoxels.AIR);
        PearlLandingSim dry = new PearlLandingSim();
        PearlLandingSim wet = new PearlLandingSim();
        assertTrue(dry.simulate(0.5, 5.5, 0.5, 1.0, 0, 0, FLOOR, 200));
        assertTrue(wet.simulate(0.5, 5.5, 0.5, 1.0, 0, 0, pool, 200));
        assertTrue(wet.landX() < dry.landX() - 1.0, "wet " + wet.landX() + " dry " + dry.landX());
    }

    @Test
    void maxTicksRespected() {
        PearlLandingSim sim = new PearlLandingSim();
        assertFalse(sim.simulate(0, 100, 0, 0.5, 0, 0, FLOOR, 3));
        assertTrue(sim.simulate(0, 100, 0, 0.5, 0, 0, FLOOR, 200));
        assertFalse(sim.simulate(0, 5000, 0, 0.5, 0, 0, FLOOR, 1000), "capped at MAX_TICKS");
    }

    @Test
    void allocationFreeAndFast() {
        PearlLandingSim sim = new PearlLandingSim();
        Runnable body = () -> sim.simulate(0, 5, 0, 1.2, 0.8, 0.3, FLOOR, 200);
        if (AllocationProbe.supported()) {
            long bytes = AllocationProbe.measure(20_000, body);
            assertTrue(bytes < 1024, "allocated " + bytes + " bytes");
        }
        long t0 = System.nanoTime();
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            body.run();
        }
        double us = (System.nanoTime() - t0) / 1000.0 / n;
        System.out.println("PearlLandingSim: " + us + " us per simulate");
        assertTrue(us < 200, "far too slow: " + us);
    }
}
