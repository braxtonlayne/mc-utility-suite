package com.utilitysuite.jobs.planner;

/**
 * Pickaxe harvest tiers, matching {@code IInventoryView.bestPickaxeTier()}: 0 none, 1 wood/gold, 2 stone, 3 iron,
 * 4 diamond, 5 netherite.
 */
public final class ToolTiers {
    private static final String[] PICKAXE_FOR_TIER = {
            null, "minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe",
            "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"
    };

    private ToolTiers() {
    }

    /** The pickaxe the planner crafts to reach a tier (wooden for 1, never golden). */
    public static String pickaxeForTier(int tier) {
        if (tier <= 0) {
            return null;
        }
        return PICKAXE_FOR_TIER[Math.min(tier, 5)];
    }

    /** Tier of a pickaxe item id, or 0 when the item is not a pickaxe. */
    public static int tierOf(String itemId) {
        return switch (itemId) {
            case "minecraft:wooden_pickaxe", "minecraft:golden_pickaxe" -> 1;
            case "minecraft:stone_pickaxe" -> 2;
            case "minecraft:iron_pickaxe" -> 3;
            case "minecraft:diamond_pickaxe" -> 4;
            case "minecraft:netherite_pickaxe" -> 5;
            default -> 0;
        };
    }

    public static boolean isPickaxe(String itemId) {
        return tierOf(itemId) > 0;
    }
}
