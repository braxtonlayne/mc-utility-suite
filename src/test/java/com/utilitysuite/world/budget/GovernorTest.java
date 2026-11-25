package com.utilitysuite.world.budget;

import com.utilitysuite.core.api.RenderBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernorTest {

    private static final long MS = 1_000_000L;

    @AfterEach
    void resetBudget() {
        RenderBudget.reset();
    }

    @Test
    void onePercentLowIsTheWorstFrames() {
        FrameTimeRing ring = new FrameTimeRing(1024);
        long t = 0;
        for (int i = 0; i < 200; i++) {
            long ft = (i == 50 || i == 150) ? 50 * MS : 10 * MS; // two 20 FPS hitches among 100 FPS frames
            t += ft;
            ring.add(t, ft);
        }
        double low = ring.onePercentLowFps(t, 10_000 * MS, 20);
        assertEquals(20.0, low, 1e-6, "2 of 200 frames = the 1%");
    }

    @Test
    void windowDropsOldFramesAndNeedsEnoughFrames() {
        FrameTimeRing ring = new FrameTimeRing(1024);
        long t = 0;
        for (int i = 0; i < 100; i++) { // an old 10 FPS stretch
            t += 100 * MS;
            ring.add(t, 100 * MS);
        }
        for (int i = 0; i < 300; i++) { // the last 2 s at ~150 FPS
            t += 6 * MS;
            ring.add(t, 6 * MS);
        }
        assertEquals(1.0e9 / (6 * MS), ring.onePercentLowFps(t, 1_500 * MS, 20), 1e-6);
        assertTrue(Double.isNaN(ring.onePercentLowFps(t + 10_000 * MS, 2_000 * MS, 20)), "nothing recent");
    }

    @Test
    void ringWrapsWithoutGrowing() {
        FrameTimeRing ring = new FrameTimeRing(16);
        for (int i = 1; i <= 100; i++) {
            ring.add(i * 10 * MS, 10 * MS);
        }
        assertEquals(16, ring.size());
        assertEquals(100.0, ring.onePercentLowFps(1000 * MS, 10_000 * MS, 1), 1e-6);
    }

    @Test
    void stepsDownAfterSustainedLowAndUpWithHysteresis() {
        BudgetGovernor g = new BudgetGovernor();
        g.configure(3, 0.15, 2, 4);
        assertEquals(0, g.evaluate(50, 60));
        assertEquals(1, g.evaluate(50, 60), "two low readings step down");
        assertEquals(1, g.evaluate(50, 60));
        assertEquals(2, g.evaluate(50, 60));
        g.evaluate(40, 60);
        assertEquals(3, g.evaluate(40, 60));
        assertEquals(3, g.evaluate(10, 60));
        assertEquals(3, g.evaluate(10, 60), "never past the deepest tier");
        // between target and target*1.15: hold
        for (int i = 0; i < 10; i++) {
            assertEquals(3, g.evaluate(65, 60));
        }
        g.evaluate(80, 60);
        g.evaluate(80, 60);
        g.evaluate(80, 60);
        assertEquals(2, g.evaluate(80, 60), "four good readings step back up one tier");
        assertEquals(2, g.evaluate(Double.NaN, 60), "NaN ignored");
    }

    @Test
    void streakBrokenByMiddleReading() {
        BudgetGovernor g = new BudgetGovernor();
        g.configure(3, 0.15, 2, 4);
        g.evaluate(50, 60);
        g.evaluate(65, 60);
        assertEquals(0, g.evaluate(50, 60), "the streak restarted");
    }

    @Test
    void maxTierCaps() {
        BudgetGovernor g = new BudgetGovernor();
        g.configure(1, 0.15, 1, 1);
        g.evaluate(10, 60);
        g.evaluate(10, 60);
        assertEquals(1, g.tier());
    }

    @Test
    void budgetHintsPerTier() {
        RenderBudget.setTier(0);
        assertEquals(1f, RenderBudget.overlayScale());
        assertFalse(RenderBudget.dropParticle());
        assertEquals(0.0, RenderBudget.armorStandCullDistanceSq());
        RenderBudget.setTier(2);
        int dropped = 0;
        for (int i = 0; i < 100; i++) {
            if (RenderBudget.dropParticle()) {
                dropped++;
            }
        }
        assertEquals(50, dropped);
        RenderBudget.setTier(9);
        assertEquals(RenderBudget.MAX_TIER, RenderBudget.tier());
        assertTrue(RenderBudget.armorStandCullDistanceSq() > 0);
        assertTrue(RenderBudget.overlayScale() < RenderBudget.overlayScale(1));
    }
}
