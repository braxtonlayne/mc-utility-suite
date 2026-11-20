package com.utilitysuite.world.minimap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimapPaletteTest {

    private static int r(int c) {
        return c >> 16 & 0xFF;
    }

    private static int g(int c) {
        return c >> 8 & 0xFF;
    }

    private static int b(int c) {
        return c & 0xFF;
    }

    @Test
    void militaryTintsTowardOliveGreen() {
        int[] inputs = {0x7FB238 /* grass */, 0x707070 /* stone */, 0xF7E9A3 /* sand */, 0xFF0000, 0x0000FF};
        for (int rgb : inputs) {
            int out = MinimapPalette.apply(MinimapPalette.Style.MILITARY, rgb, MinimapPalette.SHADE_NORMAL, false,
                    false);
            assertEquals(0xFF, out >>> 24);
            assertTrue(g(out) >= r(out), "green dominates red for " + Integer.toHexString(rgb));
            assertTrue(g(out) > b(out), "green dominates blue for " + Integer.toHexString(rgb));
        }
        // Pure red and pure blue end up close together (desaturated).
        int red = MinimapPalette.apply(MinimapPalette.Style.MILITARY, 0xFF0000, 1, false, false);
        int blue = MinimapPalette.apply(MinimapPalette.Style.MILITARY, 0x0000FF, 1, false, false);
        assertTrue(Math.abs(r(red) - r(blue)) < 60);
    }

    @Test
    void militaryWaterIsDarkTeal() {
        int w = MinimapPalette.apply(MinimapPalette.Style.MILITARY, 0x4040FF, 1, true, false);
        assertTrue(g(w) > r(w) && b(w) > r(w));
        assertTrue(g(w) < 100 && b(w) < 100);
    }

    @Test
    void gridLightensOnlyInMilitary() {
        int plain = MinimapPalette.apply(MinimapPalette.Style.MILITARY, 0x707070, 1, false, false);
        int grid = MinimapPalette.apply(MinimapPalette.Style.MILITARY, 0x707070, 1, false, true);
        assertTrue(g(grid) > g(plain));
        assertEquals(MinimapPalette.apply(MinimapPalette.Style.CLASSIC, 0x707070, 1, false, false),
                MinimapPalette.apply(MinimapPalette.Style.CLASSIC, 0x707070, 1, false, true));
    }

    @Test
    void classicKeepsTrueColoursWithShading() {
        assertEquals(0xFF7FB238, MinimapPalette.apply(MinimapPalette.Style.CLASSIC, 0x7FB238,
                MinimapPalette.SHADE_BRIGHT, false, false));
        int dark = MinimapPalette.apply(MinimapPalette.Style.CLASSIC, 0x7FB238, MinimapPalette.SHADE_DARK, false,
                false);
        assertEquals(0x7F * 180 / 255, r(dark));
    }

    @Test
    void shadeAndChannelHelpers() {
        assertEquals(MinimapPalette.SHADE_BRIGHT, MinimapPalette.shadeFor(70, 64));
        assertEquals(MinimapPalette.SHADE_DARK, MinimapPalette.shadeFor(60, 64));
        assertEquals(MinimapPalette.SHADE_NORMAL, MinimapPalette.shadeFor(64, 64));
        assertEquals(0xFF332211, MinimapPalette.toAbgr(0xFF112233));
        assertEquals(0x80FFFFFF, MinimapPalette.withAlpha(0xFFFFFFFF, 128 / 255f) | 0);
    }
}
