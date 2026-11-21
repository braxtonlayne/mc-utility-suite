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
}
