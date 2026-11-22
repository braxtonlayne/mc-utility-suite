package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrafePhaseModelTest {

    private static final double SPEED = 0.2;

    /** Opponent 3 blocks in front of an observer at the origin, strafing along x; one sample per tick. */
    private static final class Strafer {
        double x;
        final double z = 3.0;

        /** Observe velocity v at tick t, then advance (position(t+1) = position(t) + v(t)). */
        void step(StrafePhaseModel m, int id, long t, double v) {
            m.observe(id, t, x, z, v, 0.0, 0.0, 0.0);
            x += v;
        }
    }

    /** A-D strafer: + for {@code period} ticks, then -, starting at tick 0. */
    private static double adVelocity(long t, int period) {
        return ((t / period) & 1L) == 0 ? SPEED : -SPEED;
    }

    @Test
    void regularStraferIsLearned() {
        StrafePhaseModel m = new StrafePhaseModel();
        Strafer o = new Strafer();
        for (long t = 0; t < 120; t++) {
            o.step(m, 1, t, adVelocity(t, 12));
            if (t >= 60 && t % 12 >= StrafePhaseModel.MIN_HOLD - 1) {
                assertEquals(12 - (int) (t % 12), m.ticksToReversal(1, t), "tick " + t);
                int sign = adVelocity(t, 12) > 0 ? 1 : -1;
                assertEquals(sign, m.lateralSignAt(1, t, 0));
                int ahead = 12 - (int) (t % 12);
                assertEquals(-sign, m.lateralSignAt(1, t, ahead), "flipped at the reversal, tick " + t);
                assertEquals(sign, m.lateralSignAt(1, t, ahead + 12), "flipped back a period later");
            }
        }
        assertEquals(12.0, m.periodTicks(1), 1.0);
        assertTrue(m.confidence(1) > 0.6, "confidence " + m.confidence(1));
    }

    @Test
    void shortJitterIsNotAReversal() {
        StrafePhaseModel m = new StrafePhaseModel();
        Strafer o = new Strafer();
        for (long t = 0; t < 144; t++) {
            double v = adVelocity(t, 12);
            if (t % 12 == 5 || t % 12 == 6) {
                v = -v; // a two-tick tap of the other key mid-hold
            }
            o.step(m, 1, t, v);
        }
        assertEquals(12.0, m.periodTicks(1), 1.0);
        assertTrue(m.confidence(1) > 0.6, "confidence " + m.confidence(1));
    }

    @Test
    void phaseLeadBeatsPlainLeadAroundAReversal() {
        StrafePhaseModel m = new StrafePhaseModel();
        Strafer o = new Strafer();
        double[] out = new double[2];
        double lead = 4.0;
        double errPhase = 0;
        double errPlain = 0;
        for (long t = 0; t < 240; t++) {
            double v = adVelocity(t, 12);
            o.step(m, 1, t, v);
            long phase = t % 12;
            if (t >= 96 && phase >= 8 && phase <= 11) {
                double truth = 0;
                for (long k = t; k < t + (long) lead; k++) {
                    truth += adVelocity(k, 12);
                }
                double used = m.predictOffset(1, t, lead, v, 0.0, out);
                assertTrue(used > 0.5, "confidence used " + used);
                // never farther sideways than the plain lead
                assertTrue(Math.hypot(out[0], out[1]) <= Math.abs(v * lead) + 1e-9);
                errPhase += Math.hypot(out[0] - truth, out[1]);
                errPlain += Math.abs(v * lead - truth);
            }
        }
        assertTrue(errPhase < errPlain * 0.5, "phase " + errPhase + " plain " + errPlain);
    }

    @Test
    void randomStrafingFallsBackToThePlainLead() {
        StrafePhaseModel m = new StrafePhaseModel();
        Strafer o = new Strafer();
        Random rnd = new Random(7);
        double[] out = new double[2];
        int sign = 1;
        int left = 3 + rnd.nextInt(28);
        double confSum = 0;
        int confN = 0;
        for (long t = 0; t < 1200; t++) {
            if (--left <= 0) {
                sign = -sign;
                left = 3 + rnd.nextInt(28);
            }
            double v = sign * SPEED;
            o.step(m, 1, t, v);
            if (t > 200) {
                confSum += m.confidence(1);
                confN++;
                double c = m.predictOffset(1, t, 3.0, v, 0.0, out);
                if (c == 0.0) {
                    assertEquals(v * 3.0, out[0], 1e-9);
                    assertEquals(0.0, out[1], 1e-9);
                }
            }
        }
        assertTrue(m.confidence(1) < 0.3, "confidence " + m.confidence(1));
        assertTrue(confSum / confN < 0.3, "mean confidence " + confSum / confN);
        m.predictOffset(1, 1199, 3.0, SPEED, 0.0, out);
        assertEquals(SPEED * 3.0, out[0], 0.05);
        assertEquals(0.0, out[1], 0.05);
    }

    @Test
    void standingOpponentHasNoSide() {
        StrafePhaseModel m = new StrafePhaseModel();
        for (long t = 0; t < 40; t++) {
            m.observe(1, t, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0);
        }
        assertEquals(0, m.lateralSignAt(1, 39, 0));
        assertEquals(0, m.lateralSignAt(1, 39, 10));
        assertEquals(0.0, m.confidence(1));
        assertEquals(Integer.MAX_VALUE, m.ticksToReversal(1, 39));

        // learned strafer that stops: sign 0 and confidence 0 after the standing window
        Strafer o = new Strafer();
        for (long t = 0; t < 120; t++) {
            o.step(m, 2, t, adVelocity(t, 12));
        }
        for (long t = 120; t < 120 + StrafePhaseModel.STAND_TICKS + 1; t++) {
            o.step(m, 2, t, 0.0);
        }
        assertEquals(0, m.lateralSignAt(2, 129, 0));
        assertEquals(0.0, m.confidence(2));
    }

    @Test
    void unknownOpponentIsPlainLead() {
        StrafePhaseModel m = new StrafePhaseModel();
        double[] out = new double[2];
        assertEquals(0.0, m.predictOffset(42, 0, 3.0, 0.1, -0.2, out));
        assertEquals(0.3, out[0], 1e-12);
        assertEquals(-0.6, out[1], 1e-12);
        assertEquals(0.0, m.periodTicks(42));
        assertEquals(Integer.MAX_VALUE, m.ticksToReversal(42, 0));
    }

    @Test
    void leastRecentlySeenSlotIsReusedAndForgetClears() {
        StrafePhaseModel m = new StrafePhaseModel();
        Strafer o = new Strafer();
        for (long t = 0; t < 120; t++) {
            o.step(m, 0, t, adVelocity(t, 12));
        }
        assertTrue(m.periodTicks(0) > 0);
        for (int id = 1; id <= StrafePhaseModel.MAX_OPPONENTS; id++) {
            m.observe(id, 200 + id, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0);
        }
        assertEquals(-1, m.slotOf(0), "oldest opponent evicted");
        assertEquals(0.0, m.periodTicks(0));
        assertTrue(m.slotOf(StrafePhaseModel.MAX_OPPONENTS) >= 0);

        Strafer o2 = new Strafer();
        for (long t = 300; t < 420; t++) {
            o2.step(m, 3, t, adVelocity(t, 12));
        }
        assertTrue(m.periodTicks(3) > 0);
        m.forget(3);
        assertEquals(-1, m.slotOf(3));
        assertEquals(0.0, m.periodTicks(3));
        assertEquals(0.0, m.confidence(3));
        m.clear();
        assertEquals(-1, m.slotOf(1));
    }

    @Test
    void circleStraferFadesToPlainLead() {
        StrafePhaseModel m = new StrafePhaseModel();
        Strafer o = new Strafer();
        long t = 0;
        for (; t < 120; t++) {
            o.step(m, 1, t, adVelocity(t, 12));
        }
        // then holds one side for a long time (lateral axis stays nearly fixed because x drifts slowly vs 3 blocks)
        double v = adVelocity(t, 12);
        for (long end = t + 40; t < end; t++) {
            m.observe(1, t, 0.0, 3.0, v, 0.0, 0.0, 0.0);
        }
        assertEquals(0.0, m.confidence(1), 1e-9);
        double[] out = new double[2];
        assertEquals(0.0, m.predictOffset(1, t - 1, 4.0, v, 0.0, out));
        assertEquals(v * 4.0, out[0], 1e-12);
    }
}
