package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the replica against known vanilla numbers and parkour facts, plus the search on top of it. */
class MovementPhysicsTest {

    /** A small voxel world: solid cells in a fixed box, everything else air. */
    static final class Grid implements MovementPhysics.Voxels {
        static final int MIN = -40;
        static final int SIZE = 120;
        final boolean[] cells = new boolean[SIZE * SIZE * SIZE];

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        cells[idx(x, y, z)] = true;
                    }
                }
            }
        }

        private static int idx(int x, int y, int z) {
            return ((x - MIN) * SIZE + (y - MIN)) * SIZE + (z - MIN);
        }

        @Override
        public boolean solid(int x, int y, int z) {
            if (x < MIN || y < MIN || z < MIN || x >= MIN + SIZE || y >= MIN + SIZE || z >= MIN + SIZE) {
                return false;
            }
            return cells[idx(x, y, z)];
        }
    }

    /** Floor at y=19 (player stands at y=20) for z in [zFrom, zTo], x in [-3, 3]. */
    private static Grid floor(Grid g, int zFrom, int zTo, int y) {
        g.fill(-3, y, zFrom, 3, y, zTo);
        return g;
    }

    @Test
    void standingJumpHasVanillaApexAndAirtime() {
        Grid g = floor(new Grid(), -10, 10, 19);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0, true);
        double apex = 0;
        int air = 0;
        p.tick(0, 0, 0f, false, true, g);
        while (!p.onGround && air < 40) {
            apex = Math.max(apex, p.y - 20);
            air++;
            p.tick(0, 0, 0f, false, false, g);
        }
        assertEquals(1.2522, apex, 0.001, "jump apex");
        assertEquals(11, air, "ticks airborne after the jump tick (12 including it)");
        assertEquals(20.0, p.y, 1e-9);
    }

    @Test
    void sprintSpeedConvergesToVanilla() {
        Grid g = floor(new Grid(), -10, 39, 19);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -9.5, 0, 0, 0, true);
        double last = p.z;
        double step = 0;
        for (int i = 0; i < 40; i++) {
            p.tick(1, 0, 0f, true, false, g);
            step = p.z - last;
            last = p.z;
        }
        assertEquals(0.2806, step, 0.0005, "blocks per tick sprinting (5.612 m/s)");
    }

    /** Runs up and edge-jumps (the assist's rule: jump on the last tick before walking off) over a gap. */
    private static MovementPhysics runAndJump(int gap) {
        Grid g = new Grid();
        floor(g, -30, 0, 19); // run-up ends at z=0 (edge at z=1.0)
        floor(g, 1 + gap, 30, 19); // landing side
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -29.5, 0, 0, 0, true);
        MovementPhysics probe = new MovementPhysics();
        boolean jumped = false;
        for (int t = 0; t < 200; t++) {
            boolean jump = false;
            if (!jumped && p.onGround) {
                probe.copyFrom(p).tick(1, 0, 0f, true, false, g);
                jump = !probe.onGround; // the next tick would leave the ground: jump now
            }
            jumped |= jump;
            p.tick(1, 0, 0f, true, jump, g);
            if (jumped && p.onGround && p.z > 1) {
                break;
            }
            if (p.y < 0) {
                break;
            }
        }
        return p;
    }

    @Test
    void edgeTimedSprintJumpClearsThreeAirBlocksButNotFour() {
        // parkour's "4b jump": three blocks of air is the flat sprint-jump limit; four needs Speed or momentum tricks
        MovementPhysics three = runAndJump(3);
        assertTrue(three.onGround && three.y == 20.0 && three.z > 4, "3-air gap cleared: z=" + three.z + " y=" + three.y);
        MovementPhysics four = runAndJump(4);
        assertFalse(four.y == 20.0 && four.z > 5, "4-air gap is not a flat sprint jump: z=" + four.z + " y=" + four.y);
    }

    @Test
    void edgeDecisionJumpsAtAGapAndNotOnFlatGround() {
        Grid g = new Grid();
        floor(g, -30, 0, 19);
        floor(g, 4, 30, 19);
        JumpSearch js = new JumpSearch();
        // the last tick on the block (centre 0.31 past the edge, steady sprint velocity), as in a real run-up
        MovementPhysics p = new MovementPhysics().set(0.5, 20, 1.31, 0, 0, 0.153, true);
        assertTrue(js.shouldEdgeJump(p, 0f, true, 20f, g), "jump the 3-block gap instead of falling in");
        Grid flat = floor(new Grid(), -30, 30, 19);
        assertFalse(js.shouldEdgeJump(p, 0f, true, 20f, flat), "no reason to hop on flat ground");
    }

    @Test
    void airSteeringPicksTheKeysThatReachTheLanding() {
        // airborne heading +Z; the only landing is a pillar off to the left (+X is left when facing +Z)
        Grid g = new Grid();
        g.fill(1, 0, 2, 2, 19, 5);
        MovementPhysics p = new MovementPhysics().set(0.5, 21, 0.5, 0, 0.2, 0.25, false);
        JumpSearch js = new JumpSearch();
        assertTrue(js.steer(p, 0f, true, 20f, g), "some keys land on the pillar");
        assertEquals(1, js.strafe(), "strafe left toward the pillar");
        assertEquals(20.0, js.landY(), 1e-9);
    }

    @Test
    void withNoJumpPossibleItPicksTheLeastDamagingFall() {
        // airborne over a deep pit: straight ahead is a 30-block drop, a ledge 4 below sits to the right
        Grid g = new Grid();
        g.fill(-40, -10, -40, 79, -10, 79); // the pit floor far below
        g.fill(-2, 15, 1, -1, 15, 3); // ledge just to the right (-X), top at y=16
        MovementPhysics p = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.2, false);
        JumpSearch js = new JumpSearch();
        assertTrue(js.steer(p, 0f, false, 20f, g));
        assertEquals(-1, js.strafe(), "steer right onto the ledge");
        assertEquals(16.0, js.landY(), 1e-9);
        assertTrue(js.landDamage() <= 1f, "a short drop: " + js.landDamage());
    }

    @Test
    void searchAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = new Grid();
        floor(g, -30, 0, 19);
        floor(g, 4, 30, 19);
        JumpSearch js = new JumpSearch();
        MovementPhysics p = new MovementPhysics().set(0.5, 20.6, 1.5, 0, 0.1, 0.3, false);
        long bytes = AllocationProbe.measure(2_000, () -> js.steer(p, 0f, true, 20f, g));
        assertTrue(bytes < 1_024, "steer allocated " + bytes + " bytes");
    }
}
