package com.utilitysuite.ui;

import com.utilitysuite.ui.SuiteMenuScreen.TooltipPlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The menu tooltip never covers the control it explains and always stays on screen (when the screen has room). */
class TooltipPlacementTest {

    private static final int SW = 480;
    private static final int SH = 270;
    private static final int M = 4;

    private static boolean overlaps(int x, int y, int w, int h, int ax, int ay, int aw, int ah) {
        return x < ax + aw && ax < x + w && y < ay + ah && ay < y + h;
    }

    private static void check(int pointerX, int ax, int ay, int aw, int ah, int tw, int th) {
        long p = TooltipPlacement.place(pointerX, ax, ay, aw, ah, tw, th, SW, SH, M);
        int x = TooltipPlacement.x(p);
        int y = TooltipPlacement.y(p);
        String at = "anchor " + ax + "," + ay + " " + aw + "x" + ah + " tip " + tw + "x" + th + " -> " + x + "," + y;
        assertFalse(overlaps(x, y, tw, th, ax, ay, aw, ah), at);
        assertTrue(x >= M && x + tw <= SW - M, at);
        assertTrue(y >= M && y + th <= SH - M, at);
    }

    @Test
    void prefersBelowRightOfThePointer() {
        long p = TooltipPlacement.place(100, 80, 50, 60, 14, 120, 30, SW, SH, M);
        assertEquals(108, TooltipPlacement.x(p));
        assertEquals(50 + 14 + TooltipPlacement.GAP, TooltipPlacement.y(p));
    }

    @Test
    void flipsAboveNearTheBottomAndLeftNearTheRightEdge() {
        long p = TooltipPlacement.place(470, 440, 250, 30, 14, 120, 30, SW, SH, M);
        assertEquals(SW - M - 120, TooltipPlacement.x(p));
        assertEquals(250 - TooltipPlacement.GAP - 30, TooltipPlacement.y(p));
    }

    @Test
    void neverCoversTheAnchorAnywhereOnScreen() {
        int[][] anchors = {{0, 0, 40, 14}, {440, 0, 40, 14}, {0, 256, 40, 14}, {440, 256, 40, 14},
                {200, 120, 80, 20}, {10, 100, 460, 22}, {300, 40, 30, 190}, {4, 4, 120, 262}};
        int[][] tips = {{60, 20}, {200, 40}, {216, 70}};
        for (int[] a : anchors) {
            for (int[] t : tips) {
                for (int px = a[0]; px < a[0] + a[2]; px += Math.max(1, a[2] / 4)) {
                    check(px, a[0], a[1], a[2], a[3], t[0], t[1]);
                }
            }
        }
    }

    @Test
    void packRoundTripsNegativeAndPositive() {
        long p = TooltipPlacement.pack(-7, 123);
        assertEquals(-7, TooltipPlacement.x(p));
        assertEquals(123, TooltipPlacement.y(p));
        p = TooltipPlacement.pack(400, -3);
        assertEquals(400, TooltipPlacement.x(p));
        assertEquals(-3, TooltipPlacement.y(p));
    }
}
