package com.utilitysuite.travel.autopilot;

/**
 * Mid-flight rocket restock decision, engine-free. When {@link FlightReserve} reports low hotbar rockets, the main
 * inventory (slots 9-35) may still hold more: this class says whether to swap a stack into the hotbar now, keep
 * flying while waiting for safe height or for a queued click to land, or give up and land.
 * <p>
 * Elytra durability stays a landing trigger: swapping the chest slot in the air unequips the glider for at least a
 * tick and the swap click can be dropped by the server, so the only durability aid here is {@link #spareElytraSlot},
 * which lets the driver tell the user a healthier elytra is in the inventory (to swap after landing).
 * <p>
 * Hot path: no allocation; callers pass reusable arrays.
 */
final class FlightRestock {

    enum Action { SWAP, WAIT, ABORT }

    /** Hotbar slot kinds for {@link #pickTarget}. */
    static final int EMPTY = 0;
    static final int OTHER = 1;
    static final int ROCKET = 2;

    static final int MAIN_FIRST = 9;
    static final int MAIN_END = 36;
    /** Ticks to keep flying while height or the click queue is not ready, before landing instead. */
    static final int MAX_DEFER_TICKS = 160;
    /** Ticks to let a queued swap reach the server and the inventory refresh before judging the count again. */
    static final int SETTLE_TICKS = 12;
    static final int MAX_RESTOCKS = 8;
    /** Blocks above the terrain below that count as safe to spend a few ticks on the swap. */
    static final double SAFE_HEIGHT = 20.0;

    private int deferTicks;
    private int settleTicks;
    private int restocks;

    void reset() {
        deferTicks = 0;
        settleTicks = 0;
        restocks = 0;
    }

    /** Hotbar count is healthy again: forget the current wait. */
    void clearDefer() {
        deferTicks = 0;
    }

    int restocks() {
        return restocks;
    }

    /** Call once per tick (settle countdown). */
    void tick() {
        if (settleTicks > 0) {
            settleTicks--;
        }
    }

    /**
     * Decision for a tick on which the hotbar rockets are at or below the reserve.
     *
     * @param mainRockets rockets in main inventory slots 9-35
     * @param safeHeight  player is at least {@link #SAFE_HEIGHT} above the terrain below
     * @param busy        a container screen is open or clicks are still queued
     */
    Action decide(int mainRockets, boolean safeHeight, boolean busy) {
        if (settleTicks > 0) {
            return Action.WAIT; // the last swap is still travelling
        }
        if (mainRockets <= 0 || restocks >= MAX_RESTOCKS) {
            return busy ? waitOrAbort() : Action.ABORT;
        }
        if (busy || !safeHeight) {
            return waitOrAbort();
        }
        restocks++;
        settleTicks = SETTLE_TICKS;
        return Action.SWAP;
    }

    private Action waitOrAbort() {
        return ++deferTicks > MAX_DEFER_TICKS ? Action.ABORT : Action.WAIT;
    }

    /** Main-inventory slot (9-35) with the largest rocket stack, or -1. {@code counts} has 36 entries. */
    static int pickSource(int[] counts) {
        int best = -1;
        int bestCount = 0;
        for (int i = MAIN_FIRST; i < MAIN_END; i++) {
            if (counts[i] > bestCount) {
                bestCount = counts[i];
                best = i;
            }
        }
        return best;
    }

    static int sumMain(int[] counts) {
        int n = 0;
        for (int i = MAIN_FIRST; i < MAIN_END; i++) {
            n += counts[i];
        }
        return n;
    }

    /**
     * Hotbar slot (0-8) to swap the stack into: an empty one, else one holding another item that is not the selected
     * slot. Never a rocket slot (a swap would only trade stacks). -1 when none qualifies.
     *
     * @param kinds    {@link #EMPTY}, {@link #OTHER} or {@link #ROCKET} per hotbar slot (9 entries)
     * @param selected currently selected hotbar slot
     */
    static int pickTarget(int[] kinds, int selected) {
        for (int i = 0; i < 9; i++) {
            if (kinds[i] == EMPTY) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (kinds[i] == OTHER && i != selected) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of an inventory elytra with at least {@code minGain} more uses left than the worn one, or -1.
     *
     * @param usesLeft remaining uses per inventory slot, or a value below 0 where the slot holds no usable elytra
     */
    static int spareElytraSlot(int[] usesLeft, int wornUsesLeft, int minGain) {
        int best = -1;
        int bestLeft = wornUsesLeft + minGain - 1;
        for (int i = 0; i < usesLeft.length; i++) {
            if (usesLeft[i] > bestLeft) {
                bestLeft = usesLeft[i];
                best = i;
            }
        }
        return best;
    }
}
