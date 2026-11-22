package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteJumpPlannerTest {

    private static Grid gap(int air, int farFloorY) {
        Grid g = new Grid();
        g.fill(-2, 19, -10, 2, 19, 5);
        g.fill(-2, farFloorY, 6 + air, 2, farFloorY, 25);
        return g;
    }

    private static MovementPhysics standing() {
        return new MovementPhysics().set(0.5, 20, 1.5, 0, 0, 0, true);
    }

    @Test
    void takeoffSearchFindsARunUpForAThreeBlockGap() {
        RouteJumpPlanner j = new RouteJumpPlanner();
        j.begin(Integer.MAX_VALUE);
        long[] far = {Packed.pack(0, 20, 9), Packed.pack(0, 20, 10), Packed.pack(0, 20, 11)};
        j.aimRoute(far, 0, 2, 0);
        MovementPhysics running = new MovementPhysics().set(0.5, 20, 2.5, 0, 0, 0.28, true);
        int d = j.searchTakeoff(running, 0f, gap(3, 19));
        assertTrue(d >= 0, "a run-up lands the 3-block gap");
        assertEquals(0f, j.landDamage());
        assertTrue(j.landedIndex() >= 0);
    }

    @Test
    void fiveBlockGapHasNoTakeoff() {
        RouteJumpPlanner j = new RouteJumpPlanner();
        j.begin(Integer.MAX_VALUE);
        for (int z = 11; z <= 13; z++) {
            j.aimCell(0, 20, z);
            assertTrue(j.searchTakeoff(standing(), 0f, gap(5, 19)) < 0);
        }
    }

    @Test
    void aDamagingLandingIsRejected() {
        RouteJumpPlanner j = new RouteJumpPlanner();
        j.begin(Integer.MAX_VALUE);
        for (int z = 8; z <= 11; z++) {
            j.aimCell(0, 18, z);
            assertTrue(j.searchTakeoff(standing(), 0f, gap(2, 17)) < 0, "2 lower costs a heart, z " + z);
        }
        boolean any = false;
        for (int z = 8; z <= 11; z++) {
            j.aimCell(0, 19, z);
            any |= j.searchTakeoff(standing(), 0f, gap(2, 18)) >= 0;
        }
        assertTrue(any, "one lower is fine");
    }

    @Test
    void budgetIsAHardCap() {
        RouteJumpPlanner j = new RouteJumpPlanner();
        j.begin(25);
        j.aimCell(0, 20, 12);
        assertTrue(j.searchTakeoff(standing(), 0f, gap(5, 19)) < 0);
        assertTrue(j.ticks() <= 25);
        assertTrue(j.exhausted());
    }

    @Test
    void hopOnAFlatGainsAndStaysOnTheChord() {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 2, 19, 30);
        RouteJumpPlanner j = new RouteJumpPlanner();
        j.begin(Integer.MAX_VALUE);
        MovementPhysics run = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.28, true);
        assertTrue(j.hopGains(run, 0f, 0, 1, 20, 0.45, g));
        assertFalse(j.hopGains(run, 0f, 0, 1, 1.0, 0.45, g), "would overshoot the steering point");
    }
}
