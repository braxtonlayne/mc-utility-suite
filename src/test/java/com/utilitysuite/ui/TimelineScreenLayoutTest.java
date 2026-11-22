package com.utilitysuite.ui;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.tools.packetlab.TimelineEvents;
import com.utilitysuite.ui.kit.MenuScale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineScreenLayoutTest {

    private static final int[][] WINDOWS = {{1760, 990}, {1920, 1080}};

    @Test
    void virtualSizeMatchesMenuScale() {
        assertEquals(1760, TimelineScreen.virtualSize(1760, 1));
        assertEquals(880, TimelineScreen.virtualSize(1760, 2));
        assertEquals(880, TimelineScreen.virtualSize(1760, 3));
        assertEquals(880, TimelineScreen.virtualSize(1760, 4));
        assertEquals(495, TimelineScreen.virtualSize(990, 4));
        assertEquals(541, TimelineScreen.virtualSize(1081, 4));
    }

    @Test
    void mouseRoundTripsAndMapsFullWidth() {
        for (int gs = 1; gs <= 4; gs++) {
            for (double gui : new double[]{0, 1, 17.5, 220, 439}) {
                double v = TimelineScreen.toVirtual(gui, gs);
                assertEquals(gui, MenuScale.toGui(v, gs), 1e-9);
            }
        }
        assertEquals(200.0, TimelineScreen.toVirtual(100, 4), 1e-9);
        assertEquals(150.0, TimelineScreen.toVirtual(100, 3), 1e-9);
        assertEquals(880.0, TimelineScreen.toVirtual(440, 4), 1e-9);
    }

    @Test
    void contentFitsInsideVirtualBoundsAtHighGuiScales() {
        for (int[] win : WINDOWS) {
            for (int gs = 3; gs <= 4; gs++) {
                int vw = TimelineScreen.virtualSize(win[0], gs);
                int vh = TimelineScreen.virtualSize(win[1], gs);
                int ww = TimelineScreen.windowSize(760, vw);
                int wh = TimelineScreen.windowSize(460, vh);
                int wx = (vw - ww) / 2;
                int wy = (vh - wh) / 2;
                assertTrue(wx >= 12 && wy >= 12 && wx + ww <= vw - 12 && wy + wh <= vh - 12);
                assertTrue(ww > 600, "wide enough at GUI scale " + gs);
            }
        }
    }

    @Test
    void timestampsFillAFixedDigitGrid() {
        assertEquals("  -3.2s", TimelineScreen.timeText(3.2));
        assertEquals(" -12.3s", TimelineScreen.timeText(12.34));
        assertEquals("-120.0s", TimelineScreen.timeText(120.0));
        assertEquals(TimelineScreen.timeText(0.1).length(), TimelineScreen.timeText(99.9).length());
    }

    @Test
    void everyEventKindHasAToken() {
        for (TimelineEvents.Kind k : TimelineEvents.Kind.values()) {
            assertTrue(TimelineScreen.tokenOf(k, "x") != null, k.name());
        }
        assertEquals(HoloToken.DANGER, TimelineScreen.tokenOf(TimelineEvents.Kind.CORRECTION, "x"));
    }
}
