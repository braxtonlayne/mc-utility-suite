package com.utilitysuite.combat.assistant;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrafeWithTest {

    @Test
    void preferenceIsFollowedWithinTheMinimumHold() {
        StrafePlanner p = new StrafePlanner();
        int first = -1;
        for (int t = 0; t < 300; t++) {
            int d = p.update(false, false, 30, 0.0, StrafePlanner.LEFT, 0.8);
            if (d == StrafePlanner.LEFT && first < 0) {
                first = t;
            }
            if (first >= 0) {
                assertEquals(StrafePlanner.LEFT, d, "stays with the opponent at tick " + t);
            }
        }
        assertTrue(first >= 0 && first <= StrafePlanner.WITH_MIN_HOLD, "switched at " + first);
    }

    @Test
    void hazardsOverrideThePreference() {
        StrafePlanner p = new StrafePlanner();
        for (int t = 0; t < 200; t++) {
            int d = p.update(true, false, 30, 0.0, StrafePlanner.LEFT, 1.0);
            assertNotEquals(StrafePlanner.LEFT, d, "tick " + t);
        }
        StrafePlanner q = new StrafePlanner();
        for (int t = 0; t < 50; t++) {
            assertEquals(StrafePlanner.NONE, q.update(true, true, 30, 0.0, StrafePlanner.RIGHT, 1.0));
        }
    }

    @Test
    void preferenceNeverFlipsFasterThanTheMinimumHold() {
        StrafePlanner p = new StrafePlanner();
        Random rnd = new Random(3);
        int last = p.direction();
        int since = 1000;
        for (int t = 0; t < 2000; t++) {
            int want = rnd.nextBoolean() ? StrafePlanner.LEFT : StrafePlanner.RIGHT;
            int d = p.update(false, false, 30, 0.0, want, 1.0);
            since++;
            if (d != last) {
                assertTrue(since >= StrafePlanner.WITH_MIN_HOLD, "changed after " + since + " ticks at " + t);
                since = 0;
                last = d;
            }
        }
    }

    @Test
    void noPreferenceReproducesTheFourArgumentPlanner() {
        Random rnd = new Random(11);
        StrafePlanner a = new StrafePlanner();
        StrafePlanner b = new StrafePlanner();
        StrafePlanner c = new StrafePlanner();
        for (int t = 0; t < 3000; t++) {
            boolean hl = rnd.nextInt(10) == 0;
            boolean hr = rnd.nextInt(10) == 0;
            int interval = 5 + rnd.nextInt(30);
            double bias = rnd.nextDouble() * 2 - 1;
            int want = rnd.nextInt(3) - 1;
            int da = a.update(hl, hr, interval, bias);
            int db = b.update(hl, hr, interval, bias, want, 0.0);
            int dc = c.update(hl, hr, interval, bias, StrafePlanner.NONE, 1.0);
            assertEquals(da, db, "strength 0 at tick " + t);
            assertEquals(da, dc, "NONE at tick " + t);
        }
    }

    @Test
    void movingWithTheOpponentNeedsLessTurnRate() {
        double with = meanYawRate(true);
        double against = meanYawRate(false);
        assertTrue(with < against * 0.5, "with " + with + " against " + against);
    }

    /** Opponent A-D strafing (period 12) 3 blocks ahead; our planner fed the mirrored (or opposite) direction. */
    private static double meanYawRate(boolean moveWith) {
        StrafePlanner p = new StrafePlanner();
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0;
        s.pz = 0;
        s.tx = 0;
        s.tz = 3;
        double speed = 0.2;
        double sum = 0;
        int n = 0;
        for (int t = 0; t < 600; t++) {
            int oppSign = ((t / 12) & 1) == 0 ? 1 : -1; // + = its own right while facing us = our left = +x here
            s.tvx = oppSign * speed;
            s.tvz = 0;
            int withDir = StrafePlanner.mirrorOf(oppSign);
            int d = p.update(false, false, 30, 0.0, moveWith ? withDir : -withDir, 0.8);
            double own = d * speed; // StrafePlanner.RIGHT = +1 = our right
            if (t >= 60) {
                sum += AimSolver.neededYawRateDegPerTick(s, own);
                n++;
            }
        }
        return sum / n;
    }
}
