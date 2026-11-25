package com.utilitysuite.travel.autopilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiptideLaunchTest {

    @Test
    void strengthPerLevel() {
        assertEquals(0, RiptideLaunchPlanner.strength(0), 1e-9);
        assertEquals(1.5, RiptideLaunchPlanner.strength(1), 1e-9);
        assertEquals(3.0, RiptideLaunchPlanner.strength(3), 1e-9);
    }

    @Test
    void straightUpHasNoTravelAndHighApex() {
        RiptideLaunchPlanner p = new RiptideLaunchPlanner();
        RiptideLaunchPlanner.Plan out = p.evaluate(new RiptideLaunchPlanner.Plan(), -90f, 3, true, false);
        assertTrue(out.feasible);
        assertTrue(out.apexHeight > 15, "apex " + out.apexHeight);
        assertEquals(0, out.apexTravel, 1e-6);
    }

    @Test
    void elytraPlanClimbsSteeperAndFliesFarther() {
        RiptideLaunchPlanner p = new RiptideLaunchPlanner();
        RiptideLaunchPlanner.Plan glide = p.plan(new RiptideLaunchPlanner.Plan(), 3, true, true);
        RiptideLaunchPlanner.Plan ballistic = p.plan(new RiptideLaunchPlanner.Plan(), 3, true, false);
        System.out.println("glide pitch " + glide.pitch + " dist " + glide.totalDistance + " apex " + glide.apexHeight
                + " / ballistic pitch " + ballistic.pitch + " dist " + ballistic.totalDistance);
        assertTrue(glide.feasible && ballistic.feasible);
        assertTrue(glide.totalDistance > ballistic.totalDistance * 2);
        assertTrue(glide.pitch < ballistic.pitch, "elytra launch should be steeper");
        assertTrue(glide.pitch < -30);
    }

    @Test
    void higherLevelGoesFarther() {
        RiptideLaunchPlanner p = new RiptideLaunchPlanner();
        double d1 = p.plan(new RiptideLaunchPlanner.Plan(), 1, true, true).totalDistance;
        double d3 = p.plan(new RiptideLaunchPlanner.Plan(), 3, true, true).totalDistance;
        assertTrue(d3 > d1);
        assertFalse(p.plan(new RiptideLaunchPlanner.Plan(), 0, true, true).feasible);
    }

    @Test
    void sequenceAimsChargesReleasesAndDeploysAtApex() {
        RiptideSequence s = new RiptideSequence();
        assertEquals(RiptideSequence.Action.NONE, s.step(true, true, 20, 0, true, false, true));
        assertEquals(RiptideSequence.Action.HOLD_USE, s.step(true, true, 3, 0, true, false, true));
        RiptideSequence.Action a;
        int holds = 0;
        while ((a = s.step(true, true, 3, 0, true, false, true)) == RiptideSequence.Action.HOLD_USE) {
            holds++;
        }
        assertEquals(RiptideSequence.Action.RELEASE_USE, a);
        assertTrue(holds + 1 >= RiptideLaunchPlanner.MIN_CHARGE_TICKS);
        s.step(true, true, 3, 2.5, false, false, true); // RELEASE -> RISE
        assertEquals(RiptideSequence.Action.NONE, s.step(true, true, 3, 1.5, false, false, true));
        assertEquals(RiptideSequence.Action.NONE, s.step(true, true, 3, 0.5, false, false, true));
        assertEquals(RiptideSequence.Action.DEPLOY_JUMP, s.step(true, true, 3, -0.05, false, false, true));
        assertEquals(RiptideSequence.Action.GLIDE, s.step(true, true, 3, -0.1, false, true, true));
        assertEquals(RiptideSequence.Phase.GLIDE, s.phase());
    }

    @Test
    void sequenceAbortsWhenDryOrNoTrident() {
        RiptideSequence s = new RiptideSequence();
        assertEquals(RiptideSequence.Action.ABORT_DRY, s.step(true, false, 0, 0, true, false, true));
        s.reset();
        s.step(true, true, 0, 0, true, false, true);
        assertEquals(RiptideSequence.Action.ABORT_DRY, s.step(true, false, 0, 0, true, false, true));
        s.reset();
        assertEquals(RiptideSequence.Action.ABORT_NO_TRIDENT, s.step(false, true, 0, 0, true, false, true));
    }

    @Test
    void sequenceWithoutElytraEndsOnLanding() {
        RiptideSequence s = new RiptideSequence();
        s.step(true, true, 0, 0, true, false, false);
        while (s.step(true, true, 0, 0, true, false, false) == RiptideSequence.Action.HOLD_USE) {
            // charging
        }
        s.step(true, true, 0, 2, false, false, false);
        for (int i = 0; i < 5; i++) {
            assertEquals(RiptideSequence.Action.NONE, s.step(true, true, 0, -0.5, false, false, false));
        }
        assertEquals(RiptideSequence.Action.LANDED, s.step(true, true, 0, 0, true, false, false));
    }
}
