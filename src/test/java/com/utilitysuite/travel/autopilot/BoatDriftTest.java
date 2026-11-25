package com.utilitysuite.travel.autopilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boat drift simulation and ice-track corner reading on hand-built surfaces. */
class BoatDriftTest {

    /** Everything one surface, optionally a wall line at x >= wallX. */
    private static BoatDriftSim.Surface uniform(int code, int wallX) {
        return (x, z) -> x >= wallX ? BoatDriftSim.WALL : code;
    }

    private static final double YAW_EAST = -90.0; // vanilla: forward = (-sin yaw, cos yaw) = (+1, 0)

    @Test
    void forwardOnIceApproachesTerminalSpeed() {
        BoatDriftSim sim = new BoatDriftSim();
        sim.run(uniform(BoatDriftSim.ICE, 100_000), 0.5, 0.5, 0, 0, YAW_EAST, 0, true, false, false, false, 120);
        double v = sim.speed(sim.count() - 1);
        assertEquals(2.0, BoatDriftSim.terminalSpeed(BoatDriftSim.FRICTION_ICE), 1e-9);
        assertTrue(v > 1.7 && v < 2.0, "speed " + v);
        assertTrue(sim.x(sim.count() - 1) > 100, "moved east");
        assertEquals(0.5, sim.z(sim.count() - 1), 1e-6);
        // on plain ground the same input tops out at 0.1 blocks/tick
        sim.run(uniform(BoatDriftSim.LAND, 100_000), 0.5, 0.5, 0, 0, YAW_EAST, 0, true, false, false, false, 40);
        assertEquals(0.1, sim.speed(sim.count() - 1), 0.002);
        assertTrue(BoatDriftSim.terminalSpeed(BoatDriftSim.FRICTION_BLUE_ICE) > 3.5);
    }

    @Test
    void coastingKeepsMomentumOnIceNotOnLand() {
        BoatDriftSim sim = new BoatDriftSim();
        sim.run(uniform(BoatDriftSim.ICE, 100_000), 0.5, 0.5, 1.0, 0, YAW_EAST, 0, false, false, false, false, 20);
        assertEquals(Math.pow(0.98, 20), sim.speed(20), 1e-9);
        sim.run(uniform(BoatDriftSim.BLUE_ICE, 100_000), 0.5, 0.5, 1.0, 0, YAW_EAST, 0, false, false, false, false,
                20);
        assertEquals(Math.pow(0.989, 20), sim.speed(20), 1e-9);
        sim.run(uniform(BoatDriftSim.LAND, 100_000), 0.5, 0.5, 1.0, 0, YAW_EAST, 0, false, false, false, false, 20);
        assertTrue(sim.speed(20) < 1e-4);
    }

    @Test
    void wallStopsTheBlockedAxis() {
        BoatDriftSim sim = new BoatDriftSim();
        sim.run(uniform(BoatDriftSim.ICE, 10), 0.5, 0.5, 1.5, 0.2, YAW_EAST, 0, true, false, false, false, 60);
        assertTrue(sim.hitWall());
        double lastX = sim.x(sim.count() - 1);
        assertTrue(lastX < 10 - BoatDriftSim.HALF_WIDTH + 1e-9, "x " + lastX);
        assertTrue(sim.z(sim.count() - 1) > 1.0, "keeps sliding along the wall");
    }

    @Test
    void turningOnIceSpinsTheBoatButMomentumCarriesOn() {
        BoatDriftSim sim = new BoatDriftSim();
        sim.run(uniform(BoatDriftSim.ICE, 100_000), 0.5, 0.5, 1.5, 0, YAW_EAST, 0, false, false, true, false, 10);
        // ten ticks of LEFT barely bend a 1.5 b/t slide
        assertTrue(sim.x(10) > 10, "x " + sim.x(10));
        assertTrue(Math.abs(sim.z(10) - 0.5) < 0.5, "z " + sim.z(10));
    }

