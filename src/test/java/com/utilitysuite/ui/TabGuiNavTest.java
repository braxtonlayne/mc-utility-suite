package com.utilitysuite.ui;

import com.utilitysuite.core.api.ModuleCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabGuiNavTest {

    @Test
    void listsEveryUserCategoryButCore() {
        assertEquals(ModuleCategory.values().length - 1, TabGuiNav.CATEGORIES.length);
        assertEquals(TabGuiNav.CATEGORIES.length, TabGuiNav.CATEGORY_NAMES.length);
        for (ModuleCategory c : TabGuiNav.CATEGORIES) {
            assertTrue(c != ModuleCategory.CORE);
        }
    }

    @Test
    void upDownWrapThroughCategories() {
        TabGuiNav n = new TabGuiNav();
        assertEquals(ModuleCategory.JOBS, n.selectedCategory());
        assertEquals(TabGuiNav.Result.MOVED, n.up(0));
        assertEquals(ModuleCategory.TOOLS, n.selectedCategory());
        n.down(0);
        n.down(0);
        assertEquals(ModuleCategory.WORLD, n.selectedCategory());
    }

    @Test
    void rightOpensThenTogglesAndLeftGoesBack() {
        TabGuiNav n = new TabGuiNav();
        assertEquals(TabGuiNav.Result.NONE, n.right(0)); // empty category does not open
        assertFalse(n.inModules());
        assertEquals(TabGuiNav.Result.OPENED, n.right(3));
        assertTrue(n.inModules());
        assertEquals(0, n.module());
        n.down(3);
        n.down(3);
        n.down(3);
        assertEquals(0, n.module()); // wrapped
        n.up(3);
        assertEquals(2, n.module());
        assertEquals(TabGuiNav.Result.TOGGLE, n.right(3));
        assertEquals(TabGuiNav.Result.CLOSED, n.left());
        assertFalse(n.inModules());
        assertEquals(TabGuiNav.Result.NONE, n.left());
        assertEquals(ModuleCategory.JOBS, n.selectedCategory()); // category cursor kept
    }

    @Test
    void clampAfterTheListShrinks() {
        TabGuiNav n = new TabGuiNav();
        n.right(5);
        n.up(5);
        assertEquals(4, n.module());
        n.clampModule(2);
        assertEquals(1, n.module());
        n.clampModule(0);
        assertFalse(n.inModules());
        n.reset();
        assertEquals(0, n.category());
    }

    // ---- consume decision (raw-key mixin) ---------------------------------------------------------------------------

    private static final int P = TabGuiNav.ACTION_PRESS;
    private static final int R = TabGuiNav.ACTION_RELEASE;
    private static final int H = TabGuiNav.ACTION_REPEAT;

    @Test
    void visibleNavKeyIsConsumedAndItsReleaseFollows() {
        TabGuiNav n = new TabGuiNav();
        assertEquals(TabGuiNav.NAVIGATE, n.decide(TabGuiNav.KEY_DOWN, P, true, true));
        assertTrue(n.awaitingRelease(TabGuiNav.KEY_DOWN));
        assertEquals(TabGuiNav.NAVIGATE, n.decide(TabGuiNav.KEY_DOWN, H, true, true)); // held arrow keeps moving
        assertEquals(TabGuiNav.SWALLOW, n.decide(TabGuiNav.KEY_DOWN, R, true, true));
        assertFalse(n.awaitingRelease(TabGuiNav.KEY_DOWN));
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_DOWN, R, true, true)); // only once
    }

    @Test
    void hiddenOrScreenOpenPassesEverything() {
        TabGuiNav n = new TabGuiNav();
        // "visible" is false when disabled, a screen is open, Clean-Screen, F1 or F3
        for (int key : new int[]{TabGuiNav.KEY_UP, TabGuiNav.KEY_DOWN, TabGuiNav.KEY_LEFT, TabGuiNav.KEY_RIGHT,
                TabGuiNav.KEY_ENTER, TabGuiNav.KEY_KP_ENTER}) {
            assertEquals(TabGuiNav.PASS, n.decide(key, P, false, true));
            assertEquals(TabGuiNav.PASS, n.decide(key, H, false, true));
            assertEquals(TabGuiNav.PASS, n.decide(key, R, false, true));
            assertFalse(n.awaitingRelease(key));
        }
    }

    @Test
    void otherKeysAlwaysPass() {
        TabGuiNav n = new TabGuiNav();
        int[] others = {32, 65, 87, 256, 258, 340, 290, -1};
        for (int key : others) {
            assertEquals(-1, TabGuiNav.slotOf(key));
            assertEquals(TabGuiNav.PASS, n.decide(key, P, true, true));
            assertEquals(TabGuiNav.PASS, n.decide(key, R, true, true));
        }
    }

    @Test
    void releaseOfAnUnconsumedPressIsNeverSwallowed() {
        TabGuiNav n = new TabGuiNav();
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_UP, P, false, true)); // pressed while hidden
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_UP, H, true, true)); // GUI appeared: repeat follows press
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_UP, R, true, true));
    }

    @Test
    void releaseOfAConsumedPressIsSwallowedEvenAfterHiding() {
        TabGuiNav n = new TabGuiNav();
        assertEquals(TabGuiNav.NAVIGATE, n.decide(TabGuiNav.KEY_LEFT, P, true, true));
        n.reset(); // e.g. module disabled or world unloaded while held
        assertEquals(TabGuiNav.SWALLOW, n.decide(TabGuiNav.KEY_LEFT, H, false, true)); // no navigation while hidden
        assertEquals(TabGuiNav.SWALLOW, n.decide(TabGuiNav.KEY_LEFT, R, false, true));
    }

    @Test
    void enterTogglesOncePerPressAndOnlyWhenEnabled() {
        TabGuiNav n = new TabGuiNav();
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_ENTER, P, true, false));
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_ENTER, R, true, false));
        assertEquals(TabGuiNav.NAVIGATE, n.decide(TabGuiNav.KEY_KP_ENTER, P, true, true));
        assertEquals(TabGuiNav.SWALLOW, n.decide(TabGuiNav.KEY_KP_ENTER, H, true, true));
        assertEquals(TabGuiNav.SWALLOW, n.decide(TabGuiNav.KEY_KP_ENTER, R, true, true));
    }

    @Test
    void keysAreTrackedIndependently() {
        TabGuiNav n = new TabGuiNav();
        n.decide(TabGuiNav.KEY_UP, P, true, true);
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_RIGHT, P, false, true));
        assertEquals(TabGuiNav.PASS, n.decide(TabGuiNav.KEY_RIGHT, R, false, true));
        assertTrue(n.awaitingRelease(TabGuiNav.KEY_UP));
        assertEquals(TabGuiNav.SWALLOW, n.decide(TabGuiNav.KEY_UP, R, false, true));
    }

    @Test
    void decideIsCheapOnTheHotPath() {
        TabGuiNav n = new TabGuiNav();
        int sink = 0;
        for (int i = 0; i < 200_000; i++) {
            sink += n.decide(TabGuiNav.KEY_DOWN, (i & 1) == 0 ? P : R, true, true);
            sink += n.decide(65, P, true, true);
        }
        assertEquals(200_000 / 2 * (TabGuiNav.NAVIGATE + TabGuiNav.SWALLOW), sink);
        assertFalse(n.awaitingRelease(TabGuiNav.KEY_DOWN));
    }
}
