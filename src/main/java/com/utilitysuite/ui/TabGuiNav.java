package com.utilitysuite.ui;

import com.utilitysuite.core.api.ModuleCategory;

/**
 * Engine-free navigation state of the Tab-GUI (arrow-key category list): Up/Down move within the current list
 * (wrapping), Right/Enter opens a category or toggles the highlighted module, Left goes back to the categories.
 * Allocation free.
 */
final class TabGuiNav {

    /** What a key press did. */
    enum Result { NONE, MOVED, OPENED, TOGGLE, CLOSED }

    /** The categories the list shows, in menu order (CORE is internal and never listed). */
    static final ModuleCategory[] CATEGORIES = {ModuleCategory.JOBS, ModuleCategory.WORLD, ModuleCategory.TRAVEL,
            ModuleCategory.COMBAT, ModuleCategory.TOOLS};
    /** Display names, indexed like {@link #CATEGORIES}. */
    static final String[] CATEGORY_NAMES = {"Jobs", "World", "Travel", "Combat", "Tools"};

    private int category;
    private int module;
    private boolean inModules;

    int category() {
        return category;
    }

    ModuleCategory selectedCategory() {
        return CATEGORIES[category];
    }

    int module() {
        return module;
    }

    boolean inModules() {
        return inModules;
    }

    /** Up arrow; {@code moduleCount} is the size of the open category's module list. */
    Result up(int moduleCount) {
        if (inModules) {
            if (moduleCount <= 0) {
                return Result.NONE;
            }
            module = (module - 1 + moduleCount) % moduleCount;
        } else {
            category = (category - 1 + CATEGORIES.length) % CATEGORIES.length;
        }
        return Result.MOVED;
    }

    /** Down arrow. */
    Result down(int moduleCount) {
        if (inModules) {
            if (moduleCount <= 0) {
                return Result.NONE;
            }
            module = (module + 1) % moduleCount;
        } else {
            category = (category + 1) % CATEGORIES.length;
        }
        return Result.MOVED;
    }

    /**
     * Right arrow or Enter: opens the highlighted category ({@code moduleCount} of the category under the cursor), or,
     * inside a category, asks the caller to toggle the highlighted module.
     */
    Result right(int moduleCount) {
        if (inModules) {
            return moduleCount > 0 ? Result.TOGGLE : Result.NONE;
        }
        if (moduleCount <= 0) {
            return Result.NONE;
        }
        inModules = true;
        module = 0;
        return Result.OPENED;
    }

    /** Left arrow: back to the category list. */
    Result left() {
        if (!inModules) {
            return Result.NONE;
        }
        inModules = false;
        return Result.CLOSED;
    }

    /** Keeps the module cursor valid after the open category's list changed size. */
    void clampModule(int moduleCount) {
        if (moduleCount <= 0) {
            module = 0;
            inModules = false;
        } else if (module >= moduleCount) {
            module = moduleCount - 1;
        }
    }

    void reset() {
        category = 0;
        module = 0;
        inModules = false;
    }
}
