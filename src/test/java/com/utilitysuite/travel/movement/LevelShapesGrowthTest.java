package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash in play (26.3, fresh world): "Index 64 out of bounds for length 64" in {@code boxCount}. The accessor loaded
 * the shape table before the cell lookup, and a first-sight state grew the table during that lookup, so the read hit
 * the old array. Every accessor now looks the cell up first.
 */
class LevelShapesGrowthTest {

    @Test
    void firstSightStatesGrowTheTablesDuringReads() {
        LevelShapes s = new LevelShapes();
        // every cell is a state never seen before, with its own shape: the shape table (64) and the entry tables (256)
        // both grow inside a read
        s.begin((x, y, z) -> s.defineEntry(new float[] {0f, 0f, 0f, 1f, 0.01f + x * 0.001f, 1f}, (byte) 0, 0.6f));
        for (int x = 0; x < 600; x++) {
            assertEquals(1, s.boxCount(x, 0, 0), "boxCount x=" + x);
        }
        s.begin((x, y, z) -> s.defineEntry(new float[] {0f, 0f, 0f, 1f, 0.02f + x * 0.001f, 1f}, (byte) 0, 0.6f));
        float[] out = new float[6];
        for (int x = 0; x < 600; x++) {
            s.box(x, 1, 0, 0, out, 0);
            assertEquals(0.02f + x * 0.001f, out[4], 1e-6f, "box x=" + x);
        }
        s.begin((x, y, z) -> s.defineEntry(new float[] {0f, 0f, 0f, 1f, 0.03f + x * 0.001f, 1f}, (byte) 0, 0.6f));
        for (int x = 0; x < 600; x++) {
            assertTrue(s.solid(x, 2, 0), "solid x=" + x);
        }
    }
}
