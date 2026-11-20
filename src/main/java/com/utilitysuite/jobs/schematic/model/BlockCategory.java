package com.utilitysuite.jobs.schematic.model;

import java.util.Set;

/**
 * Coarse, id-based classification used by the build-order planner, the site survey and substitution scoping. Pure
 * string rules so it works without a registry; the Minecraft adapter is not needed for planning.
 */
public enum BlockCategory {
    /** Ordinary block: placed bottom-up, can support others. */
    SOLID,
    /** Falls without support (sand, gravel, concrete powder, anvils): placed after the solids of its layer. */
    GRAVITY,
    /** Needs a supporting block (torches, redstone, doors, rails, carpets, plants...): placed last. */
    ATTACHMENT;

    private static final Set<String> GRAVITY_IDS = Set.of("sand", "red_sand", "gravel", "anvil", "chipped_anvil",
            "damaged_anvil", "dragon_egg", "suspicious_sand", "suspicious_gravel", "pointed_dripstone");

    private static final Set<String> ATTACHMENT_IDS = Set.of("lever", "ladder", "redstone_wire", "repeater",
            "comparator", "tripwire", "tripwire_hook", "bell", "lantern", "soul_lantern", "snow", "end_rod",
            "lightning_rod", "glow_lichen", "sculk_vein", "vine", "cave_vines", "cave_vines_plant", "weeping_vines",
            "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "hanging_roots", "spore_blossom",
            "amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "small_dripleaf",
            "big_dripleaf", "big_dripleaf_stem", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower", "sunflower", "lilac",
            "rose_bush", "peony", "pink_petals", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "sugar_cane", "cactus", "bamboo", "bamboo_sapling", "lily_pad", "brown_mushroom", "red_mushroom",
            "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots", "nether_sprouts", "nether_wart",
            "sweet_berry_bush", "kelp", "kelp_plant", "seagrass", "tall_seagrass", "sea_pickle", "cocoa", "wheat",
            "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "attached_melon_stem",
            "attached_pumpkin_stem", "torchflower_crop", "pitcher_crop", "pitcher_plant", "azalea",
            "flowering_azalea", "fire", "soul_fire", "scaffolding", "turtle_egg", "frogspawn", "flower_pot",
            "chorus_flower", "chorus_plant", "mangrove_propagule", "pale_hanging_moss");

    private static final String[] ATTACHMENT_FRAGMENTS = {"torch", "_button", "_pressure_plate", "rail", "_carpet",
            "_door", "_trapdoor", "_sign", "_banner", "_sapling", "_tulip", "candle", "_coral_fan", "_wall_fan",
            "_head", "_skull", "potted_"};

    /** Blocks that are not full opaque cubes (used to decide whether a neighbor hides a block). */
    private static final String[] NON_FULL_FRAGMENTS = {"_slab", "_stairs", "glass", "leaves", "_fence", "_wall",
            "_pane", "iron_bars", "chain", "_door", "_trapdoor", "_carpet", "ice", "_bed", "chest", "barrel_",
            "lantern", "campfire", "anvil", "_leaves", "_sign", "_banner", "scaffolding", "slime_block",
            "honey_block", "cobweb", "_coral", "beacon", "conduit", "_head", "_skull", "hopper", "cauldron",
            "brewing_stand", "enchanting_table", "lectern", "grindstone", "stonecutter", "bell", "farmland",
            "dirt_path", "soul_sand", "mud", "snow", "cactus", "bamboo", "dripleaf", "amethyst_bud", "cluster",
            "candle", "cake", "flower_pot", "potted_", "end_rod", "lightning_rod", "piston_head", "daylight_detector",
            "composter", "spawner", "pointed_dripstone"};

    public static BlockCategory of(String blockIdOrState) {
        String path = BlockStateString.path(BlockStateString.blockId(blockIdOrState));
        if (GRAVITY_IDS.contains(path) || path.endsWith("_concrete_powder")) {
            return GRAVITY;
        }
        if (ATTACHMENT_IDS.contains(path)) {
            return ATTACHMENT;
        }
        for (String f : ATTACHMENT_FRAGMENTS) {
            if (path.contains(f)) {
                return ATTACHMENT;
            }
        }
        if (path.endsWith("_coral") || path.endsWith("_flower") || path.endsWith("_roots") && !path.contains("mangrove")) {
            return ATTACHMENT;
        }
        return SOLID;
    }

    /**
     * True when the block is (very likely) a full opaque cube, so a neighbor behind it is hidden from view. Liberal:
     * unknown modded blocks count as full.
     */
    public static boolean isFullOpaque(String blockIdOrState) {
        String id = BlockStateString.blockId(blockIdOrState);
        if (id.equals(SchematicVolume.AIR) || id.equals(SchematicVolume.VOID) || id.equals("minecraft:water")
                || id.equals("minecraft:lava")) {
            return false;
        }
        if (of(id) != SOLID) {
            return false;
        }
        String path = BlockStateString.path(id);
        for (String f : NON_FULL_FRAGMENTS) {
            if (path.contains(f)) {
                return false;
            }
        }
        return true;
    }
}
