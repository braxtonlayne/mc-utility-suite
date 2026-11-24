package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cruise margin while lookahead terrain is not loaded: higher target, rockets held back, no stall. */
class UnloadedTerrainHoldTest {

    @Test
    void idleChangesNothing() {
        UnloadedTerrainHold hold = new UnloadedTerrainHold();
        hold.update(false, 100);
        assertFalse(hold.active());
        assertEquals(120.0, hold.targetAltitude(120.0));
        assertTrue(hold.rocketAllowed(2.0));
    }

    @Test
    void raisesTheTargetByTheMarginAndAtLeastAboveTheStartAltitude() {
        UnloadedTerrainHold hold = new UnloadedTerrainHold();
        hold.update(true, 150);
        assertTrue(hold.active());
        assertEquals(158.0, hold.targetAltitude(100.0), "at least the start altitude + 8");
        assertEquals(168.0, hold.targetAltitude(160.0), "known terrain + clearance + 8 when that is higher");
        // The floor is latched at the start: climbing does not push the target up tick after tick.
        for (int t = 0; t < 50; t++) {
            hold.update(true, 150 + t);
        }
        assertEquals(158.0, hold.targetAltitude(100.0));
    }

    @Test
    void holdsRocketsBackExceptToAvoidAStallThenAllowsSpeedKeeping() {
        UnloadedTerrainHold hold = new UnloadedTerrainHold();
        for (int t = 0; t < UnloadedTerrainHold.GRACE_TICKS; t++) {
            hold.update(true, 100);
            assertFalse(hold.rocketAllowed(0.9), "cruise speed: no rocket during the hold, tick " + t);
            assertTrue(hold.rocketAllowed(0.5), "near a stall a rocket is still allowed, tick " + t);
        }
        hold.update(true, 100);
        assertTrue(hold.rocketAllowed(0.9), "a long hold lets the policy keep cruise speed again");
        assertTrue(hold.active(), "the margin stays while the terrain is unknown");
    }

    @Test
    void releasesAfterAFewLoadedTicksNotOnAFlicker() {
        UnloadedTerrainHold hold = new UnloadedTerrainHold();
        for (int t = 0; t < 20; t++) {
            hold.update(true, 100);
        }
        for (int t = 0; t < UnloadedTerrainHold.RELEASE_TICKS - 1; t++) {
            hold.update(false, 100);
        }
        assertTrue(hold.active(), "a short loaded gap keeps the hold");
        hold.update(true, 130);
        assertEquals(108.0, hold.targetAltitude(0.0), "and its start altitude");
        for (int t = 0; t < UnloadedTerrainHold.RELEASE_TICKS; t++) {
            hold.update(false, 130);
        }
        assertFalse(hold.active());
        assertEquals(90.0, hold.targetAltitude(90.0));
        hold.update(true, 130);
        assertEquals(138.0, hold.targetAltitude(0.0), "a new hold latches the new altitude");
    }

    @Test
    void announcesOncePerHold() {
        UnloadedTerrainHold hold = new UnloadedTerrainHold();
        int announced = 0;
        for (int t = 0; t < 400; t++) {
            hold.update(true, 100);
            if (hold.announce()) {
                announced++;
            }
        }
        assertEquals(1, announced);
        hold.reset();
        assertFalse(hold.active());
    }

    /** The driver's cruise loop on the vanilla physics port: the terrain ahead never loads. */
    @Test
    void aPermanentHoldStillGlidesForwardWithoutStalling() {
        ElytraPitchController controller = new ElytraPitchController();
        RocketPolicy rockets = new RocketPolicy().configure(0.9, 6.0, 30);
        UnloadedTerrainHold hold = new UnloadedTerrainHold();
        ElytraSim sim = new ElytraSim(100, 1.2, 0.0);
        double base = 100; // known ground + clearance
        int sinceRocket = 1000;
        int rocketsInGrace = 0;
        double minSpeed = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double distanceAtGrace = 0;
        for (int t = 0; t < 3000; t++) {
            hold.update(true, sim.y);
            double target = hold.targetAltitude(base);
            double rocketAlt = hold.active() ? sim.y : target;
            if (hold.rocketAllowed(sim.vh)
                    && rockets.shouldFire(sim.y, rocketAlt, sim.vh, sim.boosting(), sinceRocket, 64, 3)) {
                sim.fireRocket();
                sinceRocket = 0;
                if (t < UnloadedTerrainHold.GRACE_TICKS) {
                    rocketsInGrace++;
                }
            }
            sinceRocket++;
            sim.tick(controller.pitch(sim.y, target, sim.vy, sim.vh, sim.boosting()));
            if (t == UnloadedTerrainHold.GRACE_TICKS) {
                distanceAtGrace = sim.distance;
            }
            minSpeed = Math.min(minSpeed, sim.vh);
            minY = Math.min(minY, sim.y);
        }
        assertTrue(rocketsInGrace <= 1, "rockets held back early in the hold: " + rocketsInGrace);
        assertTrue(distanceAtGrace > 40, "kept gliding forward during the hold: " + distanceAtGrace);
        assertTrue(minSpeed > 0.4, "never stalls, min speed " + minSpeed);
        assertTrue(minY > 85, "does not sink far below the known-terrain target: " + minY);
        assertTrue(sim.distance > 1500, "a long hold still makes progress: " + sim.distance);
    }

    @Test
    void allocatesNothingPerTick() {
        Assumptions.assumeTrue(AllocationProbe.supported(), "thread allocation counters unavailable");
        UnloadedTerrainHold hold = new UnloadedTerrainHold();
        int[] i = new int[1];
        double[] sink = new double[1];
        long bytes = AllocationProbe.measure(100_000, () -> {
            int t = i[0]++;
            hold.update((t / 37) % 2 == 0, 100 + (t & 15));
            sink[0] += hold.targetAltitude(120.0);
            if (hold.rocketAllowed(0.8) || hold.announce()) {
                sink[0] += 1;
            }
        });
        assertTrue(bytes < 1_024, "hold allocated " + bytes + " bytes");
    }
}
