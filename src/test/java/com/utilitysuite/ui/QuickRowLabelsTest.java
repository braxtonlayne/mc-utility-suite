package com.utilitysuite.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.ui.tabs.WorldTab;
import org.junit.jupiter.api.Test;

class QuickRowLabelsTest {
    @Test
    void goBackLabelFitsPill() throws Exception {
        var f = WorldTab.class.getDeclaredField("GO_BACK_LABEL");
        f.setAccessible(true);
        String label = (String) f.get(null);
        var w = WorldTab.class.getDeclaredField("GO_BACK_PILL_WIDTH");
        w.setAccessible(true);
        int width = (int) w.get(null);
        assertEquals("Go back", label);
        assertTrue(label.length() * 6 + 12 <= width);
    }
}
