package com.utilitysuite.jobs.planner;

import java.util.Set;

/**
 * Pro-player tool habit: before digging a lot of shovel material or felling a lot of logs, get the matching tool (one
 * on hand, one from storage, else craft it: stone when cobblestone is at hand or a pickaxe can get it, wooden
 * otherwise). A shovel digs dirt roughly 2-4x faster than a hand, an axe fells logs about 2x faster; past the
 * thresholds the craft pays for itself. Pickaxes stay with the planner's tier logic.
 */
public final class EfficiencyTools {

    public enum Kind { SHOVEL, AXE }

    /**
     * @param enabled      get efficiency tools at all
     * @param shovelBlocks shovel material in one plan that makes a shovel worth it
     * @param axeBlocks    logs in one plan that make an axe worth it
     */
    public record Settings(boolean enabled, int shovelBlocks, int axeBlocks) {
        public static final Settings DEFAULT = new Settings(true, 24, 12);
        public static final Settings OFF = new Settings(false, Integer.MAX_VALUE, Integer.MAX_VALUE);

        int threshold(Kind k) {
            return k == Kind.SHOVEL ? shovelBlocks : axeBlocks;
        }
    }

    private static final Set<String> SHOVEL_BLOCKS = Set.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:podzol", "minecraft:mycelium", "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
            "minecraft:clay", "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:snow_block", "minecraft:mud",
            "minecraft:farmland", "minecraft:dirt_path", "minecraft:muddy_mangrove_roots");

    /** Tool ids best first (what to look for on hand / in storage). */
    private static final String[] SHOVELS = {"minecraft:netherite_shovel", "minecraft:diamond_shovel",
            "minecraft:iron_shovel", "minecraft:stone_shovel", "minecraft:golden_shovel", "minecraft:wooden_shovel"};
    private static final String[] AXES = {"minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe",
            "minecraft:stone_axe", "minecraft:golden_axe", "minecraft:wooden_axe"};

    private EfficiencyTools() {
    }

    /** The tool kind that speeds up breaking this block, or null (pickaxe blocks, anything else). */
    public static Kind kindOf(String blockId) {
        if (blockId == null) {
            return null;
        }
        if (SHOVEL_BLOCKS.contains(blockId)) {
            return Kind.SHOVEL;
        }
        if (blockId.endsWith("_log") || blockId.endsWith("_wood") || blockId.endsWith("_stem")
                || blockId.endsWith("_hyphae")) {
            return Kind.AXE;
        }
        return null;
    }

    /** Candidate tool ids of a kind, best first. */
    public static String[] tools(Kind k) {
        return k == Kind.SHOVEL ? SHOVELS : AXES;
    }

    public static boolean isTool(String itemId, Kind k) {
        if (itemId == null) {
            return false;
        }
        return k == Kind.SHOVEL ? itemId.endsWith("_shovel")
                : itemId.endsWith("_axe") && !itemId.endsWith("_pickaxe");
    }

    /** The tool the planner crafts: stone when it can, else wooden. */
    public static String craftable(Kind k, boolean stone) {
        if (k == Kind.SHOVEL) {
            return stone ? "minecraft:stone_shovel" : "minecraft:wooden_shovel";
        }
        return stone ? "minecraft:stone_axe" : "minecraft:wooden_axe";
    }
}
