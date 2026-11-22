package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.travel.movement.MovementPhysics;
import com.utilitysuite.travel.movement.PhysicsBodySim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComboKeeperTest {

    static final int FLOOR_Y = HitTradeSolverTest.FLOOR_Y;
    static final MovementPhysics.Voxels FLAT = HitTradeSolverTest.FLAT;
    /** Flat floor with a three-high wall from z = 5 on. */
    static final MovementPhysics.Voxels WALL = (x, y, z) -> y < FLOOR_Y || (z >= 5 && y < FLOOR_Y + 3);
    /** Flat floor with a 12-deep pit for 3 <= z < 9. */
    static final MovementPhysics.Voxels PIT = (x, y, z) -> y < FLOOR_Y - 12 || (y < FLOOR_Y && (z < 3 || z >= 9));

    static ComboKeeper keeper(MovementPhysics.Voxels v) {
        return new ComboKeeper(HitTradeSolverTest.provider(v));
    }

    /** We sprint into a grounded opponent 2.8 blocks ahead (+Z) and hit it; its attack is a few ticks from ready. */
    static TradeInput sprintingIn(double dist) {
        TradeInput in = HitTradeSolverTest.duel(dist);
        in.svz = 0.28;
        in.sSprinting = true;
        in.sReadyIn = 0;
        in.oReadyIn = 4;
        in.oForward = 1;
        in.oSprinting = true;
        return in;
    }

    static String describe(ComboKeeper k) {
        return String.format("reset=%d x%d hitIn=%d air=%d land=(%.2f, %.2f, %.2f) keys f=%d s=%d sprint=%b",
                k.resetKind(), k.resetTicks(), k.hitInTicks(), k.airTicks(), k.landX(), k.landY(), k.landZ(),
                k.forward(), k.strafe(), k.sprint());
    }

    /** Result of playing a plan out with independent bodies that follow the keeper's own opponent rule. */
    record Run(int hitTick, boolean inReach, boolean sprinting, boolean endedEarly) {
    }

    /**
     * Plays the plan: our body holds the keeper's keys (facing the opponent), the opponent drifts while knocked up and
     * walks at us once grounded. {@code pushAt}/{@code push} shove the opponent sideways once to test re-anchoring.
     */
    static Run execute(ComboKeeper k, TradeInput in, boolean sprintHit, MovementPhysics.Voxels v, int pushAt,
                       double push) {
        BodySim self = new PhysicsBodySim(v).set(in.sx, in.sy, in.sz, in.svx, in.svy, in.svz, in.sOnGround);
        BodySim opp = new PhysicsBodySim(v).set(in.ox, in.oy, in.oz, in.ovx, in.ovy, in.ovz, in.oOnGround);
        opp.knockback(0.4, in.sx - in.ox, in.sz - in.oz);
        if (sprintHit) {
            double yaw = Math.toRadians(in.sYaw);
            opp.knockback(0.5, Math.sin(yaw), -Math.cos(yaw));
            self.scaleHorizontal(0.6);
        }
        for (int t = 1; t <= ComboKeeper.MAX_PLAN_TICKS + 1; t++) {
            double dx = opp.x() - self.x();
            double dz = opp.z() - self.z();
            float sYaw = HitTradeSolver.yawToward(dx, dz);
            float oYaw = HitTradeSolver.yawToward(-dx, -dz);
            boolean sprinting = k.sprint() && k.forward() > 0;
            self.tick(k.forward(), k.strafe(), sYaw, k.sprint(), false);
            if (opp.onGround() && opp.vy() <= 0.0) {
                opp.tick(in.oForward, in.oStrafe, oYaw, in.oSprinting && in.oForward > 0, false);
            } else {
                opp.tick(0, 0, oYaw, false, false);
            }
            if (t == pushAt) {
                opp.set(opp.x() + push, opp.y(), opp.z(), opp.vx(), opp.vy(), opp.vz(), opp.onGround());
            }
            in.sx = self.x();
            in.sy = self.y();
            in.sz = self.z();
            in.svx = self.vx();
            in.svy = self.vy();
            in.svz = self.vz();
            in.sOnGround = self.onGround();
            in.ox = opp.x();
            in.oy = opp.y();
            in.oz = opp.z();
            in.ovx = opp.vx();
            in.ovy = opp.vy();
            in.ovz = opp.vz();
            in.oOnGround = opp.onGround();
            if (!k.step(in)) {
                return new Run(-1, false, false, true);
            }
            if (k.hitInTicks() == 0) {
                boolean reach = HitTradeSolver.reachSq(self.x(), self.y(), self.z(), opp.x(), opp.y(), opp.z())
                        <= in.sReach * in.sReach;
                return new Run(t, reach, sprinting, false);
            }
        }
        return new Run(-1, false, false, false);
    }

    @Test
    void sprintHitOnGroundedOpponentLandsTheNextHitChargedAndInReach() {
        ComboKeeper k = keeper(FLAT);
        TradeInput in = sprintingIn(2.8);
        assertTrue(k.begin(in, true, true));
        System.out.println("grounded: " + describe(k));
        assertTrue(k.active());
        assertNotEquals(ComboKeeper.RESET_NONE, k.resetKind(), "a sprint hit needs a fresh sprint");
        assertTrue(k.resetTicks() >= 1 && k.resetTicks() <= 3);
        assertTrue(k.airTicks() > 0, "a grounded opponent is knocked up");
        int planned = k.hitInTicks();
        assertTrue(planned >= 12 && planned <= ComboKeeper.MAX_PLAN_TICKS, "sword charge is 12 ticks: " + planned);
        assertTrue(k.landZ() > in.oz, "knocked away from us");

        Run run = execute(k, in, true, FLAT, -1, 0);
        assertFalse(run.endedEarly(), "plan dropped while the opponent followed the arc");
        assertEquals(planned, run.hitTick());
        assertTrue(run.hitTick() >= 12, "full charge (12.5 cooldown, ready 0.5 early)");
        assertTrue(run.inReach(), "in reach on the hit tick");
        assertTrue(run.sprinting(), "sprint regained for the follow-up");
        assertFalse(k.step(in), "the plan ends once the hit was due");
        assertFalse(k.active());
    }

    @Test
    void plainHitNeedsNoReset() {
        ComboKeeper k = keeper(FLAT);
        TradeInput in = sprintingIn(2.8);
        in.sSprinting = false;
        in.svz = 0.1;
        in.oReadyIn = 20; // its attack is far from charged: nothing to wait out
        assertTrue(k.begin(in, false, true));
        System.out.println("plain: " + describe(k));
        assertEquals(ComboKeeper.RESET_NONE, k.resetKind());
        Run run = execute(k, in, false, FLAT, -1, 0);
        assertTrue(run.inReach());
        assertTrue(run.hitTick() >= 12);
    }

    @Test
    void airborneOpponentStaysUpLongerAndIsHitLater() {
        // hopping off a one-block step, one tick into the jump. Vanilla knockback adds no lift in the air, so the arc
        // is longer only because the hop started higher (a hop from level ground lands about when a grounded hit does)
        ComboKeeper grounded = keeper(FLAT);
        assertTrue(grounded.begin(sprintingIn(2.8), true, true));

        ComboKeeper hop = keeper(FLAT);
        TradeInput in = sprintingIn(2.8);
        in.oy = FLOOR_Y + 1.42;
        in.ovy = 0.3332; // rising in a hop
        in.oOnGround = false;
        assertTrue(hop.begin(in, true, true));
        System.out.println("grounded: " + describe(grounded));
        System.out.println("hopping:  " + describe(hop));
        assertTrue(hop.airTicks() > grounded.airTicks(),
                "hop air " + hop.airTicks() + " vs grounded " + grounded.airTicks());
        assertTrue(hop.hitInTicks() >= grounded.hitInTicks(),
                "hop hit " + hop.hitInTicks() + " vs grounded " + grounded.hitInTicks());
        Run run = execute(hop, in, true, FLAT, -1, 0);
        assertTrue(run.inReach(), "hopping follow-up still lands");
    }

    @Test
    void neverBackTapsWhenNotAllowed() {
        double[] dists = {1.2, 1.8, 2.4, 2.8, 3.2};
        int[] oReady = {0, 3, 8, 20};
        int backTaps = 0;
        for (double d : dists) {
            for (int r : oReady) {
                for (MovementPhysics.Voxels v : new MovementPhysics.Voxels[]{FLAT, WALL}) {
                    TradeInput in = sprintingIn(d);
                    in.oReadyIn = r;
                    ComboKeeper k = keeper(v);
                    if (k.begin(in, true, false)) {
                        assertNotEquals(ComboKeeper.RESET_S_TAP, k.resetKind(), "d=" + d + " r=" + r);
                        for (int t = 0; t < ComboKeeper.MAX_PLAN_TICKS && k.active(); t++) {
                            assertTrue(k.forward() >= 0, "no back key with back taps disallowed");
                            k.step(in);
                        }
                    }
                    TradeInput hb = sprintingIn(d);
                    hb.oReadyIn = r;
                    hb.hazardBack = true;
                    ComboKeeper k2 = keeper(v);
                    if (k2.begin(hb, true, true)) {
                        assertNotEquals(ComboKeeper.RESET_S_TAP, k2.resetKind(), "hazard behind");
                    }
                    ComboKeeper k3 = keeper(v);
                    TradeInput free = sprintingIn(d);
                    free.oReadyIn = r;
                    if (k3.begin(free, true, true) && k3.resetKind() == ComboKeeper.RESET_S_TAP) {
                        backTaps++;
                    }
                }
            }
        }
        System.out.println("S-tap chosen in " + backTaps + " of 40 free scenarios");
    }

    @Test
    void wallBehindOpponentShortensTheArcAndTheReset() {
        // its attack is not charged, so the reset is about reach and sprint only (a charged opponent pinned at a
        // wall makes the keeper hold back longer instead, see the notes)
        TradeInput open = sprintingIn(2.8);
        open.oReadyIn = 20;
        ComboKeeper free = keeper(FLAT);
        assertTrue(free.begin(open, true, true));

        TradeInput walled = sprintingIn(2.8);
        walled.oReadyIn = 20;
        ComboKeeper wall = keeper(WALL);
        assertTrue(wall.begin(walled, true, true));
        System.out.println("open: " + describe(free));
        System.out.println("wall: " + describe(wall));
        assertTrue(wall.landZ() < free.landZ() - 0.5, "the wall stops the arc short");
        assertTrue(wall.landZ() <= 5.0 - 0.3 + 1e-6, "the opponent stays in front of the wall");
        assertTrue(wall.resetTicks() <= free.resetTicks(),
                "wall reset " + wall.resetTicks() + " vs open " + free.resetTicks());
        assertTrue(wall.hitInTicks() <= free.hitInTicks());
        Run run = execute(wall, walled, true, WALL, -1, 0);
        assertTrue(run.inReach());
    }

    @Test
    void opponentLeavingTheArcEndsThePlan() {
        ComboKeeper k = keeper(FLAT);
        TradeInput in = sprintingIn(2.8);
        assertTrue(k.begin(in, true, true));
        Run run = execute(k, in, true, FLAT, 4, 1.0);
        assertTrue(run.endedEarly(), "a one-block shove must end the plan");
        assertFalse(k.active());
        assertEquals(0, k.forward());
        assertFalse(k.sprint());

        ComboKeeper small = keeper(FLAT);
        TradeInput in2 = sprintingIn(2.8);
        assertTrue(small.begin(in2, true, true));
        Run nudged = execute(small, in2, true, FLAT, 4, 0.2);
        assertFalse(nudged.endedEarly(), "a small nudge stays inside the tolerance");
    }

    @Test
    void noPlanWhenTheOpponentIsKnockedOutOfReach() {
        // already flying away from another hit and backing off once down: the second hit adds to the speed
        ComboKeeper k = keeper(FLAT);
        TradeInput in = sprintingIn(2.8);
        in.oy = FLOOR_Y + 0.5;
        in.ovy = 0.2;
        in.ovz = 0.6;
        in.oOnGround = false;
        in.oForward = -1;
        in.oSprinting = false;
        boolean planned = k.begin(in, true, true);
        System.out.println("away: " + describe(k));
        assertFalse(planned, "knocked out of reach for the whole plan: nothing to follow up");
        assertFalse(k.active());
        assertEquals(0, k.forward());
    }

    @Test
    void neverFollowsIntoAPit() {
        // the opponent at the lip of a 12-deep pit can still be hit from the edge as it drops; any plan must keep
        // us on the floor
        ComboKeeper k = keeper(PIT);
        TradeInput in = sprintingIn(1.9);
        if (k.begin(in, true, true)) {
            Run run = execute(k, in, true, PIT, -1, 0);
            assertTrue(in.sy >= FLOOR_Y - 1e-6, "stayed on the floor, y=" + in.sy);
            assertTrue(run.hitTick() < 0 || run.inReach());
        }
    }

    @Test
    void hazardInFrontMeansNoPlan() {
        ComboKeeper k = keeper(FLAT);
        TradeInput in = sprintingIn(2.8);
        in.hazardFront = true;
        assertFalse(k.begin(in, true, true));
    }

    @Test
    void stepWithoutPlanIsInert() {
        ComboKeeper k = keeper(FLAT);
        assertFalse(k.step(sprintingIn(2.8)));
        assertEquals(0, k.hitInTicks());
        assertEquals(ComboKeeper.RESET_NONE, k.resetKind());
    }
}
