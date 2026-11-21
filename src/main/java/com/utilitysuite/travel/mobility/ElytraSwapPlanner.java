package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.api.IContainerClicker;

/**
 * Elytra Swap, engine free: the inventory-menu click plan that swaps the chest armor slot with an inventory slot, and
 * the state machine around a long fall (swap the elytra on, deploy it, glide, land, swap the chestplate back).
 * <p>
 * Slot numbers are the player inventory menu's ({@code InventoryMenu}): armor chest = {@value #CHEST_MENU_SLOT},
 * main inventory 9..35 as is, hotbar 0..8 at 36..44. A hotbar item swaps in one SWAP click (button = hotbar index); a
 * main-inventory item takes three PICKUP clicks (pick it up, put it on, put the chestplate where it was). These are
 * the clicks a player makes by hand.
 */
public final class ElytraSwapPlanner {

    public static final int CHEST_MENU_SLOT = 6;
    public static final int MAX_CLICKS = 3;

    public enum Phase { IDLE, SWAP_ON, DEPLOY, GLIDING, SWAP_BACK }

    public enum Action { NONE, CLICK_SWAP_ON, TAP_JUMP, CLICK_SWAP_BACK, DONE }

    private final int[] slots = new int[MAX_CLICKS];
    private final int[] buttons = new int[MAX_CLICKS];
    private final IContainerClicker.Click[] clicks = new IContainerClicker.Click[MAX_CLICKS];
    private int count;

    private Phase phase = Phase.IDLE;
    /** Inventory index the elytra came from (the chestplate goes there): -1 when none. */
    private int swappedFrom = -1;
    private int phaseTicks;

    /** Menu slot of an inventory index (0..8 hotbar, 9..35 main). */
    public static int menuSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    /** Fills the click plan swapping the chest slot with {@code inventoryIndex}; returns the click count. */
    public int planSwap(int inventoryIndex) {
        count = 0;
        if (inventoryIndex < 0 || inventoryIndex > 35) {
            return 0;
        }
        if (inventoryIndex < 9) {
            add(CHEST_MENU_SLOT, inventoryIndex, IContainerClicker.Click.SWAP);
        } else {
            add(inventoryIndex, 0, IContainerClicker.Click.PICKUP);
            add(CHEST_MENU_SLOT, 0, IContainerClicker.Click.PICKUP);
            add(inventoryIndex, 0, IContainerClicker.Click.PICKUP);
        }
        return count;
    }

    private void add(int slot, int button, IContainerClicker.Click click) {
        slots[count] = slot;
        buttons[count] = button;
        clicks[count] = click;
        count++;
    }

    public int count() {
        return count;
    }

    public int slot(int i) {
        return slots[i];
    }

    public int button(int i) {
        return buttons[i];
    }

    public IContainerClicker.Click click(int i) {
        return clicks[i];
    }

    /**
     * One tick of the state machine.
     *
     * @param fallTicksAhead   predicted ticks of falling left (0 when not falling)
     * @param thresholdTicks   falls at least this long trigger the swap
     * @param elytraIndex      inventory index of a usable elytra (-1: none)
     * @param wearingElytra    the chest slot holds an elytra
     * @param gliding          the player is gliding
     * @param grounded         on the ground or in water (the fall is over)
     * @param clicksPending    the clicker still has queued clicks
     */
    public Action step(int fallTicksAhead, int thresholdTicks, int elytraIndex, boolean wearingElytra,
                       boolean gliding, boolean grounded, boolean clicksPending) {
        phaseTicks++;
        switch (phase) {
            case IDLE -> {
                if (grounded || wearingElytra || elytraIndex < 0 || fallTicksAhead < thresholdTicks) {
                    return Action.NONE;
                }
                swappedFrom = elytraIndex;
                to(Phase.SWAP_ON);
                return Action.CLICK_SWAP_ON;
            }
            case SWAP_ON -> {
                if (grounded) {
                    to(Phase.SWAP_BACK);
                    return Action.CLICK_SWAP_BACK;
                }
                if (wearingElytra && !clicksPending) {
                    to(Phase.DEPLOY);
                    return Action.TAP_JUMP;
                }
                if (phaseTicks > 10) {
                    to(Phase.IDLE); // the clicks did not land: give up without a swap back
                    swappedFrom = -1;
                    return Action.DONE;
                }
                return Action.NONE;
            }
            case DEPLOY -> {
                if (gliding) {
                    to(Phase.GLIDING);
                    return Action.NONE;
                }
                if (grounded) {
                    to(Phase.SWAP_BACK);
                    return Action.CLICK_SWAP_BACK;
                }
                // retry the tap every 4 ticks (the first tap may come before the server saw the elytra)
                return phaseTicks % 4 == 0 ? Action.TAP_JUMP : Action.NONE;
            }
            case GLIDING -> {
                if (grounded || !gliding && phaseTicks > 2) {
                    if (!grounded) {
                        return Action.NONE; // stopped gliding mid-air: wait for the ground
                    }
                    to(Phase.SWAP_BACK);
                    return Action.CLICK_SWAP_BACK;
                }
                return Action.NONE;
            }
            case SWAP_BACK -> {
                if (!clicksPending && phaseTicks > 1) {
                    to(Phase.IDLE);
                    swappedFrom = -1;
                    return Action.DONE;
                }
                return Action.NONE;
            }
            default -> {
                return Action.NONE;
            }
        }
    }

    private void to(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    public Phase phase() {
        return phase;
    }

    public int swappedFrom() {
        return swappedFrom;
    }

    public void reset() {
        phase = Phase.IDLE;
        swappedFrom = -1;
        phaseTicks = 0;
        count = 0;
    }
}
