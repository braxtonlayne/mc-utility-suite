package com.utilitysuite.ui;

import com.utilitysuite.ui.kit.MenuScale;
import com.utilitysuite.ui.kit.UiTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuScaleTest {

    private static final int[][] WINDOWS = {{1760, 990}, {1920, 1080}};

    @AfterEach
    void reset() {
        MenuScale.reset();
    }

    @Test
    void appliesOnlyFromGuiScaleThree() {
        assertFalse(MenuScale.applies(1));
        assertFalse(MenuScale.applies(2));
        assertTrue(MenuScale.applies(3));
        assertTrue(MenuScale.applies(4));
        assertEquals(1.0, MenuScale.factor(2), 1e-9);
        assertEquals(2.0 / 3.0, MenuScale.factor(3), 1e-9);
        assertEquals(0.5, MenuScale.factor(4), 1e-9);
    }

    @Test
    void virtualSizeMatchesVanillaBelowThreeAndHalvesTheWindowAbove() {
        assertEquals(1760, MenuScale.virtualSize(1760, 1));
        assertEquals(880, MenuScale.virtualSize(1760, 2));
        assertEquals(495, MenuScale.virtualSize(990, 2));
        assertEquals(880, MenuScale.virtualSize(1760, 3));
        assertEquals(880, MenuScale.virtualSize(1760, 4));
        assertEquals(495, MenuScale.virtualSize(990, 4));
        assertEquals(540, MenuScale.virtualSize(1080, 4));
        assertEquals(541, MenuScale.virtualSize(1081, 4)); // odd sizes round up like vanilla
        assertEquals(248, (int) Math.ceil(990 / 4.0)); // vanilla's GUI height at scale 4, for comparison
    }

    @Test
    void threeColumnsFitAtEveryScaleForBothWindows() {
        for (int[] win : WINDOWS) {
            for (int gs = 1; gs <= 4; gs++) {
                int vw = MenuScale.virtualSize(win[0], gs);
                int ww = Math.min(UiTheme.WINDOW_W, (int) (vw * 0.9f));
                assertTrue(ww >= UiTheme.THREE_COLUMN_MIN_W,
                        "window " + win[0] + "x" + win[1] + " at GUI scale " + gs + " gives " + ww + " px");
            }
        }
    }

    @Test
    void mouseRoundTripsBetweenGuiAndVirtual() {
        for (int gs = 1; gs <= 4; gs++) {
            for (double gui : new double[]{0, 1, 17.5, 220, 439}) {
                double v = MenuScale.toVirtual(gui, gs);
                assertEquals(gui, MenuScale.toGui(v, gs), 1e-9);
            }
        }
        // GUI scale 4: one GUI px = 4 window px = 2 virtual px
        assertEquals(200.0, MenuScale.toVirtual(100, 4), 1e-9);
        // GUI scale 3: one GUI px = 3 window px = 1.5 virtual px
        assertEquals(150.0, MenuScale.toVirtual(100, 3), 1e-9);
        assertEquals(100.0, MenuScale.toVirtual(100, 2), 1e-9);
        // the whole GUI width maps onto the whole virtual width
        assertEquals(880.0, MenuScale.toVirtual(440, 4), 1e-9);
    }

    @Test
    void scissorRoundsOutward() {
        double f = MenuScale.factor(3);
        assertEquals(6, MenuScale.scissorMin(10, f)); // 6.67 -> 6
        assertEquals(7, MenuScale.scissorMax(10, f)); // 6.67 -> 7
        assertEquals(20, MenuScale.scissorMin(30, f)); // exact stays exact
        assertEquals(20, MenuScale.scissorMax(30, f));
        double half = MenuScale.factor(4);
        assertEquals(5, MenuScale.scissorMin(11, half));
        assertEquals(6, MenuScale.scissorMax(11, half));
    }

    @Test
    void activeFactorDefaultsToOne() {
        assertEquals(1.0, MenuScale.active(), 1e-9);
        MenuScale.setActive(0.5);
        assertEquals(0.5, MenuScale.active(), 1e-9);
        MenuScale.setActive(0);
        assertEquals(1.0, MenuScale.active(), 1e-9);
    }
}
