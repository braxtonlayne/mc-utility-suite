package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engine-free shield duel rules: blocked half-space, raise delay, open side, block-and-answer timeline. */
class ShieldDuelTest {

    /** Attacker position {@code r} blocks from a holder at the origin, {@code deg} degrees off the holder's view. */
    static double[] at(float holderYaw, double deg, double r) {
        double yr = Math.toRadians(holderYaw + deg);
        return new double[]{-Math.sin(yr) * r, Math.cos(yr) * r};
    }

    @Test
    void blockedHalfSpaceFollowsTheViewVector() {
        for (float yaw : new float[]{0f, 90f, -135f, 200f}) {
            for (double sign : new double[]{1, -1}) {
                double[] p0 = at(yaw, 0 * sign, 2.5);
                double[] p60 = at(yaw, 60 * sign, 2.5);
                double[] p89 = at(yaw, 89 * sign, 2.5);
                double[] p91 = at(yaw, 91 * sign, 2.5);
                double[] p180 = at(yaw, 180 * sign, 2.5);
                assertTrue(ShieldDuel.frontal(yaw, 0, 0, p0[0], p0[1]), "straight ahead is blocked");
                assertTrue(ShieldDuel.frontal(yaw, 0, 0, p60[0], p60[1]), "60 deg is blocked");
                assertTrue(ShieldDuel.frontal(yaw, 0, 0, p89[0], p89[1]), "89 deg is blocked");
                assertFalse(ShieldDuel.frontal(yaw, 0, 0, p91[0], p91[1]), "91 deg is open");
                assertFalse(ShieldDuel.frontal(yaw, 0, 0, p180[0], p180[1]), "behind is open");
                assertEquals(60.0, ShieldDuel.offAxisDeg(yaw, 0, 0, p60[0], p60[1]), 1e-6);
            }
        }
        double[] p90 = at(0f, 90, 2.0);
        assertFalse(ShieldDuel.frontal(0f, 0, 0, p90[0], p90[1]), "exactly 90 deg: dot 0, the hit lands");
        assertFalse(ShieldDuel.frontal(0f, 1, 1, 1, 1), "same spot: vanilla normalises to zero, not blocked");
        // yaw 0 looks along +Z: an attacker at +Z is in front
        assertTrue(ShieldDuel.frontal(0f, 0, 0, 0, 2));
        assertFalse(ShieldDuel.frontal(0f, 0, 0, 0, -2));
    }

    @Test
    void raiseDelayWindowLetsEarlyHitsLand() {
        assertFalse(ShieldDuel.blocksHit(0, ShieldDuel.HIT_LEAD), "not raised");
        for (int use = 1; use <= 3; use++) {
            assertFalse(ShieldDuel.blocksHit(use, ShieldDuel.HIT_LEAD), "a hit inside the raise delay lands: " + use);
        }
        assertTrue(ShieldDuel.blocksHit(4, ShieldDuel.HIT_LEAD), "blocks by the time our tap arrives");
        assertTrue(ShieldDuel.blocksHit(5, 0));
        assertFalse(ShieldDuel.blocksHit(4, 0));
        assertTrue(ShieldDuel.blocksHit(40, ShieldDuel.HIT_LEAD));
    }

    @Test
    void openSideIsTheShorterWayRound() {
        // the side key must grow our angle off the holder's view (left key = (fz, -fx) for our facing f)
        for (float yaw : new float[]{0f, 45f, 170f, -100f}) {
            for (double deg = -170; deg <= 170; deg += 10) {
                if (Math.abs(deg) < 1e-9) {
                    continue;
                }
                double[] p = at(yaw, deg, 2.8);
                int side = ShieldDuel.openSide(yaw, 0, 0, p[0], p[1], false, false, ShieldDuel.LEFT);
                double fx = -p[0];
                double fz = -p[1];
                double len = Math.hypot(fx, fz);
                fx /= len;
                fz /= len;
                double lx = fz;
                double lz = -fx;
                double step = side == ShieldDuel.LEFT ? 0.1 : -0.1; // right key is minus the left vector
                double before = ShieldDuel.offAxisDeg(yaw, 0, 0, p[0], p[1]);
                double after = ShieldDuel.offAxisDeg(yaw, 0, 0, p[0] + lx * step, p[1] + lz * step);
                assertTrue(after > before, "yaw " + yaw + " at " + deg + " deg: side " + side + " grows the angle");
            }
        }
    }

