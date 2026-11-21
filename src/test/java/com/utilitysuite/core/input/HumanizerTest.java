package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IInputSmoother;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HumanizerTest {

    @Test
    void sameSeedSameStream() {
        Humanizer a = new Humanizer(new PrimitiveRandom(1234L));
        Humanizer b = new Humanizer(new PrimitiveRandom(1234L));
        assertEquals(a.skill(), b.skill());
        double[] oa = new double[2];
        double[] ob = new double[2];
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.nextUnit(), b.nextUnit());
            assertEquals(a.reactionTicks(6, 2, 20), b.reactionTicks(6, 2, 20));
            a.aimOffset(0.3, 0.9, oa);
            b.aimOffset(0.3, 0.9, ob);
            assertArrayEquals(oa, ob);
        }
        Humanizer c = new Humanizer(new PrimitiveRandom(99L));
        assertNotEquals(a.nextUnit(), c.nextUnit());
    }

    @Test
    void skillIsInTheProBandAndOverridable() {
        for (long seed = 0; seed < 200; seed++) {
            double s = new Humanizer(new PrimitiveRandom(seed)).skill();
            assertTrue(s >= Humanizer.SKILL_MIN && s <= Humanizer.SKILL_MAX, "skill " + s);
        }
        Humanizer h = new Humanizer(new PrimitiveRandom(5L));
        h.setSkillOverride(0.2);
        assertEquals(0.2, h.skill());
        h.setSkillOverride(-1);
        assertEquals(h.sessionSkill(), h.skill());
    }

    @Test
    void reactionTicksStayInBoundsWithMedianNearRequest() {
        Humanizer h = new Humanizer(new PrimitiveRandom(77L));
        int n = 10_000;
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = h.reactionTicks(8, 3, 30);
            assertTrue(v[i] >= 3 && v[i] <= 30, "out of range: " + v[i]);
        }
        Arrays.sort(v);
        int median = v[n / 2];
        assertTrue(Math.abs(median - 8) <= 1, "median " + median);
        assertTrue(v[n - 1] > v[0], "has spread");
        assertEquals(4, h.reactionTicks(4, 4, 4));
        int swapped = h.reactionTicks(4, 7, 2); // bounds given the wrong way round
        assertTrue(swapped >= 2 && swapped <= 7);
    }

    @Test
    void reactionOffIsTheClampedMedian() {
        Humanizer h = new Humanizer(new PrimitiveRandom(3L));
        h.setEnabled(false);
        for (int i = 0; i < 100; i++) {
            assertEquals(8, h.reactionTicks(8, 3, 30));
        }
    }

    @Test
    void turnSecondsIsMonotonicAndNeverFasterThanTheCap() {
        for (double skill : new double[]{0.0, 0.6, 0.9, 1.0}) {
            Humanizer h = new Humanizer(new PrimitiveRandom(11L));
            h.setSkillOverride(skill);
            double prev = -1;
            for (double angle = 0.5; angle <= 360.0; angle += 0.5) {
                double t = h.turnSeconds(angle, 3.0);
                assertTrue(t >= prev, "monotonic at " + angle);
                assertTrue(t + 1e-12 >= angle / IInputSmoother.MAX_TURN_DEG_PER_SEC, "too fast at " + angle);
                prev = t;
            }
            // wider targets are quicker to hit (Fitts)
            assertTrue(h.turnSeconds(60, 10) < h.turnSeconds(60, 1));
        }
        Humanizer h = new Humanizer(new PrimitiveRandom(11L));
        assertEquals(0.0, h.turnSeconds(0, 3));
        h.setEnabled(false);
        assertEquals(180.0 / IInputSmoother.MAX_TURN_DEG_PER_SEC, h.turnSeconds(-180, 3), 1e-12);
        // higher skill turns faster
        Humanizer lo = new Humanizer(new PrimitiveRandom(1L));
        Humanizer hi = new Humanizer(new PrimitiveRandom(1L));
        lo.setSkillOverride(0.1);
        hi.setSkillOverride(0.9);
        assertTrue(hi.turnSeconds(90, 3) < lo.turnSeconds(90, 3));
    }

    @Test
    void aimOffsetStaysInsideTheBoxAndIsCentreWeighted() {
        Humanizer h = new Humanizer(new PrimitiveRandom(21L));
        h.setSkillOverride(0.0); // widest spread
        double[] out = new double[2];
        int inner = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            h.aimOffset(0.3, 0.9, out);
            assertTrue(Math.abs(out[0]) <= 0.3 && Math.abs(out[1]) <= 0.9, "outside: " + out[0] + "," + out[1]);
            if (Math.abs(out[0]) < 0.15) {
                inner++;
            }
        }
        assertTrue(inner > n * 0.6, "centre weighted: " + inner);
        h.setEnabled(false);
        h.aimOffset(0.3, 0.9, out);
        assertEquals(0.0, out[0]);
        assertEquals(0.0, out[1]);
    }

    @Test
    void gaussianIsStandardNormal() {
        PrimitiveRandom r = new PrimitiveRandom(8L);
        double sum = 0;
        double sq = 0;
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            double g = r.nextGaussian();
            sum += g;
            sq += g * g;
        }
        double mean = sum / n;
        assertEquals(0.0, mean, 0.03);
        assertEquals(1.0, sq / n - mean * mean, 0.05);
        for (int i = 0; i < 10_000; i++) {
            int v = r.nextInt(7);
            assertTrue(v >= 0 && v < 7);
        }
    }

    private static double sink;

    @Test
    void humanizerCallsAllocateNothing() {
        if (!AllocationProbe.supported()) {
            return;
        }
        Humanizer h = new Humanizer(new PrimitiveRandom(4L));
        double[] out = new double[2];
        long bytes = AllocationProbe.measure(100_000, () -> {
            sink += h.reactionTicks(6, 2, 20);
            sink += h.turnSeconds(45, 3);
            h.aimOffset(0.3, 0.9, out);
            sink += out[0] + h.nextUnit() + h.skill();
        });
        assertTrue(bytes < 1024, "allocated " + bytes + " bytes");
    }
}
