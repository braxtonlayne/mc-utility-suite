package com.utilitysuite.world.overlays;

import java.util.Set;

/**
 * Category bits for the block-visibility filter, derived from registry ids (pure, testable). The overlay scanner
 * caches the result per block so ids are only inspected once per block type.
 */
public final class BlockCategories {

    public static final int NONE = 0;
    public static final int ORE_COMMON = 1;       // coal, copper, iron, redstone, lapis, quartz, nether gold
    public static final int ORE_PRECIOUS = 1 << 1; // gold, emerald
    public static final int ORE_RARE = 1 << 2;    // diamond, ancient debris
    public static final int STORAGE = 1 << 3;     // chests, barrels, shulkers, hoppers, ender chests, ...
    public static final int SPAWNER = 1 << 4;     // spawner, trial spawner, vault
    public static final int LAVA = 1 << 5;
    public static final int WATER = 1 << 6;
    public static final int CUSTOM = 1 << 7;
    public static final int ORES = ORE_COMMON | ORE_PRECIOUS | ORE_RARE;
    public static final int FLUIDS = LAVA | WATER;

    private BlockCategories() {
    }

    /** Categories of a block id; {@code customIds} are matched exactly (also "#tag" entries are handled elsewhere). */
    public static int classify(String id, Set<String> customIds) {
        int bits = customIds.contains(id) ? CUSTOM : NONE;
        String path = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
        if (path.equals("ancient_debris") || path.endsWith("diamond_ore")) {
            bits |= ORE_RARE;
        } else if (path.endsWith("emerald_ore") || path.equals("gold_ore") || path.equals("deepslate_gold_ore")) {
            bits |= ORE_PRECIOUS;
        } else if (path.endsWith("_ore")) {
            bits |= ORE_COMMON;
        }
        if (path.equals("chest") || path.equals("trapped_chest") || path.equals("ender_chest")
                || path.equals("barrel") || path.equals("hopper") || path.endsWith("shulker_box")
                || path.equals("dispenser") || path.equals("dropper") || path.equals("crafter")
                || path.equals("decorated_pot") || path.endsWith("chiseled_bookshelf")) {
            bits |= STORAGE;
        }
        if (path.equals("spawner") || path.equals("trial_spawner") || path.equals("vault")) {
            bits |= SPAWNER;
        }
        if (path.equals("lava")) {
            bits |= LAVA;
        } else if (path.equals("water")) {
            bits |= WATER;
        }
        return bits;
    }

    /** ARGB outline colour for a category set (highest priority category wins). */
    public static int color(int bits, String path) {
        if ((bits & ORE_RARE) != 0) {
            return path != null && path.contains("debris") ? 0xFFB0703A : 0xFF4FE8F0;
        }
        if ((bits & ORE_PRECIOUS) != 0) {
            return path != null && path.contains("emerald") ? 0xFF3CE070 : 0xFFF5D040;
        }
        if ((bits & SPAWNER) != 0) {
            return 0xFFE040E0;
        }
        if ((bits & STORAGE) != 0) {
            return 0xFFC08040;
        }
        if ((bits & LAVA) != 0) {
            return 0xFFFF6010;
        }
        if ((bits & WATER) != 0) {
            return 0xFF3070FF;
        }
        if ((bits & ORE_COMMON) != 0) {
            return 0xFFB0B0B0;
        }
        return 0xFFFFFFFF;
    }
}
