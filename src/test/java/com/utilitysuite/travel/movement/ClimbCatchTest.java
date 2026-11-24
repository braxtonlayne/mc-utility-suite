package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Ladder catch (course J49 / J85): the jump's goal is a climbable face. Before {@link ClimbCatch} the assist planned no
 * target there, so an early press fell short and Takeover bridged the gap with a block instead.
 */
class ClimbCatchTest {
    private static final float S = 1f / 16f;

    /** A pad (z -4..0, top 20) or a lone wall post (z 0, top 20.5); a 3-wide ladder face at z 3 on a tower at z 4. */
    static ShapeGrid scene(boolean post, boolean ladders) {
        ShapeGrid g = new ShapeGrid();
        if (post) {
            g.wallPost(0, 19, 0);
        } else {
            g.fill(-1, 19, -4, 1, 19, 0);
        }
        g.fill(-1, 17, 4, 1, 22, 4);
        if (ladders) {
            for (int x = -1; x <= 1; x++) {
                for (int y = 18; y <= 22; y++) {
                    g.boxes(x, y, 3, 0, 0, 13 * S, 1, 1, 1); // ladder facing north (hangs on the tower's -Z face)
                    g.surface(x, y, 3, MovementPhysics.SURFACE_CLIMBABLE);
                }
            }
        }
        return g;
    }

    /**
     * Runs W + sprint toward the tower. The user's jump key goes down at {@code pressAt} (z) and stays down until the
     * body leaves the ground; NaN: never pressed.
     */
    static String run(ShapeGrid g, MovementPhysics p, double pressAt, boolean auto, boolean assist) {
        ClimbCatch c = new ClimbCatch();
        boolean down = false;
        boolean flew = false;
        for (int t = 0; t < 80; t++) {
            if (!flew && !Double.isNaN(pressAt) && p.z >= pressAt) {
                down = true;
            }
            boolean jump = down;
            if (assist) {
                c.decide(p, 0f, 0f, 1, 0, true, down, auto, g);
                assertTrue(c.simTicks <= ClimbCatch.MAX_SIM_TICKS + ClimbCatch.SIM_TICKS + 1,
                        "budget " + c.simTicks);
                jump = c.jump() || (down && !c.withholdJump());
            }
            p.tick(1, 0, 0f, true, jump, g);
            if (!p.onGround) {
                flew = true;
                down = false;
            }
            if (g.surface((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z))
                    == MovementPhysics.SURFACE_CLIMBABLE) {
                return "caught";
            }
            if (p.y < 16) {
                return String.format("fell at z%.2f", p.z);
            }
        }
        return String.format("stuck at z%.2f y%.2f", p.z, p.y);
    }

    @Test
    void anEarlyPressFromThePadWaitsAndCatchesTheLadder() {
        int caught = 0;
        int tried = 0;
        int shortUnassisted = 0;
        StringBuilder out = new StringBuilder();
        for (double press = -2.0; press <= 0.81; press += 0.2) {
            tried++;
            ShapeGrid g = scene(false, true);
            String r = run(g, new MovementPhysics().set(0.5, 20, -3.5, 0, -0.0784, 0, true), press, false, true);
            if (r.equals("caught")) {
                caught++;
            } else {
                out.append(String.format(" [press z%.1f: %s]", press, r));
            }
            if (!run(g, new MovementPhysics().set(0.5, 20, -3.5, 0, -0.0784, 0, true), press, false, false)
                    .equals("caught")) {
                shortUnassisted++;
            }
        }
        System.out.println("CLIMB PAD " + caught + "/" + tried + " (unassisted misses " + shortUnassisted + ")" + out);
        assertTrue(shortUnassisted > 0, "the scene should punish an early press");
        assertTrue(caught == tried, caught + " of " + tried + out);
    }

    @Test
    void takeoverCatchesTheLadderWithNoPress() {
        StringBuilder out = new StringBuilder();
        int caught = 0;
        int tried = 0;
        for (double z = -3.5; z <= -0.4; z += 0.5) {
            tried++;
            String r = run(scene(false, true), new MovementPhysics().set(0.5, 20, z, 0, -0.0784, 0, true), Double.NaN,
                    true, true);
            if (r.equals("caught")) {
                caught++;
            } else {
                out.append(String.format(" [start z%.1f: %s]", z, r));
            }
        }
        System.out.println("CLIMB TAKEOVER " + caught + "/" + tried + out);
        assertTrue(caught == tried, caught + " of " + tried + out);
    }

