package com.utilitysuite.ui;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Info strip formatting and caching: correct strings, rebuilt only on change, zero allocation when unchanged. */
class InfoStripTextTest {

    private static final long SLACK_BYTES = 16 * 1024;

    @Test
    void formatsSegments() {
        InfoStripText t = new InfoStripText();
        t.setCoords(12, 64, -40);
        assertEquals("X 12  Y 64  Z -40", t.coords());
        t.setConversion(InfoStripText.DIM_OVERWORLD, 100, -9);
        assertEquals("Nether 12, -2", t.conversion());
        t.setConversion(InfoStripText.DIM_NETHER, 10, -3);
        assertEquals("Overworld 80, -24", t.conversion());
        t.setConversion(InfoStripText.DIM_OTHER, 10, -3);
        assertEquals("", t.conversion());
        t.setTpsTenths(198);
        assertEquals("19.8 TPS", t.tps());
        t.setTpsTenths(250);
        assertEquals("20.0 TPS", t.tps());
        t.setPing(42);
        assertEquals("42 ms", t.ping());
        t.setPing(-1);
        assertEquals("", t.ping());
        Object key = new Object();
        t.setBiome(key, "minecraft:dark_forest");
        assertEquals("Dark Forest", t.biome());
        assertTrue(t.isBiome(key));
        assertEquals("Plains", InfoStripText.prettyBiome("minecraft:plains"));
    }

    @Test
    void rebuildsOnlyOnChange() {
        InfoStripText t = new InfoStripText();
        t.setCoords(1, 2, 3);
        String first = t.coords();
        int v = t.version();
        t.setCoords(1, 2, 3);
        assertSame(first, t.coords());
        assertEquals(v, t.version());
        t.setCoords(1, 2, 4);
        assertNotEquals(v, t.version());
        t.reset();
        assertEquals("", t.coords());
    }

    @Test
    void durabilityBars() {
        assertEquals(14, InfoStripText.barFill(0, 100, 14));
        assertEquals(7, InfoStripText.barFill(50, 100, 14));
        assertEquals(1, InfoStripText.barFill(99, 100, 14));
        assertEquals(0, InfoStripText.barFill(100, 100, 14));
        assertEquals(InfoStripText.GREEN, InfoStripText.barColor(0, 100));
        assertEquals(InfoStripText.YELLOW, InfoStripText.barColor(50, 100));
        assertEquals(InfoStripText.RED, InfoStripText.barColor(100, 100));
    }

    @Test
    void tpsEstimatorSmoothsWindows() {
        InfoStripText.TpsEstimator e = new InfoStripText.TpsEstimator(1_000_000_000L);
        e.sample(0, 0);
        e.sample(10, 1_000_000_000L); // 10 TPS window
        assertEquals(100, e.tenths());
        e.sample(30, 2_000_000_000L); // 20 TPS window, blended
        assertEquals(140, e.tenths());
    }

    @Test
    void unchangedValuesAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported(), "thread allocation counters unavailable");
        InfoStripText t = new InfoStripText();
        Object biome = new Object();
        InfoStripText.TpsEstimator e = new InfoStripText.TpsEstimator(3_000_000_000L);
        long[] now = {0};
        long bytes = AllocationProbe.measure(100_000, () -> {
            t.setCoords(120, 64, -300);
            t.setConversion(InfoStripText.DIM_OVERWORLD, 120, -300);
            t.setBiome(biome, "minecraft:plains");
            t.setTpsTenths(200);
            t.setPing(35);
            InfoStripText.barFill(10, 250, 14);
            InfoStripText.barColor(10, 250);
            e.sample(now[0] / 50_000_000L, now[0]);
            now[0] += 50_000_000L;
        });
        assertTrue(bytes < SLACK_BYTES, "allocated " + bytes);
    }
}
