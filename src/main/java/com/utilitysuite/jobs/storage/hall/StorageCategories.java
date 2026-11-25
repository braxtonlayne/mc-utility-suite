package com.utilitysuite.jobs.storage.hall;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Item-id based categories for the storage hall sorter, plus majority inference for untagged chests. Engine free
 * (registry id strings only). The rules are coarse on purpose: a hall usually has a chest per broad kind.
 */
public final class StorageCategories {

    public static final String ORES = "ores";
    public static final String STONE = "stone";
    public static final String WOOD = "wood";
    public static final String REDSTONE = "redstone";
    public static final String FOOD = "food";
    public static final String FARMING = "farming";
    public static final String MOB_DROPS = "mob_drops";
    public static final String GEAR = "gear";
    public static final String BOOKS = "enchanted_books";
    public static final String MISC = "misc";

    /** Every category in UI order (manual tag cycles through these). */
    public static final String[] ALL = {ORES, STONE, WOOD, REDSTONE, FOOD, FARMING, MOB_DROPS, GEAR, BOOKS, MISC};

    /** Share of a chest's items that must agree before its category is inferred. */
    public static final double MAJORITY = 0.5;

    private StorageCategories() {
    }

    /** Category of a registry id such as {@code minecraft:oak_log}. */
    public static String of(String itemId) {
        if (itemId == null) {
            return MISC;
        }
        String id = itemId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        String p = colon >= 0 ? id.substring(colon + 1) : id;
        if (p.equals("enchanted_book")) {
            return BOOKS;
        }
        if (p.endsWith("_ore") || p.startsWith("raw_") || p.endsWith("_ingot") || p.endsWith("_nugget")
                || p.equals("diamond") || p.equals("emerald") || p.equals("coal") || p.equals("lapis_lazuli")
                || p.equals("quartz") || p.equals("amethyst_shard") || p.equals("netherite_scrap")
                || p.equals("ancient_debris") || (p.endsWith("_block") && isMineralBlock(p))) {
            return ORES;
        }
        if (p.contains("redstone") || p.contains("repeater") || p.contains("comparator") || p.contains("piston")
                || p.equals("observer") || p.equals("hopper") || p.equals("dropper") || p.equals("dispenser")
                || p.contains("rail") || p.equals("lever") || p.endsWith("_button") || p.endsWith("_pressure_plate")) {
            return REDSTONE;
        }
        if (p.endsWith("_sword") || p.endsWith("_pickaxe") || p.endsWith("_axe") || p.endsWith("_shovel")
                || p.endsWith("_hoe") || p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings")
                || p.endsWith("_boots") || p.equals("bow") || p.equals("crossbow") || p.equals("trident")
                || p.equals("shield") || p.equals("mace") || p.equals("elytra") || p.equals("totem_of_undying")) {
            return GEAR;
        }
        if (p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_planks") || p.endsWith("_stem")
                || p.endsWith("_hyphae") || p.equals("stick") || p.endsWith("_sapling")) {
            return WOOD;
        }
        if (p.equals("rotten_flesh") || p.equals("bone") || p.equals("bone_meal") || p.equals("string")
                || p.equals("gunpowder") || p.equals("spider_eye") || p.equals("ender_pearl") || p.equals("slime_ball")
                || p.equals("blaze_rod") || p.equals("leather") || p.equals("feather") || p.equals("arrow")
                || p.equals("ghast_tear") || p.equals("phantom_membrane")) {
            return MOB_DROPS;
        }
        if (p.equals("bread") || p.startsWith("cooked_") || p.equals("golden_carrot") || p.equals("baked_potato")
                || p.endsWith("_stew") || p.endsWith("_soup") || p.equals("cake") || p.equals("cookie")
                || p.equals("pumpkin_pie") || p.equals("apple") || p.equals("golden_apple")
                || p.equals("beef") || p.equals("porkchop") || p.equals("chicken") || p.equals("mutton")) {
            return FOOD;
        }
        if (p.endsWith("_seeds") || p.equals("wheat") || p.equals("carrot") || p.equals("potato")
                || p.equals("beetroot") || p.equals("sugar_cane") || p.equals("pumpkin") || p.equals("melon")
                || p.equals("melon_slice") || p.equals("cactus") || p.equals("bamboo") || p.equals("kelp")
                || p.endsWith("_flower") || p.endsWith("_leaves")) {
            return FARMING;
        }
        if (p.equals("stone") || p.equals("cobblestone") || p.contains("deepslate") || p.equals("dirt")
                || p.equals("gravel") || p.equals("sand") || p.equals("red_sand") || p.contains("granite")
                || p.contains("diorite") || p.contains("andesite") || p.equals("netherrack") || p.equals("tuff")
                || p.contains("blackstone") || p.equals("basalt") || p.equals("calcite") || p.contains("stone_brick")
                || p.equals("end_stone") || p.equals("obsidian") || p.equals("cobbled_deepslate")) {
            return STONE;
        }
        return MISC;
    }

    private static boolean isMineralBlock(String p) {
        return p.startsWith("iron_") || p.startsWith("gold_") || p.startsWith("diamond_") || p.startsWith("emerald_")
                || p.startsWith("copper_") || p.startsWith("coal_") || p.startsWith("lapis_")
                || p.startsWith("redstone_block") || p.startsWith("netherite_") || p.startsWith("raw_");
    }

    /**
     * The category holding at least {@link #MAJORITY} of the chest's item count, or null when the chest is empty or
     * mixed. {@code itemIds[i]} null = empty slot.
     */
    public static String infer(String[] itemIds, int[] counts) {
        Map<String, Integer> tally = new HashMap<>();
        int total = 0;
        for (int i = 0; i < itemIds.length; i++) {
            if (itemIds[i] == null || counts[i] <= 0) {
                continue;
            }
            tally.merge(of(itemIds[i]), counts[i], Integer::sum);
            total += counts[i];
        }
        if (total == 0) {
            return null;
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestN || (e.getValue() == bestN && best != null && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return bestN >= total * MAJORITY ? best : null;
    }
}
