package com.utilitysuite.jobs.planner;

/**
 * Bottle-filling helpers for FILL_BOTTLES (engine free): a bounded one-off scan for the nearest water source block or
 * filled water cauldron, and how many bottle uses are still needed. Not per tick: the scan runs once when the step
 * starts (and once more when a cauldron runs dry), at most {@code (2r+1)^2 * (down+up+1)} lookups.
 */
public final class WaterFill {

    private WaterFill() {
    }

    public static final int NONE = 0;
    /** A still water source block (flowing water does not fill bottles). */
    public static final int SOURCE = 1;
    /** A water cauldron with at least one level. */
    public static final int CAULDRON = 2;

    /** Block kind at a position: {@link #NONE}, {@link #SOURCE} or {@link #CAULDRON}. */
    @FunctionalInterface
    public interface Probe {
        int kind(int x, int y, int z);
    }

    /**
     * Nearest water source or water cauldron around the centre (squared distance, ties keep the first found), or
     * {@link PlanStep#NO_POS} when there is none within range.
     */
    public static long nearest(Probe probe, int cx, int cy, int cz, int radius, int down, int up) {
        long best = PlanStep.NO_POS;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -down; dy <= up; dy++) {
                    int d = dx * dx + dy * dy + dz * dz;
                    if (d >= bestD) {
                        continue;
                    }
                    if (probe.kind(cx + dx, cy + dy, cz + dz) != NONE) {
                        bestD = d;
                        best = PackedPos.pack(cx + dx, cy + dy, cz + dz);
                    }
                }
            }
        }
        return best;
    }

    /** Bottle uses still needed: the missing water bottles, capped by the empty glass bottles carried. */
    public static int usesNeeded(int wantWater, int haveWater, int emptyBottles) {
        return Math.max(0, Math.min(wantWater - haveWater, emptyBottles));
    }
}
