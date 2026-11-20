package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.api.IContainerClicker.Click;

/**
 * Chooses which player stacks to shift-click into an open container. Pure Java, allocation free (reusable scratch).
 * <ul>
 *   <li>{@link Mode#MATCHING}: only items (by item id) the container already holds — "quick deposit".</li>
 *   <li>{@link Mode#ALL}: everything — "smart dump".</li>
 * </ul>
 * Pinned hotbar slots are never moved; with {@code keepHotbar} no hotbar slot is moved. Main inventory slots go first,
 * then the hotbar. The planner simulates the container's free space (partial stacks of the same key first, then empty
 * slots) and skips stacks that would not fit, so a full chest produces no useless clicks.
 */
public final class DepositPlanner {

    public enum Mode { MATCHING, ALL }

    /** Upper bound of container slots considered (a double chest has 54). */
    public static final int MAX_CONTAINER = 128;

    private final int[] roomKey = new int[MAX_CONTAINER];
    private final int[] room = new int[MAX_CONTAINER];
    private int roomSize;
    private int emptySlots;

    /**
     * @param inv              player inventory snapshot
     * @param menuSlotOfInv    menu slot index of each player inventory slot in the open menu (-1 if absent)
     * @param cItem            container slots: item ids
     * @param cKey             container slots: item + components keys
     * @param cCount           container slots: counts (0 = empty)
     * @param cMax             container slots: max stack size of the item in the slot
     * @param containerSlots   number of container slots
     * @param pinnedMask       pinned hotbar slots
     * @return number of stacks moved
     */
    public int plan(InventoryModel inv, int[] menuSlotOfInv, int[] cItem, int[] cKey, int[] cCount, int[] cMax,
                    int containerSlots, Mode mode, int pinnedMask, boolean keepHotbar, ClickPlan out) {
        int n = Math.min(containerSlots, MAX_CONTAINER);
        roomSize = 0;
        emptySlots = 0;
        for (int i = 0; i < n; i++) {
            if (cCount[i] <= 0) {
                emptySlots++;
            } else if (cCount[i] < cMax[i]) {
                addRoom(cKey[i], cMax[i] - cCount[i]);
            }
        }
        int moved = 0;
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? InventoryModel.HOTBAR : 0;
            int to = pass == 0 ? InventoryModel.SLOTS : InventoryModel.HOTBAR;
            for (int s = from; s < to; s++) {
                int count = inv.count[s];
                if (count <= 0 || menuSlotOfInv[s] < 0) {
                    continue;
                }
                if (s < InventoryModel.HOTBAR && (keepHotbar || PinnedSlots.isPinned(pinnedMask, s))) {
                    continue;
                }
                if (mode == Mode.MATCHING && !containsItem(cItem, cCount, n, inv.item[s])) {
                    continue;
                }
                if (!fits(inv.key[s], count, inv.maxStack[s])) {
                    continue;
                }
                if (!out.add(menuSlotOfInv[s], 0, Click.QUICK_MOVE)) {
                    return moved;
                }
                moved++;
            }
        }
        return moved;
    }

    private static boolean containsItem(int[] cItem, int[] cCount, int n, int item) {
        for (int i = 0; i < n; i++) {
            if (cCount[i] > 0 && cItem[i] == item) {
                return true;
            }
        }
        return false;
    }

    /** Consumes simulated room for the stack; true when at least part of it fits. */
    private boolean fits(int key, int count, int maxStack) {
        int remaining = count;
        for (int i = 0; i < roomSize && remaining > 0; i++) {
            if (roomKey[i] == key && room[i] > 0) {
                int take = Math.min(room[i], remaining);
                room[i] -= take;
                remaining -= take;
            }
        }
        boolean partial = remaining < count;
        if (remaining > 0 && emptySlots > 0) {
            int max = Math.max(1, maxStack);
            int slotsNeeded = (remaining + max - 1) / max;
            int used = Math.min(slotsNeeded, emptySlots);
            emptySlots -= used;
            int placed = Math.min(remaining, used * max);
            remaining -= placed;
            int leftover = used * max - placed;
            if (leftover > 0) {
                addRoom(key, leftover);
            }
            return true;
        }
        return partial;
    }

    private void addRoom(int key, int amount) {
        for (int i = 0; i < roomSize; i++) {
            if (roomKey[i] == key) {
                room[i] += amount;
                return;
            }
        }
        if (roomSize < MAX_CONTAINER) {
            roomKey[roomSize] = key;
            room[roomSize] = amount;
            roomSize++;
        }
    }
}
