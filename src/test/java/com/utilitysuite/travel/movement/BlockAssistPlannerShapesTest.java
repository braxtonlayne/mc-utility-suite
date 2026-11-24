package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.Packed;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block planner's rollouts on a shaped grid: they must run the same exact-shape physics as the trajectory trace
 * (the planner's world-plus-one-block view forwards the base grid's shapes), not unit cubes.
 */
class BlockAssistPlannerShapesTest {

    static final double TURN = 20.0;

    /** Flat ground (feet at y 10) with a connected fence line (1.5 high) across z = 4 and ground beyond. */
    static ShapeGrid fenceLine() {
        ShapeGrid g = new ShapeGrid();
        g.fill(-5, 9, -10, 5, 9, 20);
        for (int x = -5; x <= 5; x++) {
            g.fence(x, 10, 4, false, false, true, true);
        }
        return g;
    }

    /** Ground up to z = 0, a 2-block gap, bottom slabs (top y 9.5) beyond; a deep pit between. */
    static ShapeGrid slabLanding() {
        ShapeGrid g = new ShapeGrid();
        g.fill(-3, 9, -10, 3, 9, 0);
        for (int x = -3; x <= 3; x++) {
            for (int z = 3; z <= 30; z++) {
                g.slabBottom(x, 9, z);
            }
        }
        g.fill(-10, -40, -10, 10, -40, 40);
        return g;
    }

    @Test
    void theBaselineLandsOnTheSlabsRealTop() {
        ShapeGrid g = slabLanding();
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        assertFalse(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g), "the jump is made without a block");
        assertEquals(9.5, b.baselineFirstY(), 1.0e-6, "first landing on the slab top, not a cube top");
    }

    @Test
    void aFenceLineIsNotJumpedLikeACube() {
        ShapeGrid g = fenceLine();
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 2.2, 0, -0.0784, 0.15, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        // a 1-high cube would be jumped onto; the 1.5-high fence stops the run, so a block is the only way over
        assertTrue(b.plan(p, 0f, true, 1, 0, 20f, 0f, 80f, TURN, 0, g),
                "baseline " + b.baseline() + " first y " + b.baselineFirstY() + " best " + b.value());
        assertEquals(10.0, b.baselineFirstY(), 1.0e-6, "the baseline falls back to the ground in front of the fence");
        assertEquals(10, Packed.y(b.cell()), "a block at the fence's foot");
        assertTrue(b.value() > b.baseline() + BlockAssistPlanner.MARGIN);
    }

    @Test
    void planningOnAShapedGridAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ShapeGrid g = fenceLine();
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 2.2, 0, -0.0784, 0.15, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        for (int i = 0; i < 300; i++) {
            b.plan(p, 0f, true, 1, 0, 20f, 0f, 10f, TURN, 0, g);
        }
        long bytes = AllocationProbe.measure(300, () -> b.plan(p, 0f, true, 1, 0, 20f, 0f, 10f, TURN, 0, g));
        assertTrue(bytes < 1_024, "plan on a shaped grid allocated " + bytes + " bytes over 300 calls");
    }
}
