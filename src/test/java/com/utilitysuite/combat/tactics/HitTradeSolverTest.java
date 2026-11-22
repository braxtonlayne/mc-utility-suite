package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.api.IBodySimProvider;
import com.utilitysuite.travel.movement.MovementPhysics;
import com.utilitysuite.travel.movement.PhysicsBodySim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitTradeSolverTest {

    static final int FLOOR_Y = 64;
    static final MovementPhysics.Voxels FLAT = (x, y, z) -> y < FLOOR_Y;

    static IBodySimProvider provider(MovementPhysics.Voxels voxels) {
        return () -> new PhysicsBodySim(voxels);
    }

    /** We stand at (0.5, 64, 0.5); the opponent {@code dist} blocks toward +Z, facing us. */
    static TradeInput duel(double dist) {
        TradeInput in = new TradeInput();
        in.sx = 0.5;
        in.sy = FLOOR_Y;
        in.sz = 0.5;
        in.sOnGround = true;
        in.sYaw = 0f;
        in.ox = 0.5;
        in.oy = FLOOR_Y;
        in.oz = 0.5 + dist;
        in.oOnGround = true;
        return in;
    }

    static String dump(HitTradeSolver s) {
        StringBuilder b = new StringBuilder("answer f=").append(s.forward()).append(" s=").append(s.strafe())
                .append(" j=").append(s.jump()).append(" score=").append(String.format("%.3f", s.score()))
                .append(" |");
        for (int f = 1; f >= -1; f--) {
            for (int st = 1; st >= -1; st--) {
                b.append(String.format(" (%d,%d)=%.3f", f, st, s.scoreOf(HitTradeSolver.combo(f, st))));
            }
        }
        return b.toString();
    }

    /** Opponent charged and sprinting in, our attack far from ready. */
    static TradeInput chargingOpponent() {
        TradeInput in = duel(4.2);
        in.sReadyIn = 10;
        in.oReadyIn = 0;
        in.oForward = 1;
        in.oSprinting = true;
        in.ovz = -0.28;
        return in;
    }

    @Test
    void comboIndexLayout() {
        assertEquals(0, HitTradeSolver.combo(-1, -1));
        assertEquals(4, HitTradeSolver.combo(0, 0));
        assertEquals(8, HitTradeSolver.combo(1, 1));
        assertEquals(9, HitTradeSolver.COMBOS);
    }

    @Test
    void reachMatchesEyeToBox() {
        // level ground: eye 1.62 is inside the 1.8 box height, so reach is horizontal gap to the box face
        assertEquals(3.0 * 3.0, HitTradeSolver.reachSq(0.5, 64, 0.5, 0.5, 64, 3.8), 1e-9);
        assertEquals(0.0, HitTradeSolver.reachSq(0.5, 64, 0.5, 0.6, 64, 0.6), 1e-12);
        // victim two blocks lower: eye 1.62 above our feet, victim top at -0.2 -> 1.82 vertical
        double sq = HitTradeSolver.reachSq(0.5, 64, 0.5, 0.5, 62, 0.5);
        assertEquals(1.82 * 1.82, sq, 1e-9);
    }

    @Test
    void yawConvention() {
        assertEquals(0f, HitTradeSolver.yawToward(0, 1), 1e-4);
        assertEquals(90f, HitTradeSolver.yawToward(-1, 0), 1e-4);
        assertEquals(-90f, HitTradeSolver.yawToward(1, 0), 1e-4);
    }

    @Test
    void backsOffWhileOpponentChargedAndWeAreNot() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        s.solve(chargingOpponent());
        assertTrue(s.valid());
        assertTrue(s.forward() <= 0, dump(s));
        assertTrue(s.scoreOf(HitTradeSolver.combo(-1, 0)) > s.scoreOf(HitTradeSolver.combo(1, 0)), dump(s));
    }

    @Test
    void stepsInRightAfterOpponentSwung() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        TradeInput in = duel(3.6); // eye to box 3.3: just outside reach
        in.sReadyIn = 0;
        in.oReadyIn = 11;
        in.oForward = 0;
        in.oSprinting = false;
        s.solve(in);
        assertTrue(s.valid());
        assertEquals(1, s.forward(), dump(s));
        assertTrue(s.sprint());
        assertTrue(s.score() > 0.5, dump(s));
    }

    @Test
    void bothReadyOpponentClosingLandsFirstHit() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        TradeInput in = duel(3.5);
        in.sReadyIn = 0;
        in.oReadyIn = 0;
        in.oForward = 1;
        in.ovz = -0.2;
        s.solve(in);
        assertTrue(s.valid());
        assertTrue(s.score() > 0.0, dump(s));
    }

    @Test
    void hazardBehindNeverBacksOff() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        TradeInput in = chargingOpponent();
        in.hazardBack = true;
        s.solve(in);
        assertTrue(s.valid());
        assertNotEquals(-1, s.forward(), dump(s));
        for (int st = -1; st <= 1; st++) {
            assertEquals(HitTradeSolver.REJECTED, s.scoreOf(HitTradeSolver.combo(-1, st)));
        }
    }

    @Test
    void wallBehindPrefersStrafe() {
        // a wall of blocks at z <= -1: our box (z 0.2..0.8) has 0.2 blocks of room behind it
        MovementPhysics.Voxels walled = (x, y, z) -> y < FLOOR_Y || z <= -1;
        HitTradeSolver s = new HitTradeSolver(provider(walled));
        TradeInput in = chargingOpponent();
        in.oSprinting = false; // walking in: on open ground backing off would keep the distance
        in.ovz = -0.2;
        s.solve(in);
        assertTrue(s.valid());
        assertNotEquals(-1, s.forward(), dump(s));
        assertTrue(s.scoreOf(HitTradeSolver.combo(0, 1)) > s.scoreOf(HitTradeSolver.combo(-1, 0)), dump(s));
        assertTrue(s.scoreOf(HitTradeSolver.combo(0, -1)) > s.scoreOf(HitTradeSolver.combo(-1, 0)), dump(s));

        // the same approach on open ground: backing off is the answer there
        HitTradeSolver open = new HitTradeSolver(provider(FLAT));
        open.solve(in);
        assertTrue(open.scoreOf(HitTradeSolver.combo(-1, 0)) > open.scoreOf(HitTradeSolver.combo(0, 1)), dump(open));
    }

    @Test
    void allRejectedIsInvalid() {
        MovementPhysics.Voxels voidWorld = (x, y, z) -> false;
        HitTradeSolver s = new HitTradeSolver(provider(voidWorld));
        TradeInput in = duel(3.0);
        in.sy = 80;
        in.sOnGround = false;
        in.svy = -1.0; // already falling into a void
        in.oy = 80;
        in.hazardFront = in.hazardBack = in.hazardLeft = in.hazardRight = true;
        s.solve(in);
        assertFalse(s.valid());
        for (int c = 0; c < HitTradeSolver.COMBOS; c++) {
            assertEquals(HitTradeSolver.REJECTED, s.scoreOf(c));
        }
    }

    @Test
    void hazardFlagsRejectOnlyMatchingDirections() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        TradeInput in = chargingOpponent();
        in.hazardLeft = true;
        s.solve(in);
        for (int f = -1; f <= 1; f++) {
            assertEquals(HitTradeSolver.REJECTED, s.scoreOf(HitTradeSolver.combo(f, 1)));
            assertTrue(s.scoreOf(HitTradeSolver.combo(f, 0)) > HitTradeSolver.REJECTED);
            assertTrue(s.scoreOf(HitTradeSolver.combo(f, -1)) > HitTradeSolver.REJECTED);
        }
        assertNotEquals(1, s.strafe());
    }

    @Test
    void mirroredStrafeGivesMirroredAnswer() {
        TradeInput a = chargingOpponent();
        a.ox = 0.5 + 0.8;
        a.oStrafe = 1;
        a.sReadyIn = 3;
        a.hazardLeft = false;
        TradeInput b = chargingOpponent();
        b.ox = 0.5 - 0.8;
        b.oStrafe = -1;
        b.sReadyIn = 3;
        HitTradeSolver sa = new HitTradeSolver(provider(FLAT));
        HitTradeSolver sb = new HitTradeSolver(provider(FLAT));
        sa.solve(a);
        sb.solve(b);
        for (int f = -1; f <= 1; f++) {
            for (int st = -1; st <= 1; st++) {
                assertEquals(sa.scoreOf(HitTradeSolver.combo(f, st)), sb.scoreOf(HitTradeSolver.combo(f, -st)),
                        1e-6, "combo " + f + "," + st + "\n" + dump(sa) + "\n" + dump(sb));
            }
        }
        assertEquals(sa.forward(), sb.forward());
        int left = HitTradeSolver.combo(sa.forward(), 1);
        int right = HitTradeSolver.combo(sa.forward(), -1);
        if (Math.abs(sa.scoreOf(left) - sa.scoreOf(right)) > 1e-6) {
            assertEquals(sa.strafe(), -sb.strafe(), dump(sa) + "\n" + dump(sb));
        }
    }

    @Test
    void hysteresisHoldsUnderInputNoise() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        TradeInput base = chargingOpponent();
        s.solve(base);
        int f0 = s.forward();
        int s0 = s.strafe();
        Random rnd = new Random(7);
        int changes = 0;
        for (int i = 0; i < 100; i++) {
            TradeInput in = chargingOpponent();
            in.sx += (rnd.nextDouble() - 0.5) * 0.02;
            in.sz += (rnd.nextDouble() - 0.5) * 0.02;
            in.ox += (rnd.nextDouble() - 0.5) * 0.02;
            in.oz += (rnd.nextDouble() - 0.5) * 0.02;
            in.ovz += (rnd.nextDouble() - 0.5) * 0.01;
            s.solve(in);
            if (s.forward() != f0 || s.strafe() != s0) {
                changes++;
            }
        }
        assertEquals(0, changes);
    }

    @Test
    void resetDropsHysteresis() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        s.solve(chargingOpponent());
        s.reset();
        TradeInput in = duel(3.6);
        in.oReadyIn = 11;
        in.oForward = 0;
        s.solve(in);
        assertEquals(1, s.forward(), dump(s));
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 7, 8})
    void horizonsWork(int horizon) {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        TradeInput in = duel(3.6);
        in.oReadyIn = 11;
        in.oForward = 0;
        in.oSprinting = false;
        in.horizon = horizon;
        s.solve(in);
        assertTrue(s.valid());
        assertEquals(1, s.forward(), dump(s));

        HitTradeSolver back = new HitTradeSolver(provider(FLAT));
        TradeInput c = chargingOpponent();
        c.horizon = horizon;
        back.solve(c);
        assertTrue(back.forward() <= 0, dump(back));
    }

    @Test
    void mobWithoutCooldownStillPunishesStandingInReach() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        TradeInput in = duel(2.5);
        in.oUsesCooldown = false;
        in.oReadyIn = 0;
        in.oForward = 1;
        in.oSprinting = false;
        in.sReadyIn = 8;
        s.solve(in);
        assertTrue(s.valid());
        assertTrue(s.scoreOf(HitTradeSolver.combo(0, 0)) < 0.0, dump(s));
    }

    @Test
    void scoresReportedForEveryCombo() {
        HitTradeSolver s = new HitTradeSolver(provider(FLAT));
        s.solve(duel(5.0));
        double best = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < HitTradeSolver.COMBOS; c++) {
            best = Math.max(best, s.scoreOf(c));
        }
        assertEquals(best, s.score(), 1e-12);
    }
}
