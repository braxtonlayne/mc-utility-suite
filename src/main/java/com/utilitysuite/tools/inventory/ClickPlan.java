package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.api.IContainerClicker;

/**
 * A reusable list of container clicks produced by the SmartInventory planners and queued through
 * {@link IContainerClicker}. Fixed capacity, primitive arrays, cleared and refilled in place.
 */
public final class ClickPlan {

    public static final int CAPACITY = 128;

    private final int[] slot = new int[CAPACITY];
    private final int[] button = new int[CAPACITY];
    private final IContainerClicker.Click[] click = new IContainerClicker.Click[CAPACITY];
    private int size;

    public void clear() {
        size = 0;
    }

    /** Adds a click; returns false (and adds nothing) when the plan is full. */
    public boolean add(int menuSlot, int mouseButton, IContainerClicker.Click kind) {
        if (size >= CAPACITY) {
            return false;
        }
        slot[size] = menuSlot;
        button[size] = mouseButton;
        click[size] = kind;
        size++;
        return true;
    }

    /** Room for {@code n} more clicks. */
    public boolean hasRoom(int n) {
        return size + n <= CAPACITY;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int slot(int i) {
        return slot[i];
    }

    public int button(int i) {
        return button[i];
    }

    public IContainerClicker.Click click(int i) {
        return click[i];
    }
}
