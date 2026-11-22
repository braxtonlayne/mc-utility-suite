package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.HitTradeSolver;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.TradeInput;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Input mapping and dwell of {@link TradeDriver}, with a scripted solve step. */
public class TradeDriverTest {

    /** Scripted solver answer: set the fields, then call {@code decide}. */
    public static final class ScriptedStep implements TradeDriver.Step {
        public boolean valid = true;
        public int forward;
        public int strafe;
        public boolean jump;
        public boolean sprint;
        public double score;
        public final double[] scores = new double[HitTradeSolver.COMBOS];
        public int solves;

        /** Answer (forward, strafe) with the given best score; every other combination scores {@code others}. */
        public void answer(int f, int s, double best, double others) {
            valid = true;
            forward = f;
            strafe = s;
            score = best;
            java.util.Arrays.fill(scores, others);
            scores[HitTradeSolver.combo(f, s)] = best;
        }

        @Override
        public void solve(TradeInput in) {
            solves++;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public int forward() {
            return forward;
        }

        @Override
        public int strafe() {
            return strafe;
        }

        @Override
        public boolean jump() {
            return jump;
        }

        @Override
        public boolean sprint() {
            return sprint;
        }

        @Override
        public double score() {
            return score;
        }

        @Override
        public double scoreOf(int combo) {
            return scores[combo];
        }
    }

    static final int ID = 10;

    ScriptedStep step;
    TradeDriver driver;
    OpponentModel opponents;
    CombatSnapshot s;

    @BeforeEach
    void setUp() {
        step = new ScriptedStep();
        driver = new TradeDriver(step);
        opponents = new OpponentModel();
        s = new CombatSnapshot();
        s.px = 0;
        s.py = 64;
        s.pz = 0;
        s.tx = 3;
        s.ty = 64;
        s.tz = 0;
        s.onGround = true;
        s.attackStrength = 1f;
    }

    void fill(boolean player, boolean readCooldown) {
        driver.fill(s, opponents, ID, 101, player, readCooldown, 7, 1.2, true);
    }

    @Test
    void opponentKeysAreInferredFromItsVelocityRelativeToUs() {
        s.tvx = -0.2; // toward us (we are at -x from it)
        fill(true, true);
        assertEquals(1, driver.input().oForward, "closing in");
        assertEquals(0, driver.input().oStrafe);

        s.tvx = 0.2;
        fill(true, true);
        assertEquals(-1, driver.input().oForward, "backing off");

        s.tvx = 0.01; // inside the dead zone
        s.tvz = 0.1; // facing -x, its left is +z
        fill(true, true);
        assertEquals(0, driver.input().oForward);
        assertEquals(1, driver.input().oStrafe, "strafing to its own left");

        s.tvz = -0.1;
        fill(true, true);
        assertEquals(-1, driver.input().oStrafe, "strafing to its right");

        s.tvz = 0.02;
        fill(true, true);
        assertEquals(0, driver.input().oStrafe, "dead zone");
    }

    @Test
    void positionsVelocitiesHazardsAndTuningAreCopied() {
        s.pvx = 0.1;
        s.pvz = -0.05;
        s.sprinting = true;
        s.yaw = 90f;
        s.reach = 3.0;
        s.targetOnGround = false;
        s.tvy = -0.3;
        s.hazardFront = true;
        s.hazardRight = true;
        driver.fill(s, opponents, ID, 101, false, true, 12, 1.8, false);
        TradeInput in = driver.input();
        assertEquals(0.1, in.svx);
        assertEquals(-0.05, in.svz);
        assertTrue(in.sSprinting);
        assertEquals(90f, in.sYaw);
        assertEquals(3.0, in.ox);
        assertFalse(in.oOnGround);
        assertEquals(-0.3, in.ovy);
        assertTrue(in.hazardFront);
        assertFalse(in.hazardBack);
        assertFalse(in.hazardLeft);
        assertTrue(in.hazardRight);
        assertEquals(8, in.horizon, "horizon clamped to 6..8");
        assertEquals(1.8, in.takenWeight);
        assertFalse(in.allowJump);
        assertFalse(in.oUsesCooldown, "a mob does not swing on a cooldown");
        fill(true, true);
        assertTrue(driver.input().oUsesCooldown);
    }

    @Test
    void ownReadyInComesFromTheAttackStrengthAndTheLearnedPeriod() {
        s.attackStrength = 0.5f;
        fill(true, true);
        assertEquals(7, driver.input().sReadyIn, "half charged with the 12.5-tick default");
        assertEquals(TradeDriver.DEFAULT_COOLDOWN, driver.input().sCooldownTicks);
        // an axe-like charge: +0.1 per tick -> 10-tick period
        for (int i = 0; i < 40; i++) {
            s.attackStrength = 0.05f + (i % 9) * 0.1f;
            fill(true, true);
        }
        assertEquals(10.0, driver.ownCooldown(), 0.3);
        s.attackStrength = 1f;
        fill(true, true);
        assertEquals(0, driver.input().sReadyIn, "full charge: ready now");
    }