    /** J85: the ladder jump right after landing on a wall post (the J84 landing's speed carries over). */
    @Test
    void takeoverCatchesFromTheWallPostItJustLandedOn() {
        StringBuilder out = new StringBuilder();
        int caught = 0;
        int tried = 0;
        for (double vz : new double[] {0.05, 0.12, 0.2, 0.28}) {
            for (double z : new double[] {0.35, 0.5, 0.65}) {
                tried++;
                String r = run(scene(true, true), new MovementPhysics().set(0.5, 20.5, z, 0, -0.0784, vz, true),
                        Double.NaN, true, true);
                if (r.equals("caught")) {
                    caught++;
                } else {
                    out.append(String.format(" [z%.2f vz%.2f: %s]", z, vz, r));
                }
            }
        }
        System.out.println("CLIMB POST " + caught + "/" + tried + out);
        assertTrue(caught * 10 >= tried * 9, caught + " of " + tried + out);
    }

    /** Far from the face the user's jumps are theirs: a press 8 blocks out is not held (no 2-second delayed jump). */
    @Test
    void aPressFarFromTheFaceIsNotHeld() {
        ShapeGrid s = scene(false, true);
        for (int z = -12; z <= -5; z++) {
            for (int x = -1; x <= 1; x++) {
                s.cube(x, 19, z);
            }
        }
        ClimbCatch c = new ClimbCatch();
        c.decide(new MovementPhysics().set(0.5, 20, -6.0, 0, -0.0784, 0.28, true), 0f, 0f, 1, 0, true, true, false, s);
        assertFalse(c.withholdJump(), "a press 8 blocks out goes through");
        assertFalse(c.active());
    }

    /** Walking into a wall below an unreachable ladder: the walk stops at the wall, the budget holds. */
    @Test
    void aWallUnderAnUnreachableLadderStaysInBudget() {
        ShapeGrid g = new ShapeGrid();
        g.fill(-1, 19, -12, 1, 19, 2);
        g.fill(-1, 20, 3, 1, 21, 3); // a 2-high wall right ahead
        for (int x = -1; x <= 1; x++) {
            g.boxes(x, 22, 3, 0, 0, 13 * S, 1, 1, 1);
            g.surface(x, 22, 3, MovementPhysics.SURFACE_CLIMBABLE);
        }
        ClimbCatch c = new ClimbCatch();
        for (double z = -8; z <= 2.2; z += 0.5) {
            c.decide(new MovementPhysics().set(0.5, 20, z, 0, -0.0784, 0.28, true), 0f, 0f, 1, 0, true, false, true, g);
            assertTrue(c.simTicks <= ClimbCatch.MAX_SIM_TICKS + ClimbCatch.SIM_TICKS + 1, "z " + z + ": " + c.simTicks);
        }
    }

    /** A ladder face two blocks below the takeoff is seen and caught. */
    @Test
    void aLadderBelowTheTakeoffIsCaught() {
        ShapeGrid g = new ShapeGrid();
        g.fill(-1, 19, -4, 1, 19, 0); // pad, top 20
        g.fill(-1, 13, 4, 1, 18, 4); // tower, top 19
        for (int x = -1; x <= 1; x++) {
            for (int y = 14; y <= 18; y++) {
                g.boxes(x, y, 3, 0, 0, 13 * S, 1, 1, 1);
                g.surface(x, y, 3, MovementPhysics.SURFACE_CLIMBABLE);
            }
        }
        String r = run(g, new MovementPhysics().set(0.5, 20, -3.5, 0, -0.0784, 0, true), Double.NaN, true, true);
        System.out.println("CLIMB BELOW " + r);
        assertTrue(r.equals("caught"), r);
    }

