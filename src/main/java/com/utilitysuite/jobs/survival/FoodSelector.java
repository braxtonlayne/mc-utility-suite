package com.utilitysuite.jobs.survival;

import java.util.Set;

/** Auto-Eat decisions: when to eat and which safe food wastes the least. Allocation-free. */
public final class FoodSelector {

    /** Foods with harmful side effects that are never auto-eaten. */
    public static final Set<String> UNSAFE = Set.of("minecraft:rotten_flesh", "minecraft:spider_eye",
            "minecraft:poisonous_potato", "minecraft:pufferfish", "minecraft:suspicious_stew",
            "minecraft:chorus_fruit", "minecraft:raw_chicken", "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple");

    /** Hunger below this triggers a top-up before a long sprint leg (regeneration needs 18). */
    public static final int SPRINT_TOPUP_BELOW = 18;

    /** Slots 0-8 are the hotbar, 9-35 the main inventory. */
    public static final int HOTBAR = 9;

    /** Score nudge so that, between equal foods, the one already in the hotbar wins (no pull needed). */
    private static final double PULL_PENALTY = 0.01;

    private FoodSelector() {
    }

    /**
     * @param hunger    0..20
     * @param health    0..maxHealth
     * @param threshold eat when hunger is at or below this
     */
    public static boolean shouldEat(int hunger, float health, float maxHealth, int threshold, float healthThreshold) {
        if (hunger >= 20) {
            return false;
        }
        if (hunger <= threshold) {
            return true;
        }
        // regeneration needs hunger >= 18: top up when hurt
        return health < maxHealth * healthThreshold && hunger < 18;
    }

    /** As the five-argument form, also eating before a long sprint leg when hunger is below 18. */
    public static boolean shouldEat(int hunger, float health, float maxHealth, int threshold, float healthThreshold,
                                    boolean longLegAhead) {
        if (hunger >= 20) {
            return false;
        }
        return (longLegAhead && hunger < SPRINT_TOPUP_BELOW)
                || shouldEat(hunger, health, maxHealth, threshold, healthThreshold);
    }

    /** Hotbar-only choice, kept for callers that scan slots 0-8. */
    public static int choose(String[] ids, int[] nutrition, float[] saturation, int hunger) {
        return choose(ids, nutrition, saturation, hunger, false);
    }

    /**
     * Best slot or -1 over any number of slots (36 = hotbar then main inventory). Prefers food that does not
     * overfill, then higher saturation; {@code preferSaturation} (long jobs) weights saturation three times as much so
     * the food that lasts longest wins. Unsafe foods are skipped. Between equal scores a hotbar slot wins.
     *
     * @param ids        item id per slot (null for empty / non-food)
     * @param nutrition  nutrition per slot
     * @param saturation saturation per slot
     */
    public static int choose(String[] ids, int[] nutrition, float[] saturation, int hunger,
                             boolean preferSaturation) {
        int missing = 20 - hunger;
        double satWeight = preferSaturation ? 3.0 : 1.0;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            if (id == null || nutrition[i] <= 0 || UNSAFE.contains(id)) {
                continue;
            }
            int waste = Math.max(0, nutrition[i] - missing);
            int effective = nutrition[i] - waste; // what actually lands in the hunger bar
            double score = effective * (1.0 + saturation[i] * satWeight) - waste * 2.0
                    - (i >= HOTBAR ? PULL_PENALTY : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * Hotbar slot that receives food pulled from the pack: an empty slot if there is one (nothing to restore), else
     * the selected slot.
     *
     * @param occupied hotbar slots (indices 0-8) that hold an item
     */
    public static int chooseSwapTarget(boolean[] occupied, int selected) {
        for (int i = 0; i < HOTBAR; i++) {
            if (!occupied[i]) {
                return i;
            }
        }
        return selected >= 0 && selected < HOTBAR ? selected : 0;
    }

    /** Pulling is allowed only with no screen open and no block break in progress. */
    public static boolean canPull(boolean screenOpen, boolean breakingBlock) {
        return !screenOpen && !breakingBlock;
    }

    /**
     * Whether the swap-back click is needed after eating: only when the food came from the pack and the setting is on.
     */
    public static boolean needsSwapBack(boolean pulled, boolean swapBackSetting) {
        return pulled && swapBackSetting;
    }
}
