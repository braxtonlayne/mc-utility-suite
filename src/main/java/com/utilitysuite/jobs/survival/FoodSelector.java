package com.utilitysuite.jobs.survival;

import java.util.Set;

/** Auto-Eat decisions: when to eat and which safe hotbar food wastes the least. Allocation-free. */
public final class FoodSelector {

    /** Foods with harmful side effects that are never auto-eaten. */
    public static final Set<String> UNSAFE = Set.of("minecraft:rotten_flesh", "minecraft:spider_eye",
            "minecraft:poisonous_potato", "minecraft:pufferfish", "minecraft:suspicious_stew",
            "minecraft:chorus_fruit", "minecraft:raw_chicken", "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple");

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

    /**
     * Best hotbar slot or -1. Prefers food that does not overfill, then higher saturation.
     *
     * @param ids        item id per hotbar slot (null for empty / non-food)
     * @param nutrition  nutrition per slot
     * @param saturation saturation modifier per slot
     */
    public static int choose(String[] ids, int[] nutrition, float[] saturation, int hunger) {
        int missing = 20 - hunger;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            if (id == null || nutrition[i] <= 0 || UNSAFE.contains(id)) {
                continue;
            }
            int waste = Math.max(0, nutrition[i] - missing);
            double score = -waste * 2.0 + nutrition[i] + saturation[i] * nutrition[i];
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }
}
