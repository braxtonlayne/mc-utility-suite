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

    // GLFW key and action codes, copied so this class stays engine free (values are part of the GLFW ABI).
    static final int KEY_ENTER = 257;
    static final int KEY_RIGHT = 262;
    static final int KEY_LEFT = 263;
    static final int KEY_DOWN = 264;
    static final int KEY_UP = 265;
    static final int KEY_KP_ENTER = 335;
    static final int ACTION_RELEASE = 0;
    static final int ACTION_PRESS = 1;
    static final int ACTION_REPEAT = 2;

    /** Key decision: the key is not ours, let vanilla have it. */
    static final int PASS = 0;
    /** Key decision: swallow the key and run the navigation for it. */
    static final int NAVIGATE = 1;
    /** Key decision: swallow the key without navigating (release, or Enter repeat, of a consumed press). */
    static final int SWALLOW = 2;

    private int category;
    private int module;
    private boolean inModules;
    /** One bit per navigation key ({@link #slotOf}) whose press was swallowed and whose release is still due. */
    private int consumedMask;

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

    /** Bit slot of a navigation key, or -1 for any other key. */
    static int slotOf(int key) {
        return switch (key) {
            case KEY_UP -> 0;
            case KEY_DOWN -> 1;
            case KEY_LEFT -> 2;
            case KEY_RIGHT -> 3;
            case KEY_ENTER -> 4;
            case KEY_KP_ENTER -> 5;
            default -> -1;
        };
    }

    /**
     * Decides whether a raw key event is consumed by the Tab-GUI. Allocation free, no engine calls.
     * <ul>
     *   <li>PRESS of a navigation key while {@code visible}: consumed ({@link #NAVIGATE}); Enter only when
     *   {@code enterToggles}. The release is then remembered.</li>
     *   <li>REPEAT follows its press: consumed only when the press was ({@link #NAVIGATE} for arrows while still
     *   visible, {@link #SWALLOW} for Enter, which toggles once per press), otherwise passed.</li>
     *   <li>RELEASE is swallowed exactly when its press was, whatever the visibility is now, so a key mapping never sees
     *   a press without its release (stuck key) or a release without its press.</li>
     * </ul>
     */
    int decide(int key, int action, boolean visible, boolean enterToggles) {
        int slot = slotOf(key);
        if (slot < 0) {
            return PASS;
        }
        int bit = 1 << slot;
        boolean enter = key == KEY_ENTER || key == KEY_KP_ENTER;
        if (action == ACTION_RELEASE) {
            if ((consumedMask & bit) == 0) {
                return PASS;
            }
            consumedMask &= ~bit;
            return SWALLOW;
        }
        if (action == ACTION_REPEAT) {
            if ((consumedMask & bit) == 0) {
                return PASS;
            }
            return enter || !visible ? SWALLOW : NAVIGATE;
        }
        if (action != ACTION_PRESS || !visible || (enter && !enterToggles)) {
            return PASS;
        }
        consumedMask |= bit;
        return NAVIGATE;
    }

    /** True while a navigation key's press was swallowed and its release has not arrived yet. */
    boolean awaitingRelease(int key) {
        int slot = slotOf(key);
        return slot >= 0 && (consumedMask & (1 << slot)) != 0;
    }

    /** Resets the cursor. Pending releases are kept on purpose: their presses never reached vanilla. */
    void reset() {
        category = 0;
        module = 0;
        inModules = false;
    }
}
