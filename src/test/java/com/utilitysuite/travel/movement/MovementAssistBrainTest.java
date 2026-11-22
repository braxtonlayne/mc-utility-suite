package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAssistBrainTest {

    private static Grid gap(int air) {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 19, 1 + air, 3, 19, 30);
        return g;
    }

    /** The last tick on the block before a gap, sprinting at steady speed toward +Z. */
    private static MovementPhysics atEdge() {
        return new MovementPhysics().set(0.5, 20, 1.31, 0, 0, 0.153, true);
    }

    @Test
    void jumpsAtTheEdgeOfAGapItCanClear() {
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, gap(3));
        assertTrue(b.jump());
        b.decide(atEdge(), 0f, true, 0, 0, false, 20f, gap(3));
        assertFalse(b.jump(), "not pressing forward: not the user's run, no jump");
        b.edgeJump = false;
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, gap(3));
        assertFalse(b.jump(), "setting off");
    }

    @Test
    void edgeJumpWithTheRealOnGroundVerticalVelocity() {
        // in game a player on the ground carries vy = -0.0784 (gravity, cancelled by the floor each tick)
        MovementPhysics run = new MovementPhysics().set(0.5, 20, 1.27, 0, -0.0784, 0.153, true);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(run, 0f, true, 1, 0, false, 20f, gap(3));
        assertTrue(b.jump(), "the step-off tick is detected even though Y collides first");
        MovementPhysics early = new MovementPhysics().set(0.5, 20, 0.6, 0, -0.0784, 0.153, true);
        b.decide(early, 0f, true, 1, 0, false, 20f, gap(3));
        assertFalse(b.jump(), "not yet at the edge");
    }

    @Test
    void withSafeWalkOnADamagingJumpIsNotAValidJump() {
        // 4 air blocks, the far side 6 blocks lower (a jump lands with fall damage), a deep pit between
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 13, 5, 3, 13, 30);
        g.fill(-40, -40, -40, 79, -40, 79);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, g);
        assertTrue(b.jump(), "without SafeWalk a hurtful landing beats falling into the pit");
        b.safeOnly = true;
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.jump(), "with SafeWalk on, a jump that costs health is invalid: SafeWalk holds the edge");
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, gap(3));
        assertTrue(b.jump(), "a clean jump still takes priority over SafeWalk");
    }

    @Test
    void airSteeringFillsTheFreeAxisAndNeverFightsAHeldKey() {
        // falling toward a lethal pit; a ledge just to the right (-X) is the only survivable landing
        Grid g = new Grid();
        g.fill(-40, -10, -40, 79, -10, 79);
        g.fill(-2, 15, 1, -1, 15, 3);
        MovementPhysics air = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.2, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(air, 0f, false, 1, 0, false, 20f, g);
        assertTrue(b.corrected());
        assertEquals(-1, b.strafe(), "presses right for the user");
        assertEquals(MovementAssistBrain.KEEP, b.forward(), "the forward key the user holds stays");
        b.decide(air, 0f, false, 1, 1, false, 20f, g);
        assertEquals(MovementAssistBrain.KEEP, b.strafe(), "the user holds left: their choice wins");
    }

    @Test
    void leavesAGoodTrajectoryAlone() {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 30); // flat ground everywhere
        MovementPhysics air = new MovementPhysics().set(0.5, 20.8, 0.5, 0, 0.1, 0.25, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(air, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.corrected());
        assertTrue(b.hasLanding());
        assertEquals(20.0, b.landY(), 1e-9);
        assertEquals(0f, b.landDamage());
    }

    @Test
    void sprintHopOnOpenGroundAndHeadHittersButNotIntoAWall() {
        Grid open = new Grid();
        open.fill(-3, 19, -30, 3, 19, 40);
        MovementPhysics run = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.153, true);
        MovementAssistBrain b = new MovementAssistBrain();
        b.sprintHop = true;
        b.decide(run, 0f, true, 1, 0, false, 20f, open);
        assertTrue(b.jump(), "a sprint jump outruns sprinting on open flat ground");
        Grid tunnel = new Grid();
        tunnel.fill(-3, 19, -30, 3, 19, 40);
        tunnel.fill(-3, 22, -30, 3, 22, 40); // 2-block-high tunnel
        b.decide(run, 0f, true, 1, 0, false, 20f, tunnel);
        assertTrue(b.jump(), "head-hitter sprint jumps are faster in vanilla too");
        Grid wall = new Grid();
        wall.fill(-3, 19, -30, 3, 19, 40);
        wall.fill(-3, 20, 2, 3, 21, 2); // a wall two blocks ahead
        b.decide(run, 0f, true, 1, 0, false, 20f, wall);
        assertFalse(b.jump(), "no hop into a wall");
    }

    @Test
    void predictsAnUnsurvivableFallAsLethal() {
        Grid g = new Grid();
        g.fill(-40, -30, -40, 79, -30, 79);
        MovementPhysics air = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.1, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(air, 0f, false, 0, 0, false, 20f, g);
        assertTrue(b.lethal());
        assertFalse(b.corrected(), "nothing better exists: keys untouched");
    }

    @Test
    void reachFindsLandingsAcrossAClearableGapOnly() {
        JumpReach r = new JumpReach();
        Grid three = gap(3);
        MovementPhysics stand = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0, true);
        r.compute(stand, three);
        boolean across = false;
        for (int i = 0; i < r.count(); i++) {
            across |= com.utilitysuite.travel.Packed.z(r.cell(i)) >= 4;
        }
        // from standing (no run-up) a sprint jump covers less than from a run: it still reaches its own side
        assertTrue(r.count() > 0, "some landings found");
        assertFalse(across, "a standing sprint jump does not clear 3 air blocks");
    }

    @Test
    void sprintOutputOnlyWithATargetAndNeverWhileSprinting() {
        // no target: the edge-jump states never ask for sprint or shape a takeoff, sprinting or not
        MovementAssistBrain b = new MovementAssistBrain();
        for (boolean sprinting : new boolean[]{true, false}) {
            b.decide(atEdge(), 0f, sprinting, 1, 0, false, 20f, gap(3));
            assertFalse(b.sprint());
            assertFalse(b.takeoffShaped());
            assertFalse(b.shaper().found());
        }
        // a walking run-up to a target 3 air blocks out: sprint is requested once, and never again while sprinting
        Grid g = gap(3);
        MovementAssistBrain t = new MovementAssistBrain();
        t.setIntentTarget(0, 19, 4, 0.9);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -1.7, 0, -0.0784, 0, true);
        boolean sprinting = false;
        int requests = 0;
        for (int i = 0; i < 30 && p.onGround; i++) {
            t.decide(p, 0f, sprinting, 1, 0, false, 20f, g);
            if (t.sprint()) {
                assertFalse(sprinting, "sprint is only requested while not sprinting");
                sprinting = true;
                requests++;
            }
            assertEquals(MovementAssistBrain.KEEP, t.forward(), "forward stays the user's");
            p.tick(1, t.strafe() == MovementAssistBrain.KEEP ? 0 : t.strafe(), 0f, sprinting, t.jump(), g);
        }
        assertTrue(requests <= 1, "at most one sprint tick: " + requests);
    }

    @Test
    void airborneDecisionAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = new Grid();
        g.fill(-40, -10, -40, 79, -10, 79);
        g.fill(-2, 15, 1, -1, 15, 3);
        MovementPhysics air = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.2, false);
        MovementAssistBrain b = new MovementAssistBrain();
        long bytes = AllocationProbe.measure(500, () -> b.decide(air, 0f, false, 1, 0, false, 20f, g));
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes");
    }
}
