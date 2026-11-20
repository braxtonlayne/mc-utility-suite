package com.utilitysuite.jobs.planner;

import java.util.Map;

/** Short human labels for plan steps ("Wooden Pick", "Iron Ore"). Falls back to a title-cased id path. */
public final class DisplayNames {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("minecraft:stick", "Sticks"),
            Map.entry("minecraft:wooden_pickaxe", "Wooden Pick"),
            Map.entry("minecraft:stone_pickaxe", "Stone Pick"),
            Map.entry("minecraft:iron_pickaxe", "Iron Pick"),
            Map.entry("minecraft:golden_pickaxe", "Golden Pick"),
            Map.entry("minecraft:diamond_pickaxe", "Diamond Pick"),
            Map.entry("minecraft:netherite_pickaxe", "Netherite Pick"),
            Map.entry("minecraft:raw_iron", "Iron Ore"),
            Map.entry("minecraft:raw_gold", "Gold Ore"),
            Map.entry("minecraft:raw_copper", "Copper Ore"),
            Map.entry("minecraft:iron_ingot", "Iron Ingots"),
            Map.entry("minecraft:gold_ingot", "Gold Ingots"),
            Map.entry("minecraft:copper_ingot", "Copper Ingots"));

    private DisplayNames() {
    }

    /** Label for a step producing the item. */
    public static String of(String itemId) {
        String n = NAMES.get(itemId);
        if (n != null) {
            return n;
        }
        String path = Items.path(itemId);
        if (path.endsWith("_log") || path.endsWith("_stem")) {
            return "Wood";
        }
        if (path.endsWith("_planks")) {
            return "Planks";
        }
        return humanize(path);
    }

    /** "iron_ingot" -> "Iron Ingot". */
    public static String humanize(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        boolean up = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == ' ') {
                sb.append(' ');
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    /** Plural display name for counted targets: "Diamonds", "Hoppers"; names already ending in s are kept. */
    public static String plural(String itemId, int count) {
        String path = Items.path(itemId);
        String base = humanize(path);
        if (count == 1 || base.endsWith("s") || isMassNoun(path)) {
            return base;
        }
        if (base.endsWith("ch") || base.endsWith("sh") || base.endsWith("x")) {
            return base + "es";
        }
        return base + "s";
    }

    private static final java.util.Set<String> MASS_NOUNS = java.util.Set.of("obsidian", "crying_obsidian", "coal",
            "charcoal", "redstone", "lapis_lazuli", "quartz", "dirt", "sand", "gravel", "clay", "netherrack",
            "deepslate", "cobbled_deepslate", "blackstone", "ancient_debris", "wheat", "sugar", "glowstone_dust",
            "gunpowder", "string", "leather", "bone_meal");

    private static boolean isMassNoun(String path) {
        return MASS_NOUNS.contains(path) || path.endsWith("stone") || path.endsWith("glass") || path.endsWith("wool")
                || path.endsWith("_planks") || path.endsWith("_wood");
    }
}
