package com.utilitysuite.world.minimap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimapLayoutTest {

    private static final int W = 480;
    private static final int H = 270;

    @Test
    void defaultsSitTopLeftInsideMargins() {
        assertEquals(MinimapLayout.MARGIN + MinimapLayout.BEZEL, MinimapLayout.toX(0, W, 128));
        assertEquals(MinimapLayout.MARGIN + MinimapLayout.BEZEL, MinimapLayout.toY(0, H, 128));
    }

    @Test
    void fractionsRoundTripAndStayOnScreen() {
        int x = MinimapLayout.toX(1, W, 128);
        assertEquals(W - MinimapLayout.MARGIN - MinimapLayout.BEZEL - 128, x);
        int y = MinimapLayout.toY(1, H, 128);
        assertEquals(H - MinimapLayout.MARGIN - MinimapLayout.BEZEL - MinimapLayout.BELOW - 128, y);
        assertEquals(1.0, MinimapLayout.toFracX(x, W, 128), 1e-9);
        assertEquals(0.5, MinimapLayout.toFracX(MinimapLayout.toX(0.5, W, 128), W, 128), 0.01);
        // Out-of-range fractions clamp.
        assertEquals(x, MinimapLayout.toX(3, W, 128));
        // Same fraction after a resize keeps the right anchor.
        assertEquals(800 - MinimapLayout.MARGIN - MinimapLayout.BEZEL - 128, MinimapLayout.toX(1, 800, 128));
    }

    @Test
    void dragClampsToScreen() {
        int min = MinimapLayout.MARGIN + MinimapLayout.BEZEL;
        assertEquals(min, MinimapLayout.clampX(-50, W, 128));
        assertEquals(MinimapLayout.toX(1, W, 128), MinimapLayout.clampX(10_000, W, 128));
        assertEquals(min, MinimapLayout.clampY(-1, H, 128));
        assertEquals(MinimapLayout.toY(1, H, 128), MinimapLayout.clampY(10_000, H, 128));
        assertEquals(100, MinimapLayout.clampX(100, W, 128));
    }

    @Test
    void resizeKeepsSquareAndClamps() {
        int x = 20;
        int y = 20;
        // Larger drag extent wins (square).
        assertEquals(120, MinimapLayout.resize(x, y, x + 100, y + 120, W, H));
        // Lower bound.
        assertEquals(MinimapLayout.MIN_SIZE, MinimapLayout.resize(x, y, x + 5, y + 5, W, H));
        // Upper bound by screen height: H - y - margin - bezel - below.
        int roomH = H - y - MinimapLayout.MARGIN - MinimapLayout.BEZEL - MinimapLayout.BELOW;
        assertEquals(roomH, MinimapLayout.resize(x, y, x + 1000, y + 1000, W, H));
        // Upper bound 320 on a big screen.
        assertEquals(MinimapLayout.MAX_SIZE, MinimapLayout.resize(x, y, 5000, 5000, 2000, 2000));
    }

    @Test
    void clampSizeFitsSmallScreens() {
        assertEquals(128, MinimapLayout.clampSize(128, W, H));
        assertEquals(MinimapLayout.MIN_SIZE, MinimapLayout.clampSize(10, W, H));
        int tiny = MinimapLayout.clampSize(320, 200, 120);
        assertTrue(tiny <= 120 - 2 * (MinimapLayout.MARGIN + MinimapLayout.BEZEL) - MinimapLayout.BELOW);
    }
}
