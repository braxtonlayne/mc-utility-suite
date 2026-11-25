package com.utilitysuite.jobs.schematic.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine-free copy of Minecraft 1.21.1's map colour table ({@code MapColor}, ids 1-61) with one cheap, common, solid
 * block per base colour for map art. A map pixel's colour is {@code base * shade / 255} with shade 180 (block lower
 * than its northern neighbour), 220 (level) or 255 (higher); 135 is never produced by blocks.
 */
public final class MapArtPalette {

    public static final int SHADE_LOW = 180;
    public static final int SHADE_FLAT = 220;
    public static final int SHADE_HIGH = 255;

    /** Base colours by map colour id (index 0 = none). */
    static final int[] BASE = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8,
            0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19,
            0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919,
            0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A,
            0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464,
            0xD8AF93, 0x7FA796};

    /** Block per colour id; null = not used (water needs depth, glow lichen needs a wall). */
    static final String[] BLOCK = {
            null, "minecraft:grass_block", "minecraft:birch_planks", "minecraft:mushroom_stem",
            "minecraft:redstone_block", "minecraft:packed_ice", "minecraft:iron_block",
            "minecraft:oak_leaves[persistent=true]", "minecraft:white_wool", "minecraft:clay", "minecraft:dirt",
            "minecraft:cobblestone", null, "minecraft:oak_planks", "minecraft:diorite", "minecraft:orange_wool",
            "minecraft:magenta_wool", "minecraft:light_blue_wool", "minecraft:yellow_wool", "minecraft:lime_wool",
            "minecraft:pink_wool", "minecraft:gray_wool", "minecraft:light_gray_wool", "minecraft:cyan_wool",
            "minecraft:purple_wool", "minecraft:blue_wool", "minecraft:brown_wool", "minecraft:green_wool",
            "minecraft:red_wool", "minecraft:black_wool", "minecraft:gold_block", "minecraft:diamond_block",
            "minecraft:lapis_block", "minecraft:emerald_block", "minecraft:spruce_planks", "minecraft:netherrack",
            "minecraft:white_terracotta", "minecraft:orange_terracotta", "minecraft:magenta_terracotta",
            "minecraft:light_blue_terracotta", "minecraft:yellow_terracotta", "minecraft:lime_terracotta",
            "minecraft:pink_terracotta", "minecraft:gray_terracotta", "minecraft:light_gray_terracotta",
            "minecraft:cyan_terracotta", "minecraft:purple_terracotta", "minecraft:blue_terracotta",
            "minecraft:brown_terracotta", "minecraft:green_terracotta", "minecraft:red_terracotta",
            "minecraft:black_terracotta", "minecraft:crimson_nylium", "minecraft:crimson_planks",
            "minecraft:crimson_hyphae", "minecraft:warped_nylium", "minecraft:warped_planks",
            "minecraft:warped_hyphae", "minecraft:warped_wart_block", "minecraft:cobbled_deepslate",
            "minecraft:raw_iron_block", null};

    /** Colour ids whose block is expensive (metal / gem blocks), skipped in cheap mode. */
    static final int[] EXPENSIVE = {6, 30, 31, 33, 60};

    /** One usable (colour id, shade) pair. */
    public record Entry(int colorId, int shade, int rgb, String block) {
    }

    private final Entry[] entries;

    private MapArtPalette(Entry[] entries) {
        this.entries = entries;
    }

    /**
     * @param staircase true: shades 180/220/255 (heights vary); false: 220 only (flat)
     * @param cheapOnly skip metal and gem blocks
     */
    public static MapArtPalette of(boolean staircase, boolean cheapOnly) {
        List<Entry> out = new ArrayList<>();
        int[] shades = staircase ? new int[]{SHADE_LOW, SHADE_FLAT, SHADE_HIGH} : new int[]{SHADE_FLAT};
        for (int id = 1; id < BASE.length; id++) {
            if (BLOCK[id] == null || (cheapOnly && isExpensive(id))) {
                continue;
            }
            for (int s : shades) {
                out.add(new Entry(id, s, shade(BASE[id], s), BLOCK[id]));
            }
        }
        return new MapArtPalette(out.toArray(new Entry[0]));
    }

    static boolean isExpensive(int id) {
        for (int e : EXPENSIVE) {
            if (e == id) {
                return true;
            }
        }
        return false;
    }

    /** Map colour {@code base * shade / 255} per channel, as Minecraft computes it. */
    public static int shade(int rgb, int shade) {
        int r = ((rgb >> 16) & 0xFF) * shade / 255;
        int g = ((rgb >> 8) & 0xFF) * shade / 255;
        int b = (rgb & 0xFF) * shade / 255;
        return (r << 16) | (g << 8) | b;
    }

    public int size() {
        return entries.length;
    }

    public Entry entry(int i) {
        return entries[i];
    }

    /** Index of the entry nearest to the colour (squared, perceptually weighted RGB distance). */
    public int nearest(double r, double g, double b) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < entries.length; i++) {
            int c = entries[i].rgb();
            double dr = r - ((c >> 16) & 0xFF), dg = g - ((c >> 8) & 0xFF), db = b - (c & 0xFF);
            double d = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Block for a base colour id, or null. */
    public static String block(int colorId) {
        return colorId > 0 && colorId < BLOCK.length ? BLOCK[colorId] : null;
    }

    public static int base(int colorId) {
        return BASE[colorId];
    }
}
