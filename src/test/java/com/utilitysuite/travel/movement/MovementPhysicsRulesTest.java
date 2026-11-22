package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replica rules of design 2026-11-22 section 7.3 (sneak input scale, crouched hitbox, step-up, sprint loss on wall
 * contact, slipperiness under the feet, jump delay) against hand-computed values, and the guarantee that cube grids
 * still run the old replica bit for bit.
 */
class MovementPhysicsRulesTest {

    private static final double STEADY_VY = -0.0784;
    /** Slipperiness as the shaped grids report it: the float 0.6f of {@code Block.getFriction}. */
    private static final double F = 0.6f;

    // ---- cube grids: bit-identical to the replica before the shape work -------------------------------------------

    private static MovementPhysicsTest.Grid cubeWorld() {
        MovementPhysicsTest.Grid g = new MovementPhysicsTest.Grid();
        g.fill(-6, 0, -6, 6, 0, 6);          // start floor
        g.fill(-6, 0, 9, 6, 0, 14);          // after a 2 gap
        g.fill(-6, 1, 17, 6, 1, 22);         // one up after a 2 gap
        g.fill(-6, 2, 3, 6, 2, 4);           // a wall to run into
        g.fill(-6, 4, -6, 6, 4, -2);         // a ceiling (head hitter)
        g.fill(7, 1, -6, 7, 3, 22);          // a side wall
        return g;
    }

    @Test
    void cubeGridsGiveBitIdenticalResults() {
        MovementPhysicsTest.Grid g = cubeWorld();
        for (long seed = 1; seed <= 12; seed++) {
            Random r = new Random(seed);
            LegacyCubePhysics legacy = new LegacyCubePhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
            MovementPhysics six = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
            MovementPhysics seven = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
            int f = 1;
            int s = 0;
            float yaw = 0f;
            boolean sprint = true;
            boolean jump = false;
            for (int t = 0; t < 1500; t++) {
                if (r.nextInt(6) == 0) {
                    f = r.nextInt(3) - 1;
                    s = r.nextInt(3) - 1;
                    sprint = r.nextBoolean();
                }
                if (r.nextInt(4) == 0) {
                    yaw = (float) (r.nextGaussian() * 60.0);
                }
                jump = r.nextInt(3) == 0 ? !jump : jump;
                legacy.tick(f, s, yaw, sprint, jump, g);
                six.tick(f, s, yaw, sprint, jump, g);
                seven.tick(f, s, yaw, sprint, jump, false, g);
                for (MovementPhysics m : new MovementPhysics[]{six, seven}) {
                    String at = "seed " + seed + " tick " + t;
                    assertEquals(legacy.x, m.x, 0.0, at);
                    assertEquals(legacy.y, m.y, 0.0, at);
                    assertEquals(legacy.z, m.z, 0.0, at);
                    assertEquals(legacy.vx, m.vx, 0.0, at);
                    assertEquals(legacy.vy, m.vy, 0.0, at);
                    assertEquals(legacy.vz, m.vz, 0.0, at);
                    assertEquals(legacy.onGround, m.onGround, at);
                    assertEquals(legacy.fallDistance, m.fallDistance, 0.0, at);
                    assertEquals(legacy.horizontalCollision, m.horizontalCollision, at);
                    assertEquals(legacy.standing(g), m.supported(g), at);
                }
                if (legacy.y < -10) {
                    legacy.set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
                    six.set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
                    seven.set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
                }
            }
        }
    }

    // ---- sneak ------------------------------------------------------------------------------------------------------

    private static ShapeGrid floor() {
        return new ShapeGrid().fill(-8, 0, -8, 8, 0, 60);
    }

    @Test
    void sneakScalesInputByPointThreeOneTickAfterTheKey() {
        ShapeGrid g = floor();
        MovementPhysics p = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        double a = 0.1 * (0.21600002 / (F * F * F));
        double friction = F * 0.91;
        p.tick(1, 0, 0f, false, false, true, g);
        // first sneak tick: the crouch state was read before the key, so full input
        double v1 = 0.98 * a;
        assertEquals(0.5 + v1, p.z, 1e-12);
        assertTrue(p.crouching);
        p.tick(1, 0, 0f, false, false, true, g);
        double v2 = v1 * friction + 0.3 * 0.98 * a;
        assertEquals(0.5 + v1 + v2, p.z, 1e-12);
        for (int t = 0; t < 60; t++) {
            p.tick(1, 0, 0f, false, false, true, g);
        }
        double before = p.z;
        p.tick(1, 0, 0f, false, false, true, g);
        assertEquals(0.0648, p.z - before, 0.0005, "vanilla sneak speed, 1.295 m/s");
        // release: still crouched (scaled) for one tick, then full input
        p.tick(1, 0, 0f, false, false, false, g);
        assertFalse(p.crouching);
    }