    @Test
    void openSideRespectsHazards() {
        double[] p = at(0f, 30, 2.8);
        int free = ShieldDuel.openSide(0f, 0, 0, p[0], p[1], false, false, ShieldDuel.LEFT);
        int other = -free;
        boolean hzLeftOnFree = free == ShieldDuel.LEFT;
        assertEquals(other, ShieldDuel.openSide(0f, 0, 0, p[0], p[1], hzLeftOnFree, !hzLeftOnFree,
                ShieldDuel.LEFT), "the short side is a hazard: go the long way");
        assertEquals(ShieldDuel.NONE, ShieldDuel.openSide(0f, 0, 0, p[0], p[1], true, true, ShieldDuel.LEFT),
                "hazards both sides: no circling");
        double[] ahead = at(0f, 0, 2.8);
        assertEquals(ShieldDuel.RIGHT, ShieldDuel.openSide(0f, 0, 0, ahead[0], ahead[1], false, false,
                ShieldDuel.RIGHT), "dead ahead keeps the preferred side");
        assertEquals(ShieldDuel.RIGHT, ShieldDuel.openSide(0f, 0, 0, ahead[0], ahead[1], true, false,
                ShieldDuel.LEFT), "dead ahead, left blocked");
    }

    @Test
    void sideConstantsMatchTheStrafePlanner() {
        assertEquals(com.utilitysuite.combat.assistant.StrafePlanner.LEFT, ShieldDuel.LEFT);
        assertEquals(com.utilitysuite.combat.assistant.StrafePlanner.RIGHT, ShieldDuel.RIGHT);
        assertEquals(com.utilitysuite.combat.assistant.StrafePlanner.NONE, ShieldDuel.NONE);
    }

    // ---- block and answer -----------------------------------------------------------------------------------------

    /** Result of a simulated exchange. */
    record Timeline(int swings, int blocked, int answers, int raisedTicks) {
    }

    /**
     * An opponent in reach that swings the moment its attack is charged (cooldown {@code period}); we run the loop
     * and answer (one hit) whenever the swing is not vetoed and our own cooldown is charged.
     */
    static Timeline run(double period, boolean axe, int ticks) {
        ShieldDuel d = new ShieldDuel();
        d.blockEnabled = true;
        d.hasShield = true;
        d.oppInCone = true;
        d.oppInReach = true;
        d.oppDisables = axe;
        long lastSwing = -100;
        int useTicks = 0;
        double ownCharge = 0;
        int swings = 0;
        int blocked = 0;
        int answers = 0;
        int raisedTicks = 0;
        for (int t = 0; t < ticks; t++) {
            double remaining = period - (t - lastSwing);
            d.oppReadyIn = remaining <= 1e-9 ? 0 : (int) Math.ceil(remaining - 1e-9);
            boolean up = d.step();
            useTicks = up ? useTicks + 1 : 0;
            if (up) {
                raisedTicks++;
            }
            // our answer: not vetoed and our attack charged (12.5-tick sword)
            ownCharge++;
            if (!d.vetoSwing() && ownCharge >= 12.5) {
                answers++;
                ownCharge = 0;
            }
            // the opponent swings when charged (after our decision this tick)
            if (d.oppReadyIn == 0) {
                swings++;
                // useTicks counts this tick: raised at tick r -> blocks from r + 5 (isBlocking)
                if (useTicks - 1 >= ShieldDuel.RAISE_DELAY) {
                    blocked++;
                }
                lastSwing = t;
            }
        }
        return new Timeline(swings, blocked, answers, raisedTicks);
    }

    @Test
    void blockAndAnswerAgainstASword() {
        Timeline tl = run(12.5, false, 400);
        assertTrue(tl.swings() >= 25, "they keep swinging: " + tl);
        // the first swing comes before any raise could be effective (an unread opponent counts as ready)
        assertTrue(tl.blocked() >= tl.swings() - 1, "every later charged hit lands on the shield: " + tl);
        assertTrue(tl.answers() >= tl.swings() - 2, "we answer inside every cooldown: " + tl);
    }

    @Test
    void blockAndAnswerAgainstASlowWeapon() {
        Timeline tl = run(20.0, false, 400);
        assertTrue(tl.swings() >= 18, tl.toString());
        assertTrue(tl.blocked() >= tl.swings() - 1, tl.toString());
        assertTrue(tl.answers() >= tl.swings() - 2, tl.toString());
    }