    @Test
    void bubbleColumnAndDropEndThePrediction() {
        BoatDriftSim sim = new BoatDriftSim();
        BoatDriftSim.Surface s = (x, z) -> x >= 5 ? BoatDriftSim.BUBBLE_DOWN : BoatDriftSim.WATER;
        sim.run(s, 0.5, 0.5, 0.5, 0, YAW_EAST, 0, true, false, false, false, 60);
        assertEquals(BoatDriftSim.END_BUBBLE, sim.endReason());
        assertTrue(sim.count() < 60);
        BoatDriftSim.Surface d = (x, z) -> x >= 5 ? BoatDriftSim.DROP : BoatDriftSim.ICE;
        sim.run(d, 0.5, 0.5, 1.0, 0, YAW_EAST, 0, true, false, false, false, 60);
        assertEquals(BoatDriftSim.END_DROP, sim.endReason());
    }

    @Test
    void waterCurrentPushesTheBoat() {
        BoatDriftSim sim = new BoatDriftSim();
        BoatDriftSim.Surface s = new BoatDriftSim.Surface() {
            @Override
            public int surface(int x, int z) {
                return BoatDriftSim.WATER;
            }

            @Override
            public double flowZ(int x, int z) {
                return 1.0;
            }
        };
        sim.run(s, 0.5, 0.5, 0, 0, YAW_EAST, 0, false, false, false, false, 40);
        double z = sim.z(sim.count() - 1);
        // terminal drift 0.014 / (1 - 0.9) = 0.14 b/t
        assertTrue(z > 3.0, "z " + z);
        assertEquals(0.5, sim.x(sim.count() - 1), 1e-9);
    }

    @Test
    void brakingDistanceGrowsWithSpeedAndSlipperiness() {
        double ice = BoatDriftSim.brakingDistance(1.8, 0.4, BoatDriftSim.FRICTION_ICE);
        double blue = BoatDriftSim.brakingDistance(1.8, 0.4, BoatDriftSim.FRICTION_BLUE_ICE);
        double slow = BoatDriftSim.brakingDistance(0.9, 0.4, BoatDriftSim.FRICTION_ICE);
        assertTrue(blue > ice && ice > slow && slow > 0, ice + " " + blue + " " + slow);
        assertEquals(0.0, BoatDriftSim.brakingDistance(0.3, 0.4, BoatDriftSim.FRICTION_ICE));
    }

    // ---- corridor -------------------------------------------------------------------------------------------------

    /** Blue ice: an east-bound leg z -2..2, x 0..29, turning north (to -z) through x 25..29. */
    private static final BoatDriftSim.Surface L_TRACK = (x, z) -> {
        boolean approach = x >= 0 && x <= 29 && z >= -2 && z <= 2;
        boolean exit = x >= 25 && x <= 29 && z >= -20 && z <= 2;
        return approach || exit ? BoatDriftSim.BLUE_ICE : BoatDriftSim.WALL;
    };

    @Test
    void lCornerApexAndLine() {
        IceTrackCorridor c = new IceTrackCorridor();
        assertTrue(c.analyze(L_TRACK, 2.5, 0.5, YAW_EAST, 0.5, 48));
        assertTrue(c.corner());
        assertFalse(c.deadEnd());
        // apex just inside the inner corner (x = 25, z = -2)
        assertTrue(c.apexX() > 25.0 && c.apexX() < 26.5, "apex x " + c.apexX());
        assertTrue(c.apexZ() > -2.0 && c.apexZ() < -0.8, "apex z " + c.apexZ());
        // the line starts on the outer (south, +z) side and ends heading north in the exit leg
        assertTrue(c.lineZ(0) > 1.0, "turn-in z " + c.lineZ(0));
        assertTrue(c.lineZ(IceTrackCorridor.LINE_SAMPLES - 1) < -4.0);
        assertTrue(c.lineX(IceTrackCorridor.LINE_SAMPLES - 1) > 27.0, "exit on the outer (east) side");
        for (int i = 0; i < IceTrackCorridor.LINE_SAMPLES; i++) {
            int cx = (int) Math.floor(c.lineX(i));
            int cz = (int) Math.floor(c.lineZ(i));
            assertTrue(BoatDriftSim.isIce(L_TRACK.surface(cx, cz)), "line sample " + i + " off the track");
        }
        assertTrue(c.radius() > 2.0 && c.radius() < 20.0, "radius " + c.radius());
        assertTrue(c.cornerSpeed() > 0.2 && c.cornerSpeed() < 1.0, "corner speed " + c.cornerSpeed());
        // slow: no need to brake yet, the marker is ahead of the boat on the approach
        assertFalse(c.brakeNow());
        assertTrue(c.brakeX() > 2.5 && c.brakeX() < 25.0, "brake x " + c.brakeX());
    }

