package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallClutchPlannerTest {

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
    static int ticksUntilUse(FallClutchPlanner c, MovementPhysics p, Grid g, float pitch) {
        return ticksUntilUse(c, p, g, pitch, FallClutchPlanner.WATER_ONLY);
    }

    static int ticksUntilUse(FallClutchPlanner c, MovementPhysics p, Grid g, float pitch, int kinds) {
        float yaw = 0f;
        for (int t = 0; t < 200; t++) {
            if (!c.plan(p, 0f, false, 0, 0, 20f, MIN, yaw, pitch, TURN, kinds, g)) {
                return -1;
            }
            if (c.useTick() == 0) {
                return t;
            }
            // the head turns toward the aim, capped per tick (as the smoother does)
            // (along the straight line between the two looks, so the turn takes as long as the planner assumed)
            float dy = (float) BlockAssistPlanner.wrap((double) c.aimYaw() - yaw);
            float dp = c.aimPitch() - pitch;
            double len = Math.sqrt(dy * dy + dp * dp);
            double step = len <= TURN ? 1.0 : TURN / len;
            yaw += (float) (dy * step);
            pitch += (float) (dp * step);
            p.tick(0, 0, 0f, false, false, g);
        }
        return -1;
    }

    @Test
    void aLethalFallOntoFlatGroundPoursOnTheLandingBlock() {
        Grid g = flat();
        MovementPhysics p = falling(45, 0);
        FallClutchPlanner c = new FallClutchPlanner();
        assertTrue(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
        assertTrue(c.landDamage() >= 20f, "a 35-block fall is lethal: " + c.landDamage());
        assertEquals(10, Packed.y(c.cell()));
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
        FallClutchPlanner c = new FallClutchPlanner();
        int t = ticksUntilUse(c, p, g, 0f);
        assertTrue(t > 0, "a use tick arrives while falling");
        double eye = p.y + FallClutchPlanner.EYE;
        assertTrue(eye - 10.0 <= FallClutchPlanner.REACH, "within reach of the block top: eye " + eye);
        assertTrue(p.y > 10.0, "still above the ground when the bucket is used");
    }

    @Test
    void aShortDropNeedsNoClutch() {
        Grid g = flat();
        MovementPhysics p = falling(15, 0); // 5 blocks: 2 damage
        FallClutchPlanner c = new FallClutchPlanner();
        assertFalse(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
    }

    @Test
    void aHurtfulDropUnderTheThresholdIsClutchedWhenItWouldKill() {
        Grid g = flat();
        MovementPhysics p = falling(16, 0); // 6 blocks: 3 damage, under the threshold
        FallClutchPlanner c = new FallClutchPlanner();
        assertFalse(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
        assertTrue(c.plan(p, 0f, false, 0, 0, 2f, MIN, 0f, 0f, TURN, g), "with 1 heart left it would kill");
    }

    @Test
    void onTheGroundOrFallingIntoTheVoidPlansNothing() {
        Grid g = flat();
        MovementPhysics stand = new MovementPhysics().set(0.5, 10, 0.5, 0, -0.0784, 0.1, true);
        FallClutchPlanner c = new FallClutchPlanner();
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
        FallClutchPlanner c = new FallClutchPlanner();
        assertTrue(c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g),
                "lands " + c.lands() + " in " + c.ticksToLand() + " damage " + c.landDamage());
        assertTrue(c.damageAfter() < 20f, "survivable: " + c.damageAfter());
        if (c.damageAfter() > 0f) {
            assertEquals(1, Packed.x(c.support()), "the wall face beside the fall");
            assertEquals(FallClutchPlanner.WEST_FACE, c.face());
        }
    }

    static boolean plan(FallClutchPlanner c, MovementPhysics p, Grid g, int kinds) {
        return c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, kinds, g);
    }

    @Test
    void waterWinsWhenItIsAtHand() {
        MovementPhysics p = falling(45, 0);
        FallClutchPlanner c = new FallClutchPlanner();
        int all = 0;
        for (FallClutchPlanner.Kind k : FallClutchPlanner.KINDS) {
            all |= k.bit();
        }
        assertTrue(plan(c, p, flat(), all));
        assertEquals(FallClutchPlanner.Kind.WATER, c.kind());
    }

    @Test
    void withoutABucketSlimeOrPowderSnowOnTheLandingBlockLeaveNoDamage() {
        for (FallClutchPlanner.Kind k : new FallClutchPlanner.Kind[] {
                FallClutchPlanner.Kind.SLIME, FallClutchPlanner.Kind.POWDER_SNOW}) {
            MovementPhysics p = falling(45, 0);
            FallClutchPlanner c = new FallClutchPlanner();
            assertTrue(plan(c, p, flat(), k.bit()), k.name());
            assertEquals(k, c.kind());
            assertEquals(0f, c.damageAfter(), k.name());
            assertEquals(10, Packed.y(c.cell()), "placed on the landing block: " + k);
            assertEquals(FallClutchPlanner.UP_FACE, c.face());
        }
    }

    @Test
    void aHayBaleCutsTheDamageToAFifth() {
        MovementPhysics p = falling(45, 0); // 35 blocks: 32 damage, lethal
        FallClutchPlanner c = new FallClutchPlanner();
        assertTrue(plan(c, p, flat(), FallClutchPlanner.Kind.HAY.bit()));
        assertEquals(FallClutchPlanner.Kind.HAY, c.kind());
        // landing on the bale top at y 11: a 34-block fall, (34 - 3) * 0.2 rounded up
        assertEquals(7f, c.damageAfter());
    }

    @Test
    void aBlockIsPlacedOnlyWhileTheBodyIsStillAboveItsCell() {
        MovementPhysics p = falling(45, 0);
        FallClutchPlanner c = new FallClutchPlanner();
        assertTrue(ticksUntilUse(c, p, flat(), 0f, FallClutchPlanner.Kind.HAY.bit()) > 0);
        assertTrue(p.y >= Packed.y(c.cell()) + 1.0, "feet " + p.y + " above the bale cell " + Packed.y(c.cell()));
    }

    @Test
    void aCobwebOnAWallBesideATerminalSpeedFallBreaksIt() {
        for (FallClutchPlanner.Kind k : new FallClutchPlanner.Kind[] {FallClutchPlanner.Kind.COBWEB}) {
            Grid g = new Grid();
            g.fill(-10, -40, -10, 10, -40, 10);
            g.fill(1, -39, -3, 1, 70, 3); // the wall: its west face at x 1.0
            MovementPhysics p = falling(70, -3.9);
            p.x = 0.6;
            FallClutchPlanner c = new FallClutchPlanner();
            assertTrue(plan(c, p, g, k.bit()), k + ": lands in " + c.ticksToLand() + " damage " + c.landDamage());
            assertEquals(k, c.kind());
            assertTrue(c.damageAfter() < 20f, k + " survivable: " + c.damageAfter());
            if (k == FallClutchPlanner.Kind.LADDER) {
                assertEquals(FallClutchPlanner.WEST_FACE, c.face(), "a ladder hangs on the wall");
            }
        }
    }

    @Test
    void aLadderOnAWallBreaksASlowerFall() {
        // terminal speed is too fast for a ladder: the feet cell is beyond reach the tick before. From 26 blocks up it
        // is not (about 2 blocks a tick at the end).
        Grid g = new Grid();
        g.fill(-10, -40, -10, 10, -40, 10);
        g.fill(1, -39, -3, 1, 70, 3);
        MovementPhysics p = falling(-13, 0);
        p.x = 0.6;
        FallClutchPlanner c = new FallClutchPlanner();
        int t = ticksUntilUse(c, p, g, 0f, FallClutchPlanner.Kind.LADDER.bit());
        assertTrue(t > 0, "a ladder use tick arrives");
        assertEquals(FallClutchPlanner.Kind.LADDER, c.kind());
        assertEquals(FallClutchPlanner.WEST_FACE, c.face(), "hung on the wall");
        assertTrue(c.damageAfter() < c.landDamage(), c.damageAfter() + " < " + c.landDamage());
    }

    @Test
    void aLadderNeedsAWall() {
        MovementPhysics p = falling(45, 0);
        FallClutchPlanner c = new FallClutchPlanner();
        assertFalse(plan(c, p, flat(), FallClutchPlanner.Kind.LADDER.bit() | FallClutchPlanner.Kind.VINE.bit()),
                "open air: nothing to hang a ladder or vine on");
    }

    @Test
    void planningEveryKindAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = new Grid();
        g.fill(-10, -40, -10, 10, -40, 10);
        g.fill(1, -39, -3, 1, 70, 3);
        MovementPhysics p = falling(70, -3.9);
        p.x = 0.6;
        FallClutchPlanner c = new FallClutchPlanner();
        int blocks = FallClutchPlanner.Kind.LADDER.bit() | FallClutchPlanner.Kind.HAY.bit()
                | FallClutchPlanner.Kind.COBWEB.bit();
        long bytes = AllocationProbe.measure(50, () -> plan(c, p, g, blocks));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
    }

    @Test
    void planningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = flat();
        MovementPhysics p = falling(45, 0);
        FallClutchPlanner c = new FallClutchPlanner();
        long bytes = AllocationProbe.measure(50, () -> c.plan(p, 0f, false, 0, 0, 20f, MIN, 0f, 0f, TURN, g));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
    }
}
