package com.utilitysuite.tools.inventory.loadout;

import com.utilitysuite.core.api.IContainerClicker.Click;
import com.utilitysuite.tools.inventory.ClickPlan;
import com.utilitysuite.tools.inventory.InventoryModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans the click sequence that equips a {@link Loadout}. Engine free: the caller fills a position array and gets a
 * {@link ClickPlan} for {@code IContainerClicker}. Runs once per user "Equip" (not a tick path).
 * <p>
 * Positions: 0-35 player inventory (0-8 hotbar), 36-39 armour (feet..head), 40 offhand, 41+ the open container's
 * slots. Targets are filled in order armour, offhand, hotbar; each picks the best-scoring unlocked source
 * ({@link GearItem#score}), keeping the item already in place on a tie, then preferring the player's inventory over
 * the container. Moves are simulated so later targets see where earlier swaps left things.
 * <ul>
 *   <li>hotbar target: one SWAP click on the source with the hotbar number as button;</li>
 *   <li>offhand target: one SWAP click on the source with button 40;</li>
 *   <li>armour target (inventory menu only): PICKUP source, PICKUP armour slot, and PICKUP source again when a piece
 *       came off. With a container open an armour piece found in the container is pulled into an empty inventory slot
 *       and counted as {@link Result#deferred}: equip again after closing it.</li>
 * </ul>
 * Armour that cannot be taken off (binding) is never touched.
 */
public final class LoadoutPlanner {

    public static final int INV = 36;
    public static final int ARMOR = 36;
    public static final int OFFHAND = 40;
    public static final int CONTAINER = 41;
    public static final int OFFHAND_BUTTON = 40;

    /**
     * @param swaps    target slots changed
     * @param deferred armour pieces pulled from a container that still need an equip pass after it closes
     * @param missing  names of wanted items not found anywhere
     */
    public record Result(int swaps, int deferred, List<String> missing) {
        public boolean complete() {
            return missing.isEmpty() && deferred == 0;
        }
    }

    /**
     * @param pos            current contents, length {@code 41 + containerSlots}; null = empty. Modified (simulated).
     * @param containerSlots 0 when no container is open (the player's inventory menu)
     * @param lockedArmor    bit i set = armour i cannot be removed (curse of binding)
     */
    public Result plan(Loadout loadout, GearItem[] pos, int containerSlots, int lockedArmor, ClickPlan out) {
        boolean container = containerSlots > 0;
        boolean[] locked = new boolean[pos.length];
        List<String> missing = new ArrayList<>();
        int swaps = 0;
        int deferred = 0;
        for (int t = 0; t < Loadout.TARGETS; t++) {
            GearItem want = loadout.slot(t);
            if (want == null) {
                continue;
            }
            int target = targetPos(t);
            boolean armor = t < Loadout.OFFHAND_TARGET;
            if (armor && (lockedArmor & (1 << t)) != 0) {
                locked[target] = true;
                continue;
            }
            int src = bestSource(want, pos, locked, target);
            if (src < 0) {
                missing.add(want.customName().isEmpty() ? want.itemId() : want.customName());
                continue;
            }
            if (src == target) {
                locked[target] = true;
                continue;
            }
            if (armor && container) {
                if (src >= CONTAINER) {
                    int free = freeInventorySlot(pos, locked);
                    if (free < 0 || !out.hasRoom(2)) {
                        missing.add(want.itemId());
                        continue;
                    }
                    out.add(menuSlot(src, containerSlots), 0, Click.PICKUP);
                    out.add(menuSlot(free, containerSlots), 0, Click.PICKUP);
                    pos[free] = pos[src];
                    pos[src] = null;
                    locked[free] = true;
                    deferred++;
                }
                // already in the inventory: equipped on the pass after the container closes
                else {
                    locked[src] = true;
                    deferred++;
                }
                continue;
            }
            if (armor) {
                boolean old = pos[target] != null;
                if (!out.hasRoom(old ? 3 : 2)) {
                    break;
                }
                out.add(menuSlot(src, 0), 0, Click.PICKUP);
                out.add(menuSlot(target, 0), 0, Click.PICKUP);
                if (old) {
                    out.add(menuSlot(src, 0), 0, Click.PICKUP);
                }
            } else if (t == Loadout.OFFHAND_TARGET) {
                if (!out.add(menuSlot(src, containerSlots), OFFHAND_BUTTON, Click.SWAP)) {
                    break;
                }
            } else {
                int hotbar = target;
                if (src == OFFHAND) {
                    // swap the hotbar slot with the offhand (button 40 on the hotbar slot)
                    if (!out.add(menuSlot(hotbar, containerSlots), OFFHAND_BUTTON, Click.SWAP)) {
                        break;
                    }
                } else if (!out.add(menuSlot(src, containerSlots), hotbar, Click.SWAP)) {
                    break;
                }
            }
            GearItem tmp = pos[target];
            pos[target] = pos[src];
            pos[src] = tmp;
            locked[target] = true;
            swaps++;
        }
        return new Result(swaps, deferred, missing);
    }

    /** Planner position of a loadout target. */
    public static int targetPos(int target) {
        if (target < Loadout.OFFHAND_TARGET) {
            return ARMOR + target;
        }
        return target == Loadout.OFFHAND_TARGET ? OFFHAND : target - Loadout.HOTBAR_TARGET;
    }

    /**
     * Menu slot of a planner position. Inventory menu: hotbar 36-44, main 9-35, armour 8..5, offhand 45. Container
     * menu with n slots: container 0..n-1, main n..n+26, hotbar n+27..n+35; armour/offhand absent (-1).
     */
    public static int menuSlot(int p, int containerSlots) {
        if (containerSlots <= 0) {
            if (p < INV) {
                return InventoryModel.inventoryMenuSlot(p);
            }
            if (p < OFFHAND) {
                return InventoryModel.armorMenuSlot(p - ARMOR);
            }
            return p == OFFHAND ? 45 : -1;
        }
        if (p >= CONTAINER) {
            return p - CONTAINER;
        }
        if (p < InventoryModel.HOTBAR) {
            return containerSlots + 27 + p;
        }
        return p < INV ? containerSlots + (p - InventoryModel.HOTBAR) : -1;
    }

    private static int bestSource(GearItem want, GearItem[] pos, boolean[] locked, int target) {
        int best = -1;
        int bestScore = -1;
        // the item already in place wins ties
        if (!locked[target]) {
            int s = want.score(pos[target]);
            if (s >= 0) {
                best = target;
                bestScore = s;
            }
        }
        for (int p = 0; p < pos.length; p++) {
            if (p == target || locked[p] || pos[p] == null) {
                continue;
            }
            // worn armour is never a source for another slot
            if (p >= ARMOR && p < OFFHAND) {
                continue;
            }
            int s = want.score(pos[p]);
            if (s > bestScore) {
                best = p;
                bestScore = s;
            }
        }
        return best;
    }

    private static int freeInventorySlot(GearItem[] pos, boolean[] locked) {
        for (int p = InventoryModel.HOTBAR; p < INV; p++) {
            if (pos[p] == null && !locked[p]) {
                return p;
            }
        }
        for (int p = 0; p < InventoryModel.HOTBAR; p++) {
            if (pos[p] == null && !locked[p]) {
                return p;
            }
        }
        return -1;
    }
}
