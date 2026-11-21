package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockAssistPlannerTest {

    static final double TURN = 20.0; // deg per tick, under the 22.5 no-snap cap

    /** Flat ground (feet at y 10) with a 2-high wall across z = 4 and ground beyond. */
    static Grid wall() {
        Grid g = new Grid();
        g.fill(-5, 9, -10, 5, 9, 20);
        g.fill(-5, 10, 4, 5, 11, 4);
        return g;
    }

    /** Ground up to z = 0, a {@code air}-block gap, ground again; a deep pit between. */
    static Grid gap(int air) {
        Grid g = new Grid();
        g.fill(-3, 9, -10, 3, 9, 0);
        g.fill(-3, 9, 1 + air, 3, 9, 30);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    @Test
    void jumpingAtATwoHighWallPlacesABlockToLandOnAndKeepGoing() {
        Grid g = wall();
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 2.2, 0, -0.0784, 0.15, true);
        p.tick(1, 0, 0f, true, true, g); // the jump
        BlockAssistPlanner b = new BlockAssistPlanner();
        assertTrue(b.plan(p, 0f, true, 1, 0, 20f, 0f, 10f, TURN, 0, g),
                "baseline " + b.baseline() + " best " + b.value());
        assertEquals(10, Packed.y(b.cell()), "a block at the wall's foot: land a block up, then jump the wall");
        assertEquals(3, Packed.z(b.cell()));
        assertTrue(b.value() > b.baseline() + BlockAssistPlanner.MARGIN);
    }

    @Test
    void aGapOneBlockTooLongGetsABlockAgainstTheFarSide() {
        Grid g = gap(4); // 4 air: one more than a sprint jump clears
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        assertTrue(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g),
                "baseline " + b.baseline() + " best " + b.value());
        assertEquals(9, Packed.y(b.cell()));
        assertEquals(4, Packed.z(b.cell()), "extends the far edge toward the runner");
        assertEquals(BlockAssistPlanner.NORTH, b.face(), "clicked on the far platform's near face");
    }

    @Test
    void aClearableJumpNeedsNoBlock() {
        Grid g = gap(2);
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        assertFalse(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g));
    }

    @Test
    void aSlowHeadTurnCanBeTooLate() {
        Grid g = gap(4);
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        // looking straight up: the turn to the far face cannot finish before the player is past it
        assertFalse(b.plan(p, 0f, true, 1, 0, 20f, 0f, -90f, 2.0, 1, g));
    }

    @Test
    void prefersFacesAlongTheRun() {
        Grid g = wall();
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 2.2, 0, -0.0784, 0.15, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        assertTrue(b.plan(p, 0f, true, 1, 0, 20f, 0f, 10f, TURN, 0, g));
        assertTrue(Math.abs(b.aimYaw()) < 60f, "aims ahead (yaw " + b.aimYaw() + "), not behind or aside");
    }

    @Test
    void theBrainFlagsAnEdgeNothingClears() {
        MovementPhysics run = new MovementPhysics().set(0.5, 10, 1.27, 0, -0.0784, 0.153, true);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(run, 0f, true, 1, 0, false, 20f, gap(4));
        assertFalse(b.jump());
        assertTrue(b.edgeNoLanding(), "4 air: neither walking nor jumping lands");
        b.decide(run, 0f, true, 1, 0, false, 20f, gap(3));
        assertTrue(b.jump());
        assertFalse(b.edgeNoLanding());
        MovementPhysics middle = new MovementPhysics().set(0.5, 10, -3.5, 0, -0.0784, 0.153, true);
        b.decide(middle, 0f, true, 1, 0, false, 20f, gap(4));
        assertFalse(b.edgeNoLanding(), "not at the edge");
    }

    @Test
    void planningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = gap(4);
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        long bytes = AllocationProbe.measure(50, () -> b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
    }
}
