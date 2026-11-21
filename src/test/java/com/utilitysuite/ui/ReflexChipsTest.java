package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReflexChipsTest {

    private static IAutopilot fake(boolean on, Set<Reflex> allowed) {
        return new IAutopilot() {
            @Override
            public boolean isOn() {
                return on;
            }

            @Override
            public boolean allows(Reflex reflex) {
                return on && allowed.contains(reflex);
            }

            @Override
            public Reach reach() {
                return Reach.ASSIST;
            }
        };
    }

    @Test
    void everyReflexHasALabelAndAStatusLineInOrder() {
        assertEquals(Reflex.values().length, ReflexChips.ORDER.length);
        assertEquals(EnumSet.allOf(Reflex.class), EnumSet.copyOf(java.util.Arrays.asList(ReflexChips.ORDER)));
        for (Reflex r : Reflex.values()) {
            assertNotNull(ReflexChips.CHIP_LABEL[r.ordinal()]);
            assertNotNull(ReflexChips.STATUS_LINE[r.ordinal()]);
        }
        assertArrayEquals(new Reflex[]{Reflex.FALL_CLUTCH, Reflex.PARKOUR, Reflex.PARKOUR_BLOCKS, Reflex.TOTEM},
                java.util.Arrays.copyOf(ReflexChips.ORDER, ReflexChips.CORE_COUNT));
    }

    @Test
    void statusLineIsTheSameInstanceEveryCall() {
        assertNull(ReflexChips.statusLine(null));
        assertSame(ReflexChips.statusLine(Reflex.TOTEM), ReflexChips.statusLine(Reflex.TOTEM));
        assertEquals("Autopilot: fall clutch", ReflexChips.statusLine(Reflex.FALL_CLUTCH));
    }

    @Test
    void extraChipsAppearOnlyWhenTheWidthAllows() {
        int[] w = new int[ReflexChips.ORDER.length];
        java.util.Arrays.fill(w, 40);
        int gap = 4;
        assertEquals(0, ReflexChips.visibleCount(30, w, gap));
        assertEquals(4, ReflexChips.visibleCount(4 * 40 + 3 * gap, w, gap));
        assertEquals(4, ReflexChips.visibleCount(5 * 40 + 4 * gap - 1, w, gap));
        assertEquals(5, ReflexChips.visibleCount(5 * 40 + 4 * gap, w, gap));
        assertEquals(ReflexChips.ORDER.length, ReflexChips.visibleCount(10_000, w, gap));
    }

    @Test
    void litFollowsTheAutopilot() {
        boolean[] lit = new boolean[ReflexChips.ORDER.length];
        ReflexChips.fillLit(null, lit);
        for (boolean b : lit) {
            assertEquals(false, b);
        }
        ReflexChips.fillLit(fake(true, EnumSet.of(Reflex.PARKOUR, Reflex.SHIELD)), lit);
        for (int i = 0; i < lit.length; i++) {
            Reflex r = ReflexChips.ORDER[i];
            assertEquals(r == Reflex.PARKOUR || r == Reflex.SHIELD, lit[i], r.name());
        }
        ReflexChips.fillLit(fake(false, EnumSet.allOf(Reflex.class)), lit);
        for (boolean b : lit) {
            assertEquals(false, b);
        }
    }
}