    @Test
    void neverRaisesIntoAnAxe() {
        Timeline tl = run(20.0, true, 400);
        assertEquals(0, tl.raisedTicks(), "an axe hit would disable the shield: never up against it");
        assertEquals(0, tl.blocked());
        assertTrue(tl.answers() > 0, "the long cooldown window is still used to hit");
    }

    @Test
    void timelineIsExactAgainstASword() {
        ShieldDuel d = new ShieldDuel();
        d.blockEnabled = true;
        d.hasShield = true;
        d.oppInCone = true;
        d.oppInReach = true;
        // they swung at t0 (readyIn 13): down, swing vetoed on the lowering tick and LOWER_GAP after
        d.oppReadyIn = 5;
        assertTrue(d.step(), "raised 5 + 2 ticks before their charge");
        d.oppReadyIn = 13;
        assertFalse(d.step());
        assertTrue(d.lowered());
        assertTrue(d.vetoSwing(), "lowering tick: the attack click would be eaten");
        d.oppReadyIn = 12;
        d.step();
        assertTrue(d.vetoSwing(), "one more tick for the release to reach the game");
        d.oppReadyIn = 11;
        d.step();
        assertFalse(d.vetoSwing(), "answer window open");
        d.oppReadyIn = 8;
        assertFalse(d.step());
        d.oppReadyIn = ShieldDuel.RAISE_DELAY + ShieldDuel.REACTION_MARGIN;
        assertTrue(d.step(), "up again 7 ticks before their next charged hit");
    }

    @Test
    void noRaiseWhileEatingOutOfConeOutOfReachOrAgainstARaisedShield() {
        ShieldDuel d = new ShieldDuel();
        d.blockEnabled = true;
        d.hasShield = true;
        d.oppInCone = true;
        d.oppInReach = true;
        d.oppReadyIn = 0;
        d.eating = true;
        assertFalse(d.step(), "eating owns USE");
        d.eating = false;
        d.oppInCone = false;
        assertFalse(d.step(), "legit: only with the opponent in the front cone");
        d.oppInCone = true;
        d.oppInReach = false;
        assertFalse(d.step(), "out of reach: no threat");
        d.oppInReach = true;
        d.oppShieldUp = true;
        assertFalse(d.step(), "their shield is up: they cannot swing");
        d.oppShieldUp = false;
        d.hasShield = false;
        assertFalse(d.step(), "no shield (or on cooldown)");
        d.hasShield = true;
        d.blockEnabled = false;
        assertFalse(d.step(), "setting off");
        d.blockEnabled = true;
        assertTrue(d.step());
    }

    @Test
    void aRaiseThatNeverMeetsAHitRestsAfterMaxHold() {
        ShieldDuel d = new ShieldDuel();
        d.blockEnabled = true;
        d.hasShield = true;
        d.oppInCone = true;
        d.oppInReach = true;
        d.oppReadyIn = 0; // charged forever, never swings
        int up = 0;
        for (int t = 0; t < ShieldDuel.MAX_HOLD; t++) {
            if (d.step()) {
                up++;
            }
        }
        assertEquals(ShieldDuel.MAX_HOLD, up);
        assertFalse(d.step(), "the cap drops it");
        for (int t = 0; t < ShieldDuel.REST_TICKS - 2; t++) {
            assertFalse(d.step(), "resting");
        }
        boolean back = false;
        for (int t = 0; t < 4; t++) {
            back |= d.step();
        }
        assertTrue(back, "up again after the rest");
        d.reset();
        assertFalse(d.raised());
        assertFalse(d.vetoSwing());
    }

    @Test
    void frontConeUsesOurYaw() {
        // we at origin facing +X (yaw -90), target at +X
        assertTrue(ShieldDuel.inFrontCone(-90f, 0, 0, 3, 0, ShieldDuel.FRONT_CONE_DEG));
        assertFalse(ShieldDuel.inFrontCone(90f, 0, 0, 3, 0, ShieldDuel.FRONT_CONE_DEG));
        assertFalse(ShieldDuel.inFrontCone(0f, 0, 0, 3, 0, ShieldDuel.FRONT_CONE_DEG), "90 deg to the side");
    }
}
