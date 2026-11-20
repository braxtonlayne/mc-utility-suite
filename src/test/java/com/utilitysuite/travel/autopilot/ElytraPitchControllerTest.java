package com.utilitysuite.travel.autopilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraPitchControllerTest {

    private final ElytraPitchController controller = new ElytraPitchController();
    private final RocketPolicy rockets = new RocketPolicy();

    /** Flies the vanilla physics port with the controller (and rockets when allowed); returns the sim. */
    private ElytraSim fly(double startY, double target, int ticks, boolean rocketsAllowed, double[] minMaxY,
                          double[] minSpeed, int settleTicks) {
        ElytraSim sim = new ElytraSim(startY, 1.2, 0.0);
        int sinceRocket = 1000;
        minMaxY[0] = Double.POSITIVE_INFINITY;
        minMaxY[1] = Double.NEGATIVE_INFINITY;
        minSpeed[0] = Double.POSITIVE_INFINITY;
        for (int t = 0; t < ticks; t++) {
            if (rocketsAllowed && rockets.shouldFire(sim.y, target, sim.vh, sim.boosting(), sinceRocket, 64, 0)) {
                sim.fireRocket();
                sinceRocket = 0;
            }
            sinceRocket++;
            double p = controller.pitch(sim.y, target, sim.vy, sim.vh, sim.boosting());
            assertTrue(p >= controller.minPitch() && p <= controller.maxPitch());
            sim.tick(p);
            if (t >= settleTicks) {
                minMaxY[0] = Math.min(minMaxY[0], sim.y);
                minMaxY[1] = Math.max(minMaxY[1], sim.y);
                minSpeed[0] = Math.min(minSpeed[0], sim.vh);
            }
        }
        return sim;
    }

    @Test
    void holdsCruiseAltitudeWithRockets() {
        double[] y = new double[2];
        double[] speed = new double[1];
        fly(100, 100, 3000, true, y, speed, 200);
        assertTrue(y[0] > 88 && y[1] < 112, "altitude band " + y[0] + ".." + y[1]);
        assertTrue(speed[0] > 0.4, "never stalls, min speed " + speed[0]);
    }

    @Test
    void convergesFromAboveToTargetBand() {
        double[] y = new double[2];
        double[] speed = new double[1];
        ElytraSim sim = fly(170, 100, 2400, true, y, speed, 900);
        assertTrue(y[0] > 88 && y[1] < 112, "settled band " + y[0] + ".." + y[1]);
        assertTrue(sim.distance > 1000, "made forward progress: " + sim.distance);
    }

    @Test
    void climbsFromBelowWithRockets() {
        double[] y = new double[2];
        double[] speed = new double[1];
        fly(60, 100, 2400, true, y, speed, 900);
        assertTrue(y[0] > 88 && y[1] < 112, "settled band " + y[0] + ".." + y[1]);
    }

    @Test
    void glidesWithoutStallingWhenOutOfRockets() {
        double[] y = new double[2];
        double[] speed = new double[1];
        ElytraSim sim = fly(200, 300, 600, false, y, speed, 100);
        assertTrue(speed[0] > 0.35, "keeps flying speed without rockets: " + speed[0]);
        // A glide sinks, but gently: well under fall-damage sink rates on average.
        double sinkPerTick = (200 - sim.y) / 600.0;
        assertTrue(sinkPerTick < 0.4, "average sink " + sinkPerTick);
    }

    @Test
    void pitchDirectionFollowsAltitudeError() {
        double above = controller.pitch(120, 100, 0, 1.5, false);
        double below = controller.pitch(80, 100, 0, 1.5, false);
        assertTrue(above > below, "nose down when above the target");
        assertEquals(controller.maxPitch(), controller.pitch(1000, 0, 0, 1.5, false));
        assertEquals(controller.minPitch(), controller.pitch(0, 1000, 0, 1.5, true));
    }

    @Test
    void stallRecoveryPutsNoseDown() {
        double slow = controller.pitch(80, 100, 0, 0.1, false);
        assertTrue(slow > 15, "nose-down recovery when slow, got " + slow);
        double slowBoosting = controller.pitch(80, 100, 0, 0.1, true);
        assertTrue(slowBoosting < 0, "a boosting glider may climb even when slow");
    }

    @Test
    void rocketPolicyRespectsReserveAndCooldown() {
        assertTrue(rockets.shouldFire(80, 100, 1.5, false, 100, 5, 2));
        assertTrue(!rockets.shouldFire(80, 100, 1.5, false, 100, 2, 2), "reserve kept");
        assertTrue(!rockets.shouldFire(80, 100, 1.5, false, 5, 5, 2), "cooldown");
        assertTrue(!rockets.shouldFire(80, 100, 1.5, true, 100, 5, 2), "already boosting");
        assertTrue(!rockets.shouldFire(100, 100, 1.5, false, 100, 5, 2), "on altitude and fast");
        assertTrue(rockets.shouldFire(100, 100, 0.5, false, 100, 5, 2), "too slow");
    }
}