    @Test
    void cooldownReadOffTreatsTheOpponentAsAlwaysReady() {
        opponents.noteSwing(ID, 100, 1.6); // a sword swing one tick ago
        fill(true, true);
        assertEquals(Math.max(0, opponents.attackReadyIn(ID, 101)), driver.input().oReadyIn,
                "read from the opponent model");
        assertEquals(opponents.cooldownTicks(ID), driver.input().oCooldownTicks);
        fill(true, false);
        assertEquals(0, driver.input().oReadyIn, "setting off: always ready");
    }

    @Test
    void invalidAnswerFallsBack() {
        step.valid = false;
        assertFalse(driver.decide(s, opponents, ID, 1, true, true, 7, 1.2, true));
        assertEquals(1, step.solves);
    }

    @Test
    void anAnswerIsHeldForTheDwellUnlessANewOneIsClearlyBetter() {
        step.answer(1, 0, 1.0, 0.0);
        assertTrue(decide());
        assertEquals(1, driver.forward());
        // a marginally better answer on the next tick: keep the held keys
        step.answer(-1, 1, 1.1, 0.0);
        step.scores[HitTradeSolver.combo(1, 0)] = 1.0;
        assertTrue(decide());
        assertEquals(1, driver.forward(), "held");
        assertEquals(0, driver.strafe());
        assertTrue(decide());
        assertEquals(1, driver.forward(), "still held");
        // dwell over: the better answer is taken
        assertTrue(decide());
        assertEquals(-1, driver.forward());
        assertEquals(1, driver.strafe());
    }

    @Test
    void aClearlyBetterAnswerOrARejectedHeldOneSwitchesAtOnce() {
        step.answer(1, 0, 1.0, 0.0);
        decide();
        step.answer(0, -1, 2.0, 0.0);
        step.scores[HitTradeSolver.combo(1, 0)] = 1.0;
        decide();
        assertEquals(0, driver.forward());
        assertEquals(-1, driver.strafe(), "a clear lead switches before the dwell");

        step.answer(1, 1, 0.1, 0.0);
        step.scores[HitTradeSolver.combo(0, -1)] = -1.0e9; // the held keys walk into a hazard now
        decide();
        assertEquals(1, driver.forward());
        assertEquals(1, driver.strafe(), "a rejected held answer is dropped at once");
    }

    @Test
    void sprintOnlyWithForwardAndJumpOnlyWithTheTakenAnswer() {
        step.answer(-1, 0, 1.0, 0.0);
        step.sprint = true;
        step.jump = true;
        decide();
        assertFalse(driver.sprint(), "no sprint while backing off");
        assertTrue(driver.jump());
        step.answer(1, 0, 1.05, 0.0);
        step.scores[HitTradeSolver.combo(-1, 0)] = 1.0;
        step.jump = true;
        decide();
        assertEquals(-1, driver.forward(), "held");
        assertFalse(driver.jump(), "the jump of an answer not taken is dropped");
    }

    @Test
    void decideAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        step.answer(1, 0, 1.0, 0.0);
        long bytes = AllocationProbe.measure(100_000, () -> {
            s.tvx = -s.tvx + 0.01;
            driver.decide(s, opponents, ID, 5, true, true, 7, 1.2, true);
        });
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes");
    }

    private boolean decide() {
        return driver.decide(s, opponents, ID, 1, true, true, 7, 1.2, true);
    }

    // ---- shield state ---------------------------------------------------------------------------------------------

    @Test
    void shieldFeedReachesTheSolverInputOnlyOnItsTick() {
        TradeDriver.ShieldFeed f = TradeDriver.shieldFeed();
        try {
            f.clear();
            fill(true, true);
            assertFalse(driver.input().sBlocking);
            assertFalse(driver.input().oShieldUp);

            f.set(101, true, true); // fill() runs at fight tick 101
            fill(true, true);
            assertTrue(driver.input().sBlocking);
            assertTrue(driver.input().oShieldUp);

            f.set(100, true, true); // written on an earlier tick: stale
            fill(true, true);
            assertFalse(driver.input().sBlocking, "a stage that stopped writing leaves nothing behind");
            assertFalse(driver.input().oShieldUp);
        } finally {
            f.clear();
        }
    }

    @Test
    void instanceSettersOverrideTheFeed() {
        TradeDriver.shieldFeed().clear();
        driver.setSelfBlocking(true);
        fill(true, true);
        assertTrue(driver.input().sBlocking);
        assertFalse(driver.input().oShieldUp);
        driver.setSelfBlocking(false);
        driver.setOppShieldUp(true);
        fill(true, true);
        assertFalse(driver.input().sBlocking);
        assertTrue(driver.input().oShieldUp);
    }
}
