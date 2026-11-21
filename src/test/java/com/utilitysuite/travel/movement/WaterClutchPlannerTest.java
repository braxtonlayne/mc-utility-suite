package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterClutchPlannerTest {

    static final double TURN = 20.0;
    static final float MIN = 6f;

    /** Flat ground: the player stands at y 10. */
    static Grid flat() {
        Grid g = new Grid();
        g.fill(-10, 9, -10, 10, 9, 10);
        return g;
    }

    static MovementPhysics falling(double y, double vy) {
        MovementPhysics p = new MovementPhysics().set(0.5, y, 0.5, 0, vy, 0, false);
        p.onGround = false;
        return p;
    }

    /** Plan, then follow the trajectory tick by tick re-planning, as the executor does, until the use tick is 0. */
    static int ticksUntilUse(WaterClutchPlanner c, MovementPhysics p, Grid g, float pitch) {
        for (int t = 0; t < 200; t++) {
            if (!c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, pitch, TURN, g)) {
                return -1;
            }
            if (c.useTick() == 0) {
                return t;
            }
            // the head turns toward the aim, capped per tick (as the smoother does)
            float d = c.aimPitch() - pitch;
            pitch += (float) Math.max(-TURN, Math.min(TURN, d));
            p.tick(0, 0, 0f, false, false, g);
        }
        return -1;
    }

    @Test
    void aLethalFallOntoFlatGroundPoursOnTheLandingBlock() {
        Grid g = flat();
        MovementPhysics p = falling(45, 0);
        WaterClutchPlanner c = new WaterClutchPlanner();
        assertTrue(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
        assertTrue(c.landDamage() >= 20f, "a 35-block fall is lethal: " + c.landDamage());
        assertEquals(10, Packed.y(c.waterCell()));
        assertEquals(9, Packed.y(c.support()));
        assertEquals(1, c.face(), "the top face of the block landed on");
        assertEquals(0f, c.damageAfter());
        assertTrue(c.aimPitch() > 60f, "aimed down: " + c.aimPitch());
        assertTrue(c.useTick() > 0 && c.useTick() < c.ticksToLand(), "used late in the fall, before landing");
    }

    @Test
    void reachedOnTheWayDownTheUseTickCountsToZero() {
        Grid g = flat();
        MovementPhysics p = falling(45, 0);
        WaterClutchPlanner c = new WaterClutchPlanner();
        int t = ticksUntilUse(c, p, g, 0f);
        assertTrue(t > 0, "a use tick arrives while falling");
        double eye = p.y + WaterClutchPlanner.EYE;
        assertTrue(eye - 10.0 <= WaterClutchPlanner.REACH, "within reach of the block top: eye " + eye);
        assertTrue(p.y > 10.0, "still above the ground when the bucket is used");
    }

    @Test
    void aShortDropNeedsNoClutch() {
        Grid g = flat();
        MovementPhysics p = falling(15, 0); // 5 blocks: 2 damage
        WaterClutchPlanner c = new WaterClutchPlanner();
        assertFalse(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
    }

    @Test
    void aHurtfulDropUnderTheThresholdIsClutchedWhenItWouldKill() {
        Grid g = flat();
        MovementPhysics p = falling(16, 0); // 6 blocks: 3 damage, under the threshold
        WaterClutchPlanner c = new WaterClutchPlanner();
        assertFalse(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
        assertTrue(c.plan(p, 0f, false, 0, 0, 2f, MIN, 0f, 0f, TURN, g), "with 1 heart left it would kill");
    }

    @Test
    void onTheGroundOrFallingIntoTheVoidPlansNothing() {
        Grid g = flat();
        MovementPhysics stand = new MovementPhysics().set(0.5, 10, 0.5, 0, -0.0784, 0.1, true);
        WaterClutchPlanner c = new WaterClutchPlanner();
        assertFalse(c.plan(stand, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
        MovementPhysics off = new MovementPhysics().set(30.5, 40, 30.5, 0, 0, 0, false);
        off.onGround = false;
        assertFalse(c.plan(off, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g), "nothing to land on");
    }

    @Test
    void aTerminalSpeedFallAlongAWallStillFindsWater() {
        Grid g = new Grid();
        g.fill(-10, -40, -10, 10, -40, 10); // ground: stands at -39
        g.fill(1, -39, -3, 1, 70, 3); // a wall right beside the fall (x = 1 face at x 1.0)
        MovementPhysics p = falling(70, -3.9);
        p.x = 0.6;
        WaterClutchPlanner c = new WaterClutchPlanner();
        assertTrue(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g),
                "lands " + c.lands() + " in " + c.ticksToLand() + " damage " + c.landDamage());
        assertTrue(c.damageAfter() < 20f, "survivable: " + c.damageAfter());
        if (c.damageAfter() > 0f) {
            assertEquals(1, Packed.x(c.support()), "the wall face beside the fall");
            assertEquals(WaterClutchPlanner.WEST_FACE, c.face());
        }
    }

    @Test
    void planningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = flat();
        MovementPhysics p = falling(45, 0);
        WaterClutchPlanner c = new WaterClutchPlanner();
        long bytes = AllocationProbe.measure(50, () -> c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
    }
}
