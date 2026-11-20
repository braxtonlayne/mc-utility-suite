package com.utilitysuite.jobs.planner;

import java.util.List;

/**
 * Wood families: every log type crafts its own planks, and planks of any wood satisfy the vanilla {@code planks} tag
 * (crafting table, sticks, wooden tools). Lets the planner gather "any log" and the executor craft the planks that
 * match the logs actually on hand (a jungle or birch spawn instead of oak).
 */
public final class WoodTypes {

    /** Vanilla logs and nether stems, the blocks a "gather wood" step may fell. */
    public static final List<String> LOGS = List.of("minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
            "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log",
            "minecraft:cherry_log", "minecraft:crimson_stem", "minecraft:warped_stem");

    private WoodTypes() {
    }

    public static boolean isLog(String itemId) {
        return LOGS.contains(itemId);
    }

    /** "minecraft:birch_log" -> "minecraft:birch_planks"; null when not a known log. */
    public static String planksFor(String logId) {
        if (logId.endsWith("_log")) {
            return logId.substring(0, logId.length() - 4) + "_planks";
        }
        if (logId.endsWith("_stem")) {
            return logId.substring(0, logId.length() - 5) + "_planks";
        }
        return null;
    }

    /** "minecraft:birch_planks" -> "minecraft:birch_log" (stems for crimson/warped); null when not planks. */
    public static String logFor(String planksId) {
        if (!planksId.endsWith("_planks")) {
            return null;
        }
        String base = planksId.substring(0, planksId.length() - 7);
        String log = base.endsWith("crimson") || base.endsWith("warped") ? base + "_stem" : base + "_log";
        return LOGS.contains(log) ? log : null;
    }
}
