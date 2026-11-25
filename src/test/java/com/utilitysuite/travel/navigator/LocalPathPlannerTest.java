package com.utilitysuite.travel.navigator;

import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysics;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPathPlannerTest {

    /** Ground: every cell with y below 0 is solid, except carved holes; extra solid blocks on top. */
    private static final class Grid implements MovementPhysics.Voxels {
        final LongOpenHashSet solid = new LongOpenHashSet();
        final LongOpenHashSet holes = new LongOpenHashSet();
        int floorBelow = 0;

        @Override
        public boolean solid(int x, int y, int z) {
            long p = Packed.pack(x, y, z);
            if (solid.contains(p)) {
                return true;
            }
            return y < floorBelow && !holes.contains(p);
        }

        void block(int x, int y, int z) {
            solid.add(Packed.pack(x, y, z));
        }

        /** Carves a column hole in the ground from y=-1 down to y=-depth. */
        void hole(int x, int z, int depth) {
            for (int y = -1; y >= -depth; y--) {
                holes.add(Packed.pack(x, y, z));
            }
        }
    }

    private final LocalPathPlanner planner = new LocalPathPlanner();

    private LocalPathPlanner.Result plan(Grid g, int sx, int sz, int gx, int gy, int gz, int range, int budget) {
        planner.begin(g, LocalPathPlanner.Hazards.NONE, Packed.pack(sx, 0, sz), Packed.pack(gx, gy, gz), range, budget);
        return planner.run();
    }

    private void assertWalkable(Grid g) {
        for (int i = 0; i < planner.pathLength(); i++) {
            long c = planner.pathCell(i);
            int x = Packed.x(c);
            int y = Packed.y(c);
            int z = Packed.z(c);
            assertFalse(g.solid(x, y, z), "feet inside a block at " + x + "," + y + "," + z);
            assertFalse(g.solid(x, y + 1, z), "head inside a block at " + x + "," + y + "," + z);
            assertTrue(g.solid(x, y - 1, z), "no floor at " + x + "," + y + "," + z);
        }
    }

    @Test
    void flatGroundFindsAStraightRoute() {
        Grid g = new Grid();
        assertEquals(LocalPathPlanner.Result.FOUND, plan(g, 0, 0, 10, 0, 0, 0, 5000));
        assertEquals(11, planner.pathLength());
        assertEquals(Packed.pack(0, 0, 0), planner.pathCell(0));
        assertEquals(Packed.pack(10, 0, 0), planner.pathCell(10));
        assertWalkable(g);
    }

    @Test
    void wallIsWalkedAround() {
        Grid g = new Grid();
        // a 3-high wall across x=5 from z=-4..4
        for (int z = -4; z <= 4; z++) {
            for (int y = 0; y < 3; y++) {
                g.block(5, y, z);
            }
        }
        assertEquals(LocalPathPlanner.Result.FOUND, plan(g, 0, 0, 10, 0, 0, 0, 20_000));
        assertWalkable(g);
        boolean wentAround = false;
        for (int i = 0; i < planner.pathLength(); i++) {
            if (Math.abs(Packed.z(planner.pathCell(i))) >= 5) {
                wentAround = true;
            }
        }
        assertTrue(wentAround);
    }

    @Test
    void oneBlockStepIsClimbedButTwoBlocksAreNot() {
        Grid g = new Grid();
        // a raised platform at x>=5 (top at y=0, so standing at y=1), fully across z
        for (int x = 5; x <= 12; x++) {
            for (int z = -70; z <= 70; z++) {
                g.block(x, 0, z);
            }
        }
        assertEquals(LocalPathPlanner.Result.FOUND, plan(g, 0, 0, 8, 1, 0, 0, 20_000));
        assertWalkable(g);
        assertEquals(1, Packed.y(planner.pathCell(planner.pathLength() - 1)));

        Grid tall = new Grid();
        for (int x = 5; x <= 12; x++) {
            for (int z = -70; z <= 70; z++) {
                tall.block(x, 0, z);
                tall.block(x, 1, z);
            }
        }
        LocalPathPlanner.Result r = plan(tall, 0, 0, 8, 2, 0, 0, 200_000);
        assertFalse(r == LocalPathPlanner.Result.FOUND, "a two-block wall is not climbable without blocks");
    }

    @Test
    void dropsDownUpToThreeBlocks() {
        Grid g = new Grid();
        g.floorBelow = -3; // ground at y=-4 top -> standing at y=-3
        // start ledge
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -3; y < 0; y++) {
                    g.block(x, y, z);
                }
            }
        }
        assertEquals(LocalPathPlanner.Result.FOUND, plan(g, 0, 0, 6, -3, 0, 0, 20_000));
        assertWalkable(g);
    }

    @Test
    void oneBlockGapIsJumpedOverADeepPit() {
        Grid g = new Grid();
        // a trench one block wide at x=3, across all z in the search box, 10 deep
        for (int z = -70; z <= 70; z++) {
            g.hole(3, z, 10);
        }
        assertEquals(LocalPathPlanner.Result.FOUND, plan(g, 0, 0, 6, 0, 0, 0, 20_000));
        assertWalkable(g);
        boolean jumped = false;
        for (int i = 1; i < planner.pathLength(); i++) {
            if (Math.abs(Packed.x(planner.pathCell(i)) - Packed.x(planner.pathCell(i - 1))) == 2) {
                jumped = true;
            }
        }
        assertTrue(jumped);
    }

    @Test
    void islandBehindAWideDeepTrenchIsUnreachable() {
        Grid g = new Grid();
        // island at 10..12 x 10..12; ring 3 wide around it carved 10 deep
        for (int x = 7; x <= 15; x++) {
            for (int z = 7; z <= 15; z++) {
                if (x < 10 || x > 12 || z < 10 || z > 12) {
                    g.hole(x, z, 10);
                }
            }
        }
        assertEquals(LocalPathPlanner.Result.FAILED, plan(g, 0, 0, 11, 0, 11, 0, 200_000));
        assertEquals(0, planner.pathLength());
    }

    @Test
    void exhaustedBudgetReturnsTheBestPartialRoute() {
        Grid g = new Grid();
        for (int z = -6; z <= 6; z++) {
            for (int y = 0; y < 3; y++) {
                g.block(20, y, z);
            }
        }
        LocalPathPlanner.Result r = plan(g, 0, 0, 40, 0, 0, 0, 60);
        assertEquals(LocalPathPlanner.Result.PARTIAL, r);
        assertTrue(planner.expanded() <= 60);
        long end = planner.pathCell(planner.pathLength() - 1);
        assertTrue(Packed.x(end) > 0, "the partial route makes progress toward the goal");
        assertWalkable(g);
    }

    @Test
    void farGoalOutsideTheBoxGivesAPartialRouteTowardIt() {
        Grid g = new Grid();
        LocalPathPlanner.Result r = plan(g, 0, 0, 500, 0, 0, 2, LocalPathPlanner.DEFAULT_NODE_BUDGET);
        assertEquals(LocalPathPlanner.Result.PARTIAL, r);
        long end = planner.pathCell(planner.pathLength() - 1);
        assertTrue(Packed.x(end) >= 40, "ends far along toward the goal, was " + Packed.x(end));
        assertTrue(Packed.x(end) <= LocalPathPlanner.MAX_RADIUS);
    }

    @Test
    void forbiddenHazardCellsAreAvoided() {
        Grid g = new Grid();
        // lava strip at x=4, z -3..3, with walls beyond so the detour goes around the strip ends
        planner.begin(g, (x, y, z) -> x == 4 && Math.abs(z) <= 3 ? LocalPathPlanner.Hazards.FORBIDDEN : 0,
                Packed.pack(0, 0, 0), Packed.pack(8, 0, 0), 0, 20_000);
        assertEquals(LocalPathPlanner.Result.FOUND, planner.run());
        for (int i = 0; i < planner.pathLength(); i++) {
            long c = planner.pathCell(i);
            assertFalse(Packed.x(c) == 4 && Math.abs(Packed.z(c)) <= 3);
        }
    }

    @Test
    void budgetedStepsResumeAcrossCalls() {
        Grid g = new Grid();
        planner.begin(g, null, Packed.pack(0, 0, 0), Packed.pack(30, 0, 5), 0, 20_000);
        int calls = 0;
        while (planner.step(10) == LocalPathPlanner.Result.SEARCHING) {
            calls++;
        }
        assertTrue(calls > 1);
        assertEquals(LocalPathPlanner.Result.FOUND, planner.result());
        assertEquals(Packed.pack(30, 0, 5), planner.pathCell(planner.pathLength() - 1));
    }
}
