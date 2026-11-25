package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.IContainerClicker.Click;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine-free click scripts that move an exact count from one menu slot to an empty (or same-item) slot, for
 * {@code IContainerClicker.queueClick}. A whole stack moves with two left clicks; a partial count picks the stack up and
 * places single items with right clicks either into the target ({@code k} clicks) or back into the source
 * ({@code S - k} clicks), whichever is fewer, then drops the carried rest.
 */
public final class SlotTransfer {

    private SlotTransfer() {
    }

    /** One queued click: slot, mouse button (0 left, 1 right), click type. */
    public record Step(int slot, int button, Click click) {
    }

    /**
     * @param src      source slot holding {@code srcCount} items
     * @param dst      target slot (empty or same item with room)
     * @param want     items to move
     * @return the clicks; empty when nothing to move
     */
    public static List<Step> exact(int src, int srcCount, int dst, int want) {
        List<Step> out = new ArrayList<>();
        int k = Math.min(want, srcCount);
        if (k <= 0) {
            return out;
        }
        out.add(new Step(src, 0, Click.PICKUP));
        if (k == srcCount) {
            out.add(new Step(dst, 0, Click.PICKUP));
        } else if (k <= srcCount - k) {
            for (int i = 0; i < k; i++) {
                out.add(new Step(dst, 1, Click.PICKUP));
            }
            out.add(new Step(src, 0, Click.PICKUP));
        } else {
            for (int i = 0; i < srcCount - k; i++) {
                out.add(new Step(src, 1, Click.PICKUP));
            }
            out.add(new Step(dst, 0, Click.PICKUP));
        }
        return out;
    }
}