    /** A jump that will not catch any more lets go in the air, so the block assist and the clutch can act. */
    @Test
    void aMissedCatchLetsGoInTheAir() {
        ShapeGrid g = scene(false, true);
        ClimbCatch c = new ClimbCatch();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -0.5, 0, -0.0784, 0.28, true);
        boolean wasActive = false;
        for (int t = 0; t < 40 && p.y > 16; t++) {
            c.decide(p, 0f, 0f, 1, 0, true, false, true, g);
            wasActive |= c.active();
            boolean jump = c.jump();
            // the user lets go of W right after the takeoff: the flight falls short
            int f = p.onGround ? 1 : 0;
            p.tick(f, 0, 0f, f > 0, jump, g);
            if (!p.onGround && wasActive && p.vy < 0 && !c.active()) {
                return; // let go while falling
            }
        }
        assertTrue(false, "the catch never let go of a falling body that cannot catch");
    }

    @Test
    void decideAllocatesNothing() {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.utilitysuite.core.AllocationProbe.supported());
        ShapeGrid g = scene(false, true);
        ClimbCatch c = new ClimbCatch();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -1.5, 0, -0.0784, 0.2, true);
        for (int i = 0; i < 2000; i++) {
            c.decide(p, 0f, 0f, 1, 0, true, true, true, g); // warm up the JIT
        }
        long bytes = com.utilitysuite.core.AllocationProbe.measure(500, () -> c.decide(p, 0f, 0f, 1, 0, true, true, true, g));
        assertTrue(bytes < 1024, "decide allocated " + bytes + " bytes");
    }

    @Test
    void noClimbableAheadLeavesTheJumpAlone() {
        ShapeGrid g = scene(false, false);
        ClimbCatch c = new ClimbCatch();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -1.0, 0, -0.0784, 0.2, true);
        c.decide(p, 0f, 0f, 1, 0, true, true, true, g);
        assertFalse(c.active());
        assertFalse(c.withholdJump());
        assertFalse(c.jump());
    }

    // ---- facing: the real collision box decides which side can be entered ------------------------------------------

    /** A ladder facing away (its slab on the near side, nothing behind it) cannot be entered from that side. */
    @Test
    void aLadderWhoseBackIsTowardTheRunnerIsNotCaught() {
        ShapeGrid g = new ShapeGrid();
        g.fill(-1, 19, -4, 1, 19, 0);
        for (int x = -1; x <= 1; x++) {
            for (int y = 18; y <= 22; y++) {
                g.boxes(x, y, 3, 0, 0, 0, 1, 1, 3 * S); // slab on the -Z side of the cell: the runner meets its back
                g.surface(x, y, 3, MovementPhysics.SURFACE_CLIMBABLE);
            }
        }
        ClimbCatch c = new ClimbCatch();
        c.decide(new MovementPhysics().set(0.5, 20, -1.5, 0, -0.0784, 0.2, true), 0f, 0f, 1, 0, true, true, true, g);
        assertFalse(c.active(), "no jump from the front of this slab reaches the cell");
        assertFalse(c.withholdJump());
        String r = run(g, new MovementPhysics().set(0.5, 20, -3.5, 0, -0.0784, 0, true), Double.NaN, true, true);
        assertFalse(r.equals("caught"), r);
    }

    /** A vine has no collision: it is caught from the front, with nothing behind it. */
    @Test
    void aVineIsCaught() {
        ShapeGrid g = new ShapeGrid();
        g.fill(-1, 19, -4, 1, 19, 0);
        for (int x = -1; x <= 1; x++) {
            for (int y = 18; y <= 22; y++) {
                g.surface(x, y, 3, MovementPhysics.SURFACE_CLIMBABLE); // no boxes
            }
        }
        String r = run(g, new MovementPhysics().set(0.5, 20, -3.5, 0, -0.0784, 0, true), Double.NaN, true, true);
        assertTrue(r.equals("caught"), r);
    }

    // ---- in the air ---------------------------------------------------------------------------------------------

    /** Ticks a body in the air with the given keys; the catch's steering overrides them like the assist does. */
    static String air(ShapeGrid g, MovementPhysics p, int f, int s, boolean auto) {
        ClimbCatch c = new ClimbCatch();
        for (int t = 0; t < 60; t++) {
            c.decide(p, 0f, 0f, f, s, true, false, auto, g);
            assertTrue(c.simTicks <= ClimbCatch.MAX_SIM_TICKS + ClimbCatch.SIM_TICKS + 1, "budget " + c.simTicks);
            int kf = c.steering() ? c.steerForward() : f;
            int ks = c.steering() ? c.steerStrafe() : s;
            assertFalse(c.jump(), "no jump press in the air");
            p.tick(kf, ks, 0f, kf > 0, false, g);
            if (g.surface((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z))
                    == MovementPhysics.SURFACE_CLIMBABLE) {
                return "caught";
            }
            if (p.onGround || p.y < 16) {
                return String.format("landed at z%.2f y%.2f", p.z, p.y);
            }
        }
        return "stuck";
    }

    /** Knocked off the pad toward the tower with W held: the user's keys already catch, no steering. */
    @Test
    void aBodyFallingOffThePadTowardTheLadderCatchesWithTheUsersKeys() {
        ShapeGrid g = scene(false, true);
        ClimbCatch c = new ClimbCatch();
        MovementPhysics p = new MovementPhysics().set(0.5, 20.5, 0.8, 0, 0.1, 0.25, false);
        c.decide(p, 0f, 0f, 1, 0, true, false, false, g);
        assertTrue(c.active());
        assertFalse(c.steering());
        String r = air(g, new MovementPhysics().set(0.5, 20.5, 0.8, 0, 0.1, 0.25, false), 1, 0, false);
        assertTrue(r.equals("caught"), r);
    }

    /** No key held, drifting: the assist presses W itself (keys only) on a free axis. */
    @Test
    void anIdleBodyIsSteeredIntoTheLadderByKeysOnly() {
        ShapeGrid g = scene(false, true);
        String r = air(g, new MovementPhysics().set(0.5, 20.5, 0.8, 0, 0.1, 0.2, false), 0, 0, false);
        assertTrue(r.equals("caught"), r);
        ClimbCatch c = new ClimbCatch();
        c.decide(new MovementPhysics().set(0.5, 20.5, 0.8, 0, 0.1, 0.2, false), 0f, 0f, 0, 0, true, false, false, g);
        assertTrue(c.steering());
        assertTrue(c.steerForward() == 1 && c.steerStrafe() == 0);
    }

    /** Manual input wins: the user holding back is not overridden unless the assist may take over. */
    @Test
    void theUsersOpposingKeyIsNotOverriddenWithoutTakeover() {
        ShapeGrid g = scene(false, true);
        MovementPhysics p = new MovementPhysics().set(0.5, 20.5, 0.8, 0, 0.1, 0.3, false);
        ClimbCatch c = new ClimbCatch();
        c.decide(p, 0f, 0f, -1, 0, false, false, false, g);
        assertFalse(c.steering(), "S held: not steered forward");
        c.decide(p, 0f, 0f, -1, 0, false, false, true, g);
        assertTrue(c.active() && c.steering());
    }

    /** A fall that no key set can bring to the face is not owned: the clutch and the block assist act. */
    @Test
    void aFallThatCannotReachAnyFaceIsNotCaught() {
        ShapeGrid g = scene(false, true);
        ClimbCatch c = new ClimbCatch();
        c.decide(new MovementPhysics().set(0.5, 30, -3.5, 0, -0.5, 0, false), 0f, 0f, 1, 0, true, false, true, g);
        assertFalse(c.active());
        assertFalse(c.steering());
        // armed on a catching fall, then knocked far off line: lets go
        MovementPhysics p = new MovementPhysics().set(0.5, 20.5, 0.8, 0, 0.1, 0.25, false);
        c.decide(p, 0f, 0f, 1, 0, true, false, true, g);
        assertTrue(c.active());
        p.set(0.5, 20.5, -3.5, 0, -0.9, -0.5, false);
        c.decide(p, 0f, 0f, 1, 0, true, false, true, g);
        assertFalse(c.active(), "no longer catches: let go");
    }

    @Test
    void theInAirCheckAllocatesNothing() {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.utilitysuite.core.AllocationProbe.supported());
        ShapeGrid g = scene(false, true);
        ClimbCatch c = new ClimbCatch();
        MovementPhysics p = new MovementPhysics().set(0.5, 20.5, 0.8, 0, 0.1, 0.2, false);
        for (int i = 0; i < 2000; i++) {
            c.decide(p, 0f, 0f, 0, 0, true, false, true, g);
        }
        long bytes = com.utilitysuite.core.AllocationProbe.measure(500, () -> c.decide(p, 0f, 0f, 0, 0, true, false, true, g));
        assertTrue(bytes < 1024, "in-air decide allocated " + bytes + " bytes");
    }
}
