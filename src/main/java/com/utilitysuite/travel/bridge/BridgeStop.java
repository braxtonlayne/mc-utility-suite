package com.utilitysuite.travel.bridge;

/** Why a bridge run ends (or NONE to continue). Engine-free decision in {@link #check}. */
public enum BridgeStop {
    NONE,
    /** Every planned block is placed and the plan ended at the marked target. */
    AT_MARKER,
    /** Every planned block of a facing-direction plan is placed. */
    PLAN_DONE,
    /** The configured block budget is used up. */
    BUDGET,
    /** The hotbar holds no more than the configured reserve of placeable blocks. */
    HOTBAR_LOW;

    /**
     * @param nextIndex     index of the next plan position still to place ({@code planLength} when all are done)
     * @param placed        blocks placed in this run
     * @param budget        block budget (0 = unlimited)
     * @param hotbarBlocks  placeable blocks left in the hotbar
     * @param reserve       stop when {@code hotbarBlocks <= reserve}
     */
    public static BridgeStop check(int nextIndex, int planLength, boolean toMarker, int placed, int budget,
                                   int hotbarBlocks, int reserve) {
        if (nextIndex >= planLength) {
            return toMarker ? AT_MARKER : PLAN_DONE;
        }
        if (budget > 0 && placed >= budget) {
            return BUDGET;
        }
        if (hotbarBlocks <= reserve) {
            return HOTBAR_LOW;
        }
        return NONE;
    }
}
