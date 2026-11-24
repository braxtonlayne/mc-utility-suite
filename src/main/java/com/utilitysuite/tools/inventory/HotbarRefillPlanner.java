package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.api.IContainerClicker.Click;

/**
 * Decides which hotbar slots to replenish from the main inventory, and with which clicks. Pure Java, allocation free.
 * <p>
 * The planner remembers what each hotbar slot held during play ({@link #plan}). A slot is refilled when it holds (or
 * last held, if it just ran out) an item of an enabled category (blocks, food, pearls, rockets) and its count fell
 * below the threshold. Stacks of the <em>same</em> item and components are preferred and are the only source for a
 * top-up; they come only from the main inventory (slots 9-35), a hotbar slot is never a source.
 * <p>
 * Class refill: when a slot is empty and no identical stack exists, the slot is filled with the best remaining item of
 * the same {@link ItemClasses class} (pickaxe, sword, totem, food, ...) from slots 9-35, ranked by the score the
 * adapter supplied (tier and enchantments for tools, saturation for food). It is on for unpinned slots and off for
 * pinned slots by default ({@code sameClassUnpinned}, {@code sameClassPinned}), so a pinned slot keeps exactly its own
 * item unless the user opted in.
 * <p>
 * While a screen is open the user may be rearranging items; {@link #observe} then syncs the memory to what is there
 * (forgetting emptied slots) so closing the inventory never "refills" a slot the user cleared on purpose.
 * <p>
 * Clicks (player inventory menu, no container open): an empty target gets one {@code SWAP} (source slot, button =
 * hotbar index); a partial target gets {@code PICKUP} source, {@code PICKUP} target and, if a remainder stays on the
 * cursor, {@code PICKUP} source again.
 */
public final class HotbarRefillPlanner {

    /**
     * Item classes as small int codes plus a per-slot ranking score (higher is better, negative = never use), both
     * filled in place by the adapter for the 36 inventory slots. Reused, never reallocated.
     */
    public static final class ItemClasses {
        public static final int NONE = 0;
        public static final int PICKAXE = 1;
        public static final int AXE = 2;
        public static final int SHOVEL = 3;
        public static final int HOE = 4;
        public static final int SWORD = 5;
        public static final int BOW = 6;
        public static final int SHIELD = 7;
        public static final int TOTEM = 8;
        public static final int FOOD = 9;
        public static final int BLOCKS = 10;

        public final int[] code = new int[InventoryModel.SLOTS];
        public final int[] score = new int[InventoryModel.SLOTS];

        public void clear() {
            java.util.Arrays.fill(code, NONE);
            java.util.Arrays.fill(score, 0);
        }

        public ItemClasses set(int slot, int itemClass, int itemScore) {
            code[slot] = itemClass;
            score[slot] = itemScore;
            return this;
        }
    }

    private final int[] memKey = new int[InventoryModel.HOTBAR];
    private final int[] memFlags = new int[InventoryModel.HOTBAR];
    private final int[] memMax = new int[InventoryModel.HOTBAR];
    private final int[] memItem = new int[InventoryModel.HOTBAR];
    private final int[] memClass = new int[InventoryModel.HOTBAR];
    private final boolean[] remembered = new boolean[InventoryModel.HOTBAR];

    /** Forgets everything (world change). */
    public void reset() {
        for (int i = 0; i < InventoryModel.HOTBAR; i++) {
            remembered[i] = false;
        }
    }

    /** Screen open: memory follows the inventory exactly (empty slots are forgotten). */
    public void observe(InventoryModel inv) {
        observe(inv, null);
    }

    /** As {@link #observe(InventoryModel)}, also following item classes. */
    public void observe(InventoryModel inv, ItemClasses classes) {
        for (int h = 0; h < InventoryModel.HOTBAR; h++) {
            if (inv.isEmpty(h)) {
                remembered[h] = false;
            } else {
                remember(inv, classes, h);
            }
        }
    }

