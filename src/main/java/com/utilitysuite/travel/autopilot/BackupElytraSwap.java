package com.utilitysuite.travel.autopilot;

/**
 * Mid-flight backup elytra swap, engine-free. Shortly before the worn elytra reaches the durability reserve, a spare
 * elytra from the inventory with more than {@link #MIN_SPARE_FRACTION} of its durability left (Mending ones first) is
 * swapped into the chest slot with ordinary inventory clicks. The click plan never leaves the chest slot empty (one SWAP
 * click from the hotbar, or pick up / put on / put back from the main inventory), so the glide is not interrupted.
 * A settle window lets the server confirm the swap; after {@link #MAX_FAILURES} swaps that changed nothing it gives up
 * and the reserve landing behaves as before. With no qualifying spare the reserve behaviour is unchanged. No allocation.
 */
final class BackupElytraSwap {

    /** A spare needs more than this fraction of its durability left. */
    static final double MIN_SPARE_FRACTION = 0.25;
    /** Swap this many uses (seconds of flight) before the reserve is reached. */
    static final int SWAP_MARGIN = 15;
    static final int SETTLE_TICKS = 12;
    static final int MAX_FAILURES = 2;

    enum Action { NONE, WAIT, SWAP }

    private int settleTicks;
    private int wornBefore;
    private int failures;
    private boolean swapping;

    void reset() {
        settleTicks = 0;
        wornBefore = 0;
        failures = 0;
        swapping = false;
    }

    /** True while a swap is queued or settling (the reserve landing waits for it). */
    boolean swapping() {
        return swapping;
    }

    boolean gaveUp() {
        return failures >= MAX_FAILURES;
    }

    /** Swap now: the worn elytra is within {@link #SWAP_MARGIN} uses of the reserve. */
    static boolean due(int wornUsesLeft, int minDurability) {
        return wornUsesLeft <= minDurability + SWAP_MARGIN;
    }

    /**
     * Inventory index of the spare to swap in, or -1: Mending elytras first, then the most uses left. A spare needs
     * more than {@link #MIN_SPARE_FRACTION} of its durability and more uses than the worn one.
     *
     * @param usesLeft uses left per inventory slot, below 0 where the slot holds no usable elytra
     * @param maxUses  maximum uses per slot (only read where {@code usesLeft >= 0})
     * @param mending  the slot's elytra has Mending
     */
    static int pickSpare(int[] usesLeft, int[] maxUses, boolean[] mending, int wornUsesLeft) {
        int best = -1;
        boolean bestMending = false;
        int bestLeft = -1;
        for (int i = 0; i < usesLeft.length; i++) {
            int left = usesLeft[i];
            if (left < 0 || left <= wornUsesLeft || left <= maxUses[i] * MIN_SPARE_FRACTION) {
                continue;
            }
            boolean m = mending[i];
            if (best < 0 || (m && !bestMending) || (m == bestMending && left > bestLeft)) {
                best = i;
                bestMending = m;
                bestLeft = left;
            }
        }
        return best;
    }

    /**
     * One cruise tick.
     *
     * @param wornUsesLeft  uses left on the worn elytra
     * @param minDurability the reserve setting
     * @param spare         {@link #pickSpare} result
     * @param safeHeight    high enough above the terrain to spend a few ticks on clicks
     * @param busy          a screen is open or clicks are still queued
     */
    Action step(int wornUsesLeft, int minDurability, int spare, boolean safeHeight, boolean busy) {
        if (swapping) {
            if (settleTicks > 0) {
                settleTicks--;
                return Action.WAIT;
            }
            if (busy) {
                return Action.WAIT;
            }
            swapping = false;
            if (wornUsesLeft > wornBefore) {
                failures = 0; // the new elytra is on
            } else {
                failures++;
            }
        }
        if (gaveUp() || !due(wornUsesLeft, minDurability) || spare < 0) {
            return Action.NONE;
        }
        if (busy || !safeHeight) {
            return Action.WAIT;
        }
        swapping = true;
        settleTicks = SETTLE_TICKS;
        wornBefore = wornUsesLeft;
        return Action.SWAP;
    }

    /** The clicks could not be queued: count it as a failed swap. */
    void swapRejected() {
        swapping = false;
        settleTicks = 0;
        failures++;
    }
}
