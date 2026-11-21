package com.utilitysuite.ui;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-key decision of the Tab-GUI allocates nothing. */
class TabGuiNavAllocationTest {

    private static final long BUDGET = 1_024;
    private static final int[] KEYS = {TabGuiNav.KEY_UP, TabGuiNav.KEY_DOWN, TabGuiNav.KEY_LEFT, TabGuiNav.KEY_RIGHT,
            TabGuiNav.KEY_ENTER, TabGuiNav.KEY_KP_ENTER, 65, 32};

    @Test
    void decideAndNavigate() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TabGuiNav nav = new TabGuiNav();
        int[] i = {0};
        int[] sink = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            int k = i[0]++;
            int key = KEYS[k & 7];
            int action = (k >> 3) % 3;
            int d = nav.decide(key, action, (k & 64) != 0, (k & 128) != 0);
            sink[0] += d;
            if (d == TabGuiNav.NAVIGATE) {
                sink[0] += nav.up(4).ordinal() + nav.down(4).ordinal() + nav.right(4).ordinal() + nav.left().ordinal();
            }
            nav.clampModule(3);
            sink[0] += nav.awaitingRelease(key) ? 1 : 0;
        });
        assertTrue(bytes < BUDGET, "decide allocated " + bytes + " bytes");
    }
}
