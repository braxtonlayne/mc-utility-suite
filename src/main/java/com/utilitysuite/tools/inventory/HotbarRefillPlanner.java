package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.api.IContainerClicker.Click;

/**
 * Decides which hotbar slots to replenish from the main inventory, and with which clicks. Pure Java, allocation free.
 * <p>
 * The planner remembers what each hotbar slot held during play ({@link #plan}). A slot is refilled when it holds (or
 * last held, if it just ran out) an item of an enabled category (blocks, food, pearls, rockets) and its count fell
 * below the threshold. Only stacks of the <em>same</em> item and components are moved in, and only from the main
 * inventory (slots 9-35): a hotbar slot is never filled with something else and never used as a source, so pinned
 * slots keep exactly what the user put there. Pinned slots are refilled like any other slot (with their own item).
 * <p>
 * While a screen is open the user may be rearranging items; {@link #observe} then syncs the memory to what is there
 * (forgetting emptied slots) so closing the inventory never "refills" a slot the user cleared on purpose.
 * <p>
 * Clicks (player inventory menu, no container open): an empty target gets one {@code SWAP} (source slot, button =
 * hotbar index); a partial target gets {@code PICKUP} source, {@code PICKUP} target and, if a remainder stays on the
 * cursor, {@code PICKUP} source again.
 */
public final class HotbarRefillPlanner {

    private final int[] memKey = new int[InventoryModel.HOTBAR];
    private final int[] memFlags = new int[InventoryModel.HOTBAR];
    private final int[] memMax = new int[InventoryModel.HOTBAR];
    private final int[] memItem = new int[InventoryModel.HOTBAR];
    private final boolean[] remembered = new boolean[InventoryModel.HOTBAR];

    /** Forgets everything (world change). */
    public void reset() {
        for (int i = 0; i < InventoryModel.HOTBAR; i++) {
            remembered[i] = false;
        }
    }

    /** Screen open: memory follows the inventory exactly (empty slots are forgotten). */
    public void observe(InventoryModel inv) {
        for (int h = 0; h < InventoryModel.HOTBAR; h++) {
            if (inv.isEmpty(h)) {
                remembered[h] = false;
            } else {
                remember(inv, h);
            }
        }
    }

    public boolean remembers(int hotbarSlot) {
        return remembered[hotbarSlot];
    }

    /**
     * Plans refills. The model is updated as if the clicks already happened, so one call never plans two refills from
     * the same source stack.
     *
     * @param categoryMask  {@link InventoryModel#FLAG_BLOCK} etc. that may be refilled
     * @param thresholdPct  refill when {@code count * 100 < maxStack * thresholdPct} (an empty slot always qualifies)
     * @return number of hotbar slots refilled
     */
    public int plan(InventoryModel inv, int categoryMask, int thresholdPct, ClickPlan out) {
        int refilled = 0;
        for (int h = 0; h < InventoryModel.HOTBAR; h++) {
            int key;
            int flags;
            int max;
            int count = inv.count[h];
            if (count > 0) {
                remember(inv, h);
                key = inv.key[h];
                flags = inv.flags[h];
                max = inv.maxStack[h];
            } else if (remembered[h]) {
                key = memKey[h];
                flags = memFlags[h];
                max = memMax[h];
            } else {
                continue;
            }
            if ((flags & categoryMask) == 0 || max <= 1) {
                continue;
            }
            if (count > 0 && (long) count * 100L >= (long) max * thresholdPct) {
                continue;
            }
            int src = bestSource(inv, key);
            if (src < 0) {
                continue;
            }
            int srcMenu = InventoryModel.inventoryMenuSlot(src);
            int srcCount = inv.count[src];
            if (count == 0) {
                if (!out.hasRoom(1)) {
                    break;
                }
                out.add(srcMenu, h, Click.SWAP);
                inv.set(h, memItem[h], key, srcCount, max, flags);
                inv.count[src] = 0;
            } else {
                int moved = Math.min(srcCount, max - count);
                boolean remainder = srcCount > moved;
                if (!out.hasRoom(remainder ? 3 : 2)) {
                    break;
                }
                out.add(srcMenu, 0, Click.PICKUP);
                out.add(InventoryModel.inventoryMenuSlot(h), 0, Click.PICKUP);
                if (remainder) {
                    out.add(srcMenu, 0, Click.PICKUP);
                }
                inv.count[h] = count + moved;
                inv.count[src] = srcCount - moved;
            }
            refilled++;
        }
        return refilled;
    }

    private void remember(InventoryModel inv, int h) {
        memKey[h] = inv.key[h];
        memFlags[h] = inv.flags[h];
        memMax[h] = inv.maxStack[h];
        memItem[h] = inv.item[h];
        remembered[h] = true;
    }

    /** Largest main-inventory stack with the same key, or -1. */
    static int bestSource(InventoryModel inv, int key) {
        int best = -1;
        int bestCount = 0;
        for (int s = InventoryModel.HOTBAR; s < InventoryModel.SLOTS; s++) {
            int c = inv.count[s];
            if (c > bestCount && inv.key[s] == key) {
                best = s;
                bestCount = c;
            }
        }
        return best;
    }
}