    @Test
    void fastBoatNearTheCornerMustBrakeNow() {
        IceTrackCorridor c = new IceTrackCorridor();
        assertTrue(c.analyze(L_TRACK, 15.5, 0.5, YAW_EAST, 3.4, 48));
        assertTrue(c.corner());
        assertTrue(c.brakeNow());
        assertEquals(15.5, c.brakeX(), 1e-9);
    }

    @Test
    void mirroredTrackTurnsTheOtherWay() {
        BoatDriftSim.Surface mirrored = (x, z) -> L_TRACK.surface(x, -z);
        IceTrackCorridor a = new IceTrackCorridor();
        IceTrackCorridor b = new IceTrackCorridor();
        a.analyze(L_TRACK, 2.5, 0.5, YAW_EAST, 0.5, 48);
        b.analyze(mirrored, 2.5, 0.5, YAW_EAST, 0.5, 48);
        assertEquals(-a.turnSign(), b.turnSign());
        assertEquals(a.apexX(), b.apexX(), 1e-9);
        assertEquals(-a.apexZ() + 1.0, b.apexZ(), 1e-9); // cell z -> -z mirrors centre z c to 1 - c
    }

    @Test
    void deadEndAndStraightAndOffTrack() {
        IceTrackCorridor c = new IceTrackCorridor();
        BoatDriftSim.Surface dead = (x, z) -> x >= 0 && x <= 20 && z >= -1 && z <= 1 ? BoatDriftSim.ICE
                : BoatDriftSim.WALL;
        assertTrue(c.analyze(dead, 2.5, 0.5, YAW_EAST, 1.0, 48));
        assertTrue(c.deadEnd());
        assertFalse(c.corner());
        BoatDriftSim.Surface straight = (x, z) -> z >= -1 && z <= 1 ? BoatDriftSim.ICE : BoatDriftSim.WALL;
        assertFalse(c.analyze(straight, 2.5, 0.5, YAW_EAST, 1.0, 48));
        assertFalse(c.analyze(uniform(BoatDriftSim.LAND, 1000), 2.5, 0.5, YAW_EAST, 1.0, 48));
    }

    @Test
    void simAndCorridorAllocateNothing() {
        BoatDriftSim sim = new BoatDriftSim();
        IceTrackCorridor c = new IceTrackCorridor();
        for (int i = 0; i < 200; i++) {
            sim.run(L_TRACK, 2.5, 0.5, 1.0, 0, YAW_EAST, 0, true, false, false, true, 60);
            c.analyze(L_TRACK, 2.5, 0.5, YAW_EAST, 1.0, 48);
        }
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean smx) || !smx.isThreadAllocatedMemorySupported()) {
            return;
        }
        long tid = Thread.currentThread().threadId();
        long before = smx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500; i++) {
            sim.run(L_TRACK, 2.5, 0.5, 1.0, 0, YAW_EAST, 0, true, false, false, true, 60);
            c.analyze(L_TRACK, 2.5, 0.5, YAW_EAST, 1.0, 48);
        }
        long bytes = smx.getThreadAllocatedBytes(tid) - before;
        assertTrue(bytes < 16_000, "allocated " + bytes);
    }
}
