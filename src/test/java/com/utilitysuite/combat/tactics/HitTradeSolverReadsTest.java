package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learned reach (hover band) and the post-hit stun in {@link HitTradeSolver} (2026-11-23). */
class HitTradeSolverReadsTest {

    static HitTradeSolver solver() {
        return new HitTradeSolver(HitTradeSolverTest.provider(HitTradeSolverTest.FLAT));
    }

    /** Opponent standing still, its hit charged, ours a few ticks away; eye-to-box gap {@code gap}. */
    static TradeInput waiting(double gap) {
        TradeInput in = HitTradeSolverTest.duel(gap + 0.3);
        in.sReadyIn = 9;
        in.oReadyIn = 0;
        in.oForward = 0;
        in.oSprinting = false;
        in.allowJump = false;
        return in;
    }

    @Test
    void learnedShortReachHoversInsideOurReach() {
        // it reaches 2.7 (eye to box), we 3.0. The fixed band (2.7 to 3.3) rates 2.9 and 3.3 the same, although only
        // 2.9 lets us hit; the learned band (2.7 to our 3.0) prefers the spot inside our reach.
        int hold = HitTradeSolver.combo(0, 0);
        double fixedIn = holdScore(2.9, false);
        double fixedOut = holdScore(3.3, false);
        double learnedIn = holdScore(2.9, true);
        double learnedOut = holdScore(3.3, true);
        assertEquals(fixedIn, fixedOut, 1e-9, "fixed band: no preference");
        assertTrue(learnedIn > learnedOut + 0.01, "learned band: inside our reach wins");
        assertEquals(fixedIn, learnedIn, 1e-9, "inside the band both agree");

        HitTradeSolver s = solver();
        TradeInput in = waiting(2.9);
        in.oReach = 2.7;
        in.hoverRelative = true;
        s.solve(in);
        assertEquals(0, s.forward(), "at 2.9 it holds there: " + HitTradeSolverTest.dump(s));
        assertTrue(s.scoreOf(hold) > s.scoreOf(HitTradeSolver.combo(-1, 0)));
    }

    static double holdScore(double gap, boolean relative) {
        HitTradeSolver s = solver();
        TradeInput in = waiting(gap);
        in.oReach = 2.7;
        in.hoverRelative = relative;
        s.solve(in);
        return s.scoreOf(HitTradeSolver.combo(0, 0));
    }

    /** Both charged, it stands inside both reaches, not sprinting. */
    static TradeInput exchange(int stun) {
        TradeInput in = HitTradeSolverTest.duel(2.6);
        in.sReadyIn = 0;
        in.oReadyIn = 0;
        in.oForward = 1;
        in.oSprinting = false;
        in.allowJump = false;
        in.oHitStun = stun;
        return in;
    }

    @Test
    void aLearnedStunLetsUsStepInAfterOurHit() {
        HitTradeSolver plain = solver();
        plain.solve(exchange(0));
        HitTradeSolver stunned = solver();
        stunned.solve(exchange(5));
        int fwd = HitTradeSolver.combo(1, 0);
        assertTrue(stunned.scoreOf(fwd) > plain.scoreOf(fwd),
                "following our hit in costs less when it cannot answer: " + HitTradeSolverTest.dump(plain) + " / "
                        + HitTradeSolverTest.dump(stunned));
        assertTrue(stunned.score() >= plain.score());
    }

    @Test
    void aSprintingOpponentGetsNoStun() {
        TradeInput a = exchange(5);
        a.oSprinting = true;
        a.ovz = -0.28;
        TradeInput b = exchange(0);
        b.oSprinting = true;
        b.ovz = -0.28;
        HitTradeSolver s1 = solver();
        s1.solve(a);
        HitTradeSolver s2 = solver();
        s2.solve(b);
        for (int c = 0; c < HitTradeSolver.COMBOS; c++) {
            assertEquals(s2.scoreOf(c), s1.scoreOf(c), 1e-9, "combo " + c);
        }
    }

    @Test
    void solveWithReadsAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HitTradeSolver s = solver();
        TradeInput in = exchange(4);
        in.hoverRelative = true;
        in.oReach = 2.8;
        long[] tick = {0};
        long bytes = AllocationProbe.measure(5_000, () -> {
            long t = tick[0]++;
            in.oz = 0.5 + 2.0 + (t % 5) * 0.4;
            in.sReadyIn = (int) (t % 9);
            s.solve(in);
        });
        assertTrue(bytes < 1_024, "HitTradeSolver with reads allocated " + bytes + " bytes");
    }
}
