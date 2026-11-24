package com.utilitysuite.tools.player;

/**
 * Picks the replacement rod for Auto-Fish when the current one reaches its minimum durability. Engine-free: the module
 * passes the uses left per hotbar slot ({@link #NO_ROD} where the slot holds no fishing rod). No allocation.
 */
public final class RodSwap {

    /** Marker for a hotbar slot without a fishing rod. */
    public static final int NO_ROD = -1;

    private RodSwap() {
    }

    /**
     * The hotbar slot (other than {@code current}) holding a rod with more than {@code minUses} uses left, preferring
     * the one with the most uses left, or -1 when none qualifies.
     */
    public static int pick(int[] usesLeft, int current, int minUses) {
        int best = -1;
        int bestUses = minUses;
        for (int i = 0; i < usesLeft.length; i++) {
            if (i != current && usesLeft[i] != NO_ROD && usesLeft[i] > bestUses) {
                best = i;
                bestUses = usesLeft[i];
            }
        }
        return best;
    }
}
