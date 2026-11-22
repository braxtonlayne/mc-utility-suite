package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FlightController}: the air brake family onto a 1-block pillar (cube replica), walls, the sim-tick bound. */
class FlightControllerTest {

    private static Grid pillar() {
        Grid g = new Grid();
        g.fill(0, 19, 5, 0, 19, 5);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    private static SupportRegion top(Grid g) {
        SupportRegion r = new SupportRegion();
        assertTrue(r.build(g, 0.5, 20.0, 5.5));
        return r;
    }

    /** Past the apex at sprint-jump speed, holding W: the user's keys touch down on the far half and slide off. */
    private static MovementPhysics fast() {
        return new MovementPhysics().set(0.5, 21.0, 3.5, 0, 0, 0.35, false);
    }

    @Test
    void theBrakeLandsWithMoreMarginThanHoldingForward() {
        Grid g = pillar();
        SupportRegion r = top(g);
        FlightController fc = new FlightController();
        assertTrue(fc.plan(fast(), 1, 0, 0f, true, false, r, g));
        assertTrue(fc.userLands(), "the user's keys touch down on the pillar");
        assertTrue(fc.brakeTick() >= 0, "a brake is planned");
        assertTrue(fc.bestMargin() > fc.userMargin(), fc.bestMargin() + " vs " + fc.userMargin());
        assertTrue(fc.brakeForward() == 0, "GUIDE with W held: release only, never turn it around");
        FlightController carry = new FlightController();
        assertTrue(carry.plan(fast(), 1, 0, 0f, true, true, r, g));
        assertTrue(carry.bestMargin() >= fc.bestMargin() - 1e-9, "back allowed: at least as good");
    }

    @Test
    void theBrakeFiresOnlyOnItsTick() {
        Grid g = pillar();
        SupportRegion r = top(g);
        FlightController fc = new FlightController();
        MovementPhysics p = fast();
        boolean braked = false;
        for (int t = 0; t < 20 && !p.onGround; t++) {
            fc.plan(p, 1, 0, 0f, true, false, r, g);
            int f = fc.brakeNow() ? fc.brakeForward() : 1;
            braked |= fc.brakeNow();
            p.tick(f, 0, 0f, f > 0, false, g);
        }
        assertTrue(braked);
        assertTrue(p.onGround);
        assertTrue(r.margin(p.x, p.z) >= 0.0, "on the pillar at touchdown: " + p.z);
    }

    @Test
    void aWallOnTheWayFailsEveryCandidate() {
        Grid g = pillar();
        g.fill(0, 20, 4, 0, 21, 4);
        SupportRegion r = top(g);
        FlightController fc = new FlightController();
        assertFalse(fc.plan(fast(), 1, 0, 0f, true, true, r, g), "any wall contact fails a candidate");
    }

    @Test
    void groundOrNoRegionPlansNothing() {
        Grid g = pillar();
        FlightController fc = new FlightController();
        assertFalse(fc.plan(new MovementPhysics().set(0.5, 20, 5.5, 0, 0, 0, true), 1, 0, 0f, true, true, top(g), g));
        assertFalse(fc.plan(fast(), 1, 0, 0f, true, true, new SupportRegion(), g));
    }

    @Test
    void simulatedTicksPerPlanAreBounded() {
        Grid g = pillar();
        SupportRegion r = top(g);
        FlightController fc = new FlightController();
        int max = 0;
        for (double y = 19.0; y <= 23.0; y += 0.25) {
            for (double vz = 0.0; vz <= 0.5; vz += 0.05) {
                for (int f = -1; f <= 1; f++) {
                    fc.plan(new MovementPhysics().set(0.5, y, 2.0, 0, 0.3, vz, false), f, 0, 0f, true, true, r, g);
                    max = Math.max(max, fc.simTicks());
                }
            }
        }
        assertTrue(max > 0);
        assertTrue(max <= FlightController.MAX_SIM_TICKS, "simulated " + max + " ticks in one plan");
    }

    @Test
    void planAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = pillar();
        SupportRegion r = top(g);
        FlightController fc = new FlightController();
        MovementPhysics p = fast();
        long bytes = AllocationProbe.measure(300, () -> fc.plan(p, 1, 0, 0f, true, true, r, g));
        assertTrue(bytes < 1_024, "allocated " + bytes + " bytes");
    }
}