    /**
     * A pinned hotbar slot that is low (or ran out) while the main inventory holds none of its item: the reserve is
     * empty, so the user may want to look in a shulker box. Allocation free; call it only on the rare tick where
     * {@link #plan} refilled nothing. Returns the hotbar slot or -1; {@link #itemAt} gives its raw item id.
     */
    public int depletedPinned(InventoryModel inv, int categoryMask, int thresholdPct, int pinnedMask) {
        for (int h = 0; h < InventoryModel.HOTBAR; h++) {
            if (!PinnedSlots.isPinned(pinnedMask, h)) {
                continue;
            }
            int count = inv.count[h];
            int key;
            int flags;
            int max;
            if (count > 0) {
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
            if (max <= 1 || (flags & categoryMask) == 0) {
                continue;
            }
            if (count > 0 && (long) count * 100L >= (long) max * thresholdPct) {
                continue;
            }
            if (bestSource(inv, key) < 0) {
                return h;
            }
        }
        return -1;
    }

    /** Raw item id of what the hotbar slot holds, or last held when empty. */
    public int itemAt(InventoryModel inv, int hotbarSlot) {
        return inv.count[hotbarSlot] > 0 ? inv.item[hotbarSlot] : memItem[hotbarSlot];
    }

    public boolean remembers(int hotbarSlot) {
        return remembered[hotbarSlot];
    }

    /** Identical-item refill only (no item classes). */
    public int plan(InventoryModel inv, int categoryMask, int thresholdPct, ClickPlan out) {
        return plan(inv, null, categoryMask, thresholdPct, 0, false, false, out);
    }

    /**
     * Plans refills. The model (and {@code classes}) are updated as if the clicks already happened, so one call never
     * plans two refills from the same source stack.
     *
     * @param classes          item classes and scores, or null to disable class refill
     * @param categoryMask     {@link InventoryModel#FLAG_BLOCK} etc. that may be refilled
     * @param thresholdPct     refill when {@code count * 100 < maxStack * thresholdPct} (an empty slot always qualifies)
     * @param pinnedMask       pinned hotbar slots (bit per slot)
     * @param sameClassUnpinned allow class refill for unpinned slots
     * @param sameClassPinned  allow class refill for pinned slots
     * @return number of hotbar slots refilled
     */
    public int plan(InventoryModel inv, ItemClasses classes, int categoryMask, int thresholdPct, int pinnedMask,
                    boolean sameClassUnpinned, boolean sameClassPinned, ClickPlan out) {
        int refilled = 0;
        for (int h = 0; h < InventoryModel.HOTBAR; h++) {
            int key;
            int flags;
            int max;
            int cls;
            int count = inv.count[h];
            if (count > 0) {
                remember(inv, classes, h);
                key = inv.key[h];
                flags = inv.flags[h];
                max = inv.maxStack[h];
                cls = memClass[h];
            } else if (remembered[h]) {
                key = memKey[h];
                flags = memFlags[h];
                max = memMax[h];
                cls = memClass[h];
            } else {
                continue;
            }
            boolean categoryOk = (flags & categoryMask) != 0;
            boolean stackable = max > 1;
            if (count > 0) {
                if (!stackable || !categoryOk) {
                    continue;
                }
                if ((long) count * 100L >= (long) max * thresholdPct) {
                    continue;
                }
                int src = bestSource(inv, key);
                if (src < 0) {
                    continue;
                }
                int srcMenu = InventoryModel.inventoryMenuSlot(src);
                int srcCount = inv.count[src];
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
                refilled++;
                continue;
            }

            // Empty slot: identical item first, then the best item of the same class.
            int src = -1;
            if (stackable ? categoryOk : cls != ItemClasses.NONE) {
                src = bestSource(inv, key);
            }
            boolean classOk = PinnedSlots.isPinned(pinnedMask, h) ? sameClassPinned : sameClassUnpinned;
            if (src < 0 && classOk && classes != null && cls != ItemClasses.NONE
                    && classRefillAllowed(cls, categoryMask)) {
                src = bestClassSource(inv, classes, cls);
            }
            if (src < 0) {
                continue;
            }
            if (!out.hasRoom(1)) {
                break;
            }
            out.add(InventoryModel.inventoryMenuSlot(src), h, Click.SWAP);
            inv.set(h, inv.item[src], inv.key[src], inv.count[src], inv.maxStack[src], inv.flags[src]);
            inv.count[src] = 0;
            if (classes != null) {
                classes.code[h] = classes.code[src];
                classes.score[h] = classes.score[src];
                classes.code[src] = ItemClasses.NONE;
            }
            refilled++;
        }
        return refilled;
    }

    /** Food and blocks follow their category switches; every other class is always allowed. */
    private static boolean classRefillAllowed(int cls, int categoryMask) {
        if (cls == ItemClasses.FOOD) {
            return (categoryMask & InventoryModel.FLAG_FOOD) != 0;
        }
        if (cls == ItemClasses.BLOCKS) {
            return (categoryMask & InventoryModel.FLAG_BLOCK) != 0;
        }
        return true;
    }

    private void remember(InventoryModel inv, ItemClasses classes, int h) {
        memKey[h] = inv.key[h];
        memFlags[h] = inv.flags[h];
        memMax[h] = inv.maxStack[h];
        memItem[h] = inv.item[h];
        memClass[h] = classes == null ? ItemClasses.NONE : classes.code[h];
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

    /** Main-inventory slot of the given class with the highest non-negative score (larger stack on ties), or -1. */
    static int bestClassSource(InventoryModel inv, ItemClasses classes, int cls) {
        int best = -1;
        int bestScore = -1;
        for (int s = InventoryModel.HOTBAR; s < InventoryModel.SLOTS; s++) {
            if (inv.count[s] <= 0 || classes.code[s] != cls) {
                continue;
            }
            int sc = classes.score[s];
            if (sc > bestScore || (sc == bestScore && best >= 0 && inv.count[s] > inv.count[best])) {
                best = s;
                bestScore = sc;
            }
        }
        return best;
    }
}
