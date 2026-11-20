package com.utilitysuite.jobs.planner;

/** Registry id constants the planner refers to by name (kept as strings so planning never touches the registry). */
public final class Items {
    public static final String CRAFTING_TABLE = "minecraft:crafting_table";
    public static final String FURNACE = "minecraft:furnace";
    public static final String BLAST_FURNACE = "minecraft:blast_furnace";
    public static final String SMOKER = "minecraft:smoker";
    public static final String STONECUTTER = "minecraft:stonecutter";
    public static final String STICK = "minecraft:stick";
    public static final String COAL = "minecraft:coal";
    public static final String CHARCOAL = "minecraft:charcoal";

    private Items() {
    }

    /** Adds the {@code minecraft:} namespace when missing. */
    public static String qualify(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /** The path part of a registry id ("minecraft:iron_ore" -> "iron_ore"). */
    public static String path(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    public static boolean isStation(String id) {
        return CRAFTING_TABLE.equals(id) || FURNACE.equals(id) || BLAST_FURNACE.equals(id) || SMOKER.equals(id)
                || STONECUTTER.equals(id);
    }
}
