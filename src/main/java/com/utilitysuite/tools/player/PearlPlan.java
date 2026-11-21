package com.utilitysuite.tools.player;

import java.util.function.IntPredicate;

/**
 * Engine-free plan for the quick pearl key. A pearl in the hotbar is selected directly (and the previous slot restored
 * after the throw). A pearl only in the main inventory is swapped into the CURRENTLY selected hotbar slot (one SWAP
 * click, no slot change), thrown, and swapped back with the same click so the original stack returns. Mutable and
 * reusable: {@link #plan} overwrites the fields.
 */
public final class PearlPlan {

    /** Hotbar slot to select for the throw (0..8), or -1 when there is no pearl. */
    public int useSlot = -1;
    /** Inventory index 9..35 the pearl is swapped in from, or -1 when it is already in the hotbar. */
    public int swapFromInv = -1;
    /** Hotbar slot to reselect afterwards, or -1 when the selection does not change. */
    public int restoreSlot = -1;

    /**
     * @param selected the currently selected hotbar slot
     * @param isPearl  inventory index (0..35) holds an ender pearl
     * @return true when a pearl was found
     */
    public boolean plan(int selected, IntPredicate isPearl) {
        useSlot = -1;
        swapFromInv = -1;
        restoreSlot = -1;
        if (isPearl.test(selected)) {
            useSlot = selected;
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (isPearl.test(i)) {
                useSlot = i;
                restoreSlot = selected;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (isPearl.test(i)) {
                useSlot = selected;
                swapFromInv = i;
                return true;
            }
        }
        return false;
    }

    public boolean needsSwap() {
        return swapFromInv >= 0;
    }

    public boolean needsSelect() {
        return restoreSlot >= 0;
    }
}
