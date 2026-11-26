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
    void anAllowedBlocksListLimitsTheSupportsClickedOn() {
        Grid g = gap(4);
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        b.supportFilter = (x, y, z) -> false; // a map that allows building on none of these blocks
        assertFalse(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g), "no allowed support: no block");
        BlockAssistPlanner free = new BlockAssistPlanner();
        assertTrue(free.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g));
        long sup = free.support();
        b.supportFilter = (x, y, z) -> Packed.pack(x, y, z) == sup; // only the block the free plan clicks on
        assertTrue(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g));
        assertEquals(sup, b.support());
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

    /** As {@link #gap} but over a wide flat floor 9 below (feet at 1): survivable, and a gap-free runway. */
    static Grid gapOverFloor(int air) {
        Grid g = new Grid();
        g.fill(-3, 9, -10, 3, 9, 0);
        g.fill(-3, 9, 1 + air, 3, 9, 30);
        g.fill(-20, 0, -20, 20, 0, 60);
        return g;
    }

    @Test
    void theDropPenaltyIsHeavyBeyondTheAllowance() {
        assertEquals(0.0, BlockAssistPlanner.dropPenalty(2.0, 0.0), 1e-9, "a step-down inside FREE_DROP is free");
        assertEquals(0.0, BlockAssistPlanner.dropPenalty(3.0, 0.0), 1e-9);
        assertEquals(6 * BlockAssistPlanner.DROP_WEIGHT, BlockAssistPlanner.dropPenalty(9.0, 0.0), 1e-9,
                "the course's glass floor 9 below: 6 blocks past the free drop");
        assertEquals(0.0, BlockAssistPlanner.dropPenalty(9.0, 9.5), 1e-9, "a drop the player aims at is free");
    }

    @Test
    void aJumpThatLandsWithoutHelpGetsNoBlockEvenOverAFloor() {
        // owner, 2026-11-23: blocks only when the jump could not be made otherwise
        for (int air = 1; air <= 3; air++) {
            Grid g = gapOverFloor(air);
            MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
            p.tick(1, 0, 0f, true, true, g);
            BlockAssistPlanner b = new BlockAssistPlanner();
            b.groundY = 10;
            assertFalse(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g), air + " air clears on its own");
            assertEquals(10.0, b.baselineFirstY(), 1e-6, air + " air: the baseline lands on the far side");
        }
    }

    @Test
    void aGapTooLongOverAFloorStillGetsTheMidGapBlockNotTheFloor() {
        Grid g = gapOverFloor(4);
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        b.groundY = 10;
        assertTrue(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g),
                "baseline " + b.baseline() + " best " + b.value());
        assertTrue(b.baselineFirstY() < 2.0, "without a block the run lands on the floor");
        assertEquals(9, Packed.y(b.cell()), "the save keeps the run on its height");
        assertTrue(b.value() > b.baseline() + BlockAssistPlanner.MARGIN);
    }

    @Test
    void theFloorRunwayNeverOutscoresTheRoute() {
        // the baseline drops 9 and then sprints along a flat floor: its value carries the heavy drop penalty
        Grid g = gapOverFloor(4);
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        b.groundY = 10;
        b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g);
        assertTrue(b.baseline() < -BlockAssistPlanner.DROP_WEIGHT * 5, "baseline " + b.baseline());
    }

    @Test
    void aDropThePlayerAimsAtIsAJumpMade() {
        // the same floor, but the player's intent target is the floor itself: dropping there is what they want
        Grid g = gapOverFloor(6);
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 1.28, 0, -0.0784, 0.153, true);
        p.tick(1, 0, 0f, true, true, g);
        BlockAssistPlanner b = new BlockAssistPlanner();
        b.groundY = 10;
        b.dropAllowance = 9.5;
        assertFalse(b.plan(p, 0f, true, 1, 0, 20f, 0f, 20f, TURN, 0, g), "no block against the player's own drop");
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

    /** A grid whose given z-plane (between two heights) is replaceable, as tall grass or a snow layer would be. */
    private static MovementPhysics.Voxels withReplaceablePlane(Grid g, int z, int y0, int y1) {
        return new MovementPhysics.Voxels() {
            @Override
            public boolean solid(int x, int y, int zz) {
                return g.solid(x, y, zz);
            }

            @Override
            public boolean replaceable(int x, int y, int zz) {
                return zz == z && y >= y0 && y <= y1;
            }
        };
    }

    @Test
    void aReplaceableBlockIsNeverTheSupportFace() {
        Grid g = wall();
        MovementPhysics p = new MovementPhysics().set(0.5, 10, 2.2, 0, -0.0784, 0.15, true);
        p.tick(1, 0, 0f, true, true, g);
        assertTrue(new BlockAssistPlanner().plan(p, 0f, true, 1, 0, 20f, 0f, 10f, TURN, 0, g));
        // the same wall, now grass: its side is no place to click; the floor under the block still is
        MovementPhysics.Voxels grassy = withReplaceablePlane(g, 4, 10, 11);
        BlockAssistPlanner b = new BlockAssistPlanner();
        if (b.plan(p, 0f, true, 1, 0, 20f, 0f, 10f, TURN, 0, grassy)) {
            long s = b.support();
            assertFalse(grassy.replaceable(Packed.x(s), Packed.y(s), Packed.z(s)),
                    "support " + Packed.x(s) + "," + Packed.y(s) + "," + Packed.z(s));
        }
        // when every neighbour that could support the block is replaceable, nothing is planned
        MovementPhysics.Voxels allGrass = new MovementPhysics.Voxels() {
            @Override
            public boolean solid(int x, int y, int zz) {
                return g.solid(x, y, zz);
            }

            @Override
            public boolean replaceable(int x, int y, int zz) {
                return true;
            }
        };
        assertFalse(new BlockAssistPlanner().plan(p, 0f, true, 1, 0, 20f, 0f, 10f, TURN, 0, allGrass));
    }
}