    @Test
    void crouchedHitboxIsOneAndAHalfAndStaysWhereStandingDoesNotFit() {
        // ceiling box at 2.7 over z in [-8, 1): a crouched body (1.5) fits under it, a standing one (1.8) does not
        ShapeGrid g = floor();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 0; z++) {
                g.boxes(x, 2, z, 0, 0.7f, 0, 1, 1, 1);
            }
        }
        MovementPhysics p = new MovementPhysics().set(0.5, 1, -2.5, 0, STEADY_VY, 0, true);
        p.crouching = true;
        p.tick(0, 0, 0f, false, true, true, g);
        assertEquals(1.0 + (2.0 + (double) 0.7f - 2.5), p.y, 1e-12, "jump clipped by the crouched head");
        for (int t = 0; t < 10; t++) {
            p.tick(0, 0, 0f, false, false, false, g);
        }
        assertEquals(1.0, p.y, 1e-9);
        assertTrue(p.crouching, "cannot stand up under the ceiling");
        for (int t = 0; t < 80 && p.z < 3; t++) {
            p.tick(1, 0, 0f, false, false, false, g);
        }
        assertFalse(p.crouching, "stands once out from under it");
    }

    // ---- step-up ----------------------------------------------------------------------------------------------------

    @Test
    void walksUpABottomSlabButNotAFullBlock() {
        ShapeGrid g = floor();
        for (int x = -8; x <= 8; x++) {
            g.slabBottom(x, 1, 3);
            g.slabBottom(x, 1, 4);
        }
        MovementPhysics p = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        for (int t = 0; t < 40 && p.z < 3.5; t++) {
            p.tick(1, 0, 0f, false, false, g);
        }
        assertEquals(1.5, p.y, 1e-9, "stepped onto the slab");
        assertTrue(p.z > 3.5);

        ShapeGrid w = floor().fill(-8, 1, 3, 8, 1, 3);
        MovementPhysics q = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        for (int t = 0; t < 40; t++) {
            q.tick(1, 0, 0f, false, false, w);
        }
        assertEquals(1.0, q.y, 1e-9);
        assertEquals(2.7, q.z, 1e-9, "stopped at the block's face");
    }

    @Test
    void stepsOntoAFenceFromTheBlockBesideIt() {
        // a fence in the floor layer is only 0.5 above the feet: within the 0.6 step
        ShapeGrid g = new ShapeGrid().fill(-1, 0, -2, 1, 0, 0);
        g.fence(0, 0, 1, true, true, false, false).fence(0, 0, 2, true, false, false, false);
        MovementPhysics p = new MovementPhysics().set(0.5, 1, -0.5, 0, STEADY_VY, 0, true);
        for (int t = 0; t < 12; t++) {
            p.tick(1, 0, 0f, false, false, g);
        }
        assertEquals(1.5, p.y, 1e-9);
        assertTrue(p.z > 1.0 && p.supported(g), "walking along the fence top, z=" + p.z);
    }

    // ---- sprint loss on wall contact ---------------------------------------------------------------------------------

    @Test
    void sprintStopsAfterAWallHitAndReturnsTheTickAfter() {
        ShapeGrid g = floor().fill(-8, 1, 4, 8, 2, 4);
        MovementPhysics p = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        // the tick that reaches the wall still moves along the input (a minor contact); the next push does not move
        int firstContact = -1;
        int blocked = -1;
        for (int t = 0; t < 40 && blocked < 0; t++) {
            p.tick(1, 0, 0f, true, false, g);
            if (p.horizontalCollision && firstContact < 0) {
                firstContact = t;
            }
            if (p.sprintBlocked) {
                blocked = t;
            }
        }
        assertTrue(firstContact >= 0 && blocked >= firstContact);
        assertEquals(3.7, p.z, 1e-9);
        assertTrue(p.sprinting, "the blocking tick itself was sprinting");
        // turn along the wall: no contact on this tick, but the sprint is still off
        p.tick(1, 0, 90f, true, false, g);
        assertFalse(p.sprinting);
        assertFalse(p.horizontalCollision);
        assertFalse(p.sprintBlocked);
        double xBefore = p.x;
        double vxBefore = p.vx;
        p.tick(1, 0, 90f, true, false, g);
        assertTrue(p.sprinting, "back on the next tick");
        double a = 0.1 * 1.3 * (0.21600002 / (F * F * F));
        assertEquals(vxBefore - 0.98 * a, p.x - xBefore, 1e-12, "sprint acceleration again");
    }

    @Test
    void grazingAWallKeepsTheSprint() {
        // wall face at x = 0, body hugging it, yaw 2 degrees into it: the move stays within 8 degrees of the input
        ShapeGrid g = floor().fill(-1, 1, -8, -1, 2, 60);
        MovementPhysics p = new MovementPhysics().set(0.3, 1, 0.5, 0, STEADY_VY, 0, true);
        for (int t = 0; t < 10; t++) {
            p.tick(1, 0, 2f, true, false, g);
            assertTrue(p.horizontalCollision, "tick " + t);
            assertFalse(p.sprintBlocked, "tick " + t);
            assertTrue(p.sprinting, "tick " + t);
        }
    }

    @Test
    void cubePathKeepsTheOldSprintRule() {
        MovementPhysicsTest.Grid g = new MovementPhysicsTest.Grid();
        g.fill(-8, 0, -8, 8, 0, 20);
        g.fill(-8, 1, 4, 8, 2, 4);
        MovementPhysics p = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        for (int t = 0; t < 30; t++) {
            p.tick(1, 0, 0f, true, false, g);
        }
        assertFalse(p.sprintBlocked, "cube path does not model sprint loss");
    }

    // ---- slipperiness ------------------------------------------------------------------------------------------------

    @Test
    void slipperinessIsReadFromTheBlockUnderTheFeetEveryTick() {
        ShapeGrid g = floor();
        for (int x = -8; x <= 8; x++) {
            for (int z = 5; z <= 60; z++) {
                g.slipperiness(x, 0, z, 0.98f);
            }
        }
        MovementPhysics p = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        while (p.z < 8) {
            p.tick(1, 0, 0f, true, false, g);
        }
        p.tick(0, 0, 0f, false, false, g);
        assertEquals((double) 0.98f, p.slipperiness, 0.0);
        double d0 = p.z;
        p.tick(0, 0, 0f, false, false, g);
        double d1 = p.z - d0;
        p.tick(0, 0, 0f, false, false, g);
        double d2 = p.z - d0 - d1;
        assertEquals(0.98f * 0.91, d2 / d1, 1e-9, "ice friction per tick");
        // back on stone the next ground tick uses 0.6 again
        MovementPhysics q = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        q.slipperiness = 0.98;
        q.tick(0, 0, 0f, false, false, g);
        assertEquals(F, q.slipperiness, 0.0);
    }

    @Test
    void slabReadsTheBlockUnderItLikeVanilla() {
        // getOnPos(0.500001) from a bottom slab top (y + 0.5) is the cell below the slab
        ShapeGrid g = new ShapeGrid().fill(-2, 0, -2, 2, 0, 2).slipperiness(0, 0, 0, 0.98f).slabBottom(0, 1, 0);
        MovementPhysics p = new MovementPhysics().set(0.5, 1.5, 0.5, 0, STEADY_VY, 0, true);
        p.tick(0, 0, 0f, false, false, g);
        assertEquals((double) 0.98f, p.slipperiness, 0.0);
    }

    // ---- jump delay --------------------------------------------------------------------------------------------------

    private static int[] jumpTicks(boolean[] jumpKey) {
        // head hitter: ceiling at 3 over a floor at 0, so each jump is back on the ground two ticks later
        ShapeGrid g = floor().fill(-8, 3, -8, 8, 3, 8);
        MovementPhysics p = new MovementPhysics().set(0.5, 1, 0.5, 0, STEADY_VY, 0, true);
        int[] out = new int[jumpKey.length];
        int n = 0;
        for (int t = 0; t < jumpKey.length; t++) {
            boolean was = p.onGround;
            p.tick(0, 0, 0f, false, jumpKey[t], g);
            if (was && p.y > 1.0 + 1e-9) {
                out[n++] = t;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    @Test
    void heldJumpReJumpsAfterTenTicks() {
        boolean[] held = new boolean[25];
        java.util.Arrays.fill(held, true);
        int[] jumps = jumpTicks(held);
        assertEquals(3, jumps.length);
        assertEquals(0, jumps[0]);
        assertEquals(10, jumps[1]);
        assertEquals(20, jumps[2]);
    }

    @Test
    void freshPressHasNoDelay() {
        boolean[] keys = new boolean[8];
        keys[0] = true;
        keys[3] = true;
        int[] jumps = jumpTicks(keys);
        assertEquals(2, jumps.length);
        assertEquals(3, jumps[1], "landed at tick 2, pressed again at 3");
    }

    @Test
    void copyFromCarriesTheNewState() {
        MovementPhysics a = new MovementPhysics();
        a.crouching = true;
        a.jumpDelay = 7;
        a.sprintBlocked = true;
        a.sprinting = true;
        MovementPhysics b = new MovementPhysics().copyFrom(a);
        assertTrue(b.crouching && b.sprintBlocked && b.sprinting);
        assertEquals(7, b.jumpDelay);
    }
}
