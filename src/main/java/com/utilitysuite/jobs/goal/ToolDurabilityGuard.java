package com.utilitysuite.jobs.goal;

/**
 * Decides, from a caller-filled snapshot of the 36 inventory slots, whether the selected pickaxe or shovel is about to
 * break and which backup of the same kind should replace it. Engine-agnostic and allocation-free: the Minecraft
 * adapter refills the arrays (every {@link #CHECK_INTERVAL} ticks, not every tick) and calls {@link #decide}.
 */
public final class ToolDurabilityGuard {

    public static final int NONE = 0;
    public static final int PICKAXE = 1;
    public static final int SHOVEL = 2;

    /** Ticks between checks while a mining step runs. */
    public static final int CHECK_INTERVAL = 5;
    public static final int DEFAULT_MIN_DURABILITY = 5;
    /** Remaining durability of an undamageable item. */
    public static final int UNBREAKABLE = Integer.MAX_VALUE;

    public enum Decision {
        /** Nothing to do. */
        OK,
        /** Move {@link #swapSlot} into the selected hotbar slot (or select it when it is on the hotbar). */
        SWAP,
        /** Tool nearly broken and no usable backup. */
        NO_BACKUP,
        /** Same, and the tool is enchanted: never break it, keep the job paused. */
        NO_BACKUP_ENCHANTED
    }

    public final int[] kind = new int[36];
    /** Remaining uses (max damage - damage). */
    public final int[] remaining = new int[36];
    /** Pickaxe tier 0..5 ({@link com.utilitysuite.jobs.planner.ToolTiers}); 0 for shovels. */
    public final int[] tier = new int[36];
    public final boolean[] enchanted = new boolean[36];
    /** Selected hotbar slot 0..8. */
    public int selected;
    /** Inventory slot of the chosen backup after a {@link Decision#SWAP}. */
    public int swapSlot = -1;

    /**
     * @param minRemaining     swap when the held tool has this many uses left or fewer
     * @param protectEnchanted never let an enchanted tool break (swap or hold); false treats it like any tool and
     *                         lets it run down
     * @param requiredTier     pickaxe tier the step needs (0 for none); a backup pickaxe must reach it
     */
    public Decision decide(int minRemaining, boolean protectEnchanted, int requiredTier) {
        swapSlot = -1;
        int held = selected;
        if (held < 0 || held > 8) {
            return Decision.OK;
        }
        int k = kind[held];
        if (k == NONE || remaining[held] > minRemaining || (enchanted[held] && !protectEnchanted)) {
            return Decision.OK;
        }
        int best = -1;
        for (int i = 0; i < 36; i++) {
            if (i == held || kind[i] != k || remaining[i] <= minRemaining) {
                continue;
            }
            if (k == PICKAXE && tier[i] < requiredTier) {
                continue;
            }
            if (best < 0 || better(i, best)) {
                best = i;
            }
        }
        if (best >= 0) {
            swapSlot = best;
            return Decision.SWAP;
        }
        return enchanted[held] ? Decision.NO_BACKUP_ENCHANTED : Decision.NO_BACKUP;
    }

    /** Plain tools first (spare the enchanted one), then the most durability left. */
    private boolean better(int a, int b) {
        if (enchanted[a] != enchanted[b]) {
            return !enchanted[a];
        }
        return remaining[a] > remaining[b];
    }

    public void clear() {
        java.util.Arrays.fill(kind, NONE);
        java.util.Arrays.fill(remaining, UNBREAKABLE);
        java.util.Arrays.fill(tier, 0);
        java.util.Arrays.fill(enchanted, false);
    }
}
