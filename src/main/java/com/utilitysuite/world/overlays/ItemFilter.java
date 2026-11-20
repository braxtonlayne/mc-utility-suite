package com.utilitysuite.world.overlays;

import java.util.LinkedHashSet;
import java.util.Set;

/** Ground-item highlight lists: junk is never highlighted, highlighted items are shown, others follow a default. */
public final class ItemFilter {

    public static final String DEFAULT_HIGHLIGHT = "minecraft:diamond,minecraft:totem_of_undying,minecraft:netherite_ingot,"
            + "minecraft:netherite_scrap,minecraft:ancient_debris,minecraft:enchanted_golden_apple,minecraft:elytra,"
            + "minecraft:emerald,minecraft:diamond_block,minecraft:nether_star,minecraft:enchanted_book,"
            + "minecraft:heavy_core,minecraft:trident";
    public static final String DEFAULT_JUNK = "minecraft:cobblestone,minecraft:cobbled_deepslate,minecraft:dirt,"
            + "minecraft:wheat_seeds,minecraft:netherrack,minecraft:gravel,minecraft:granite,minecraft:diorite,"
            + "minecraft:andesite,minecraft:tuff,minecraft:rotten_flesh,minecraft:stick";

    public enum Verdict { HIGHLIGHT, SHOW, HIDE }

    private final Set<String> highlight = new LinkedHashSet<>();
    private final Set<String> junk = new LinkedHashSet<>();
    private boolean showOthers;

    public ItemFilter() {
        set(DEFAULT_HIGHLIGHT, DEFAULT_JUNK, false);
    }

    public void set(String highlightCsv, String junkCsv, boolean showOthers) {
        parse(highlightCsv, highlight);
        parse(junkCsv, junk);
        this.showOthers = showOthers;
    }

    private static void parse(String csv, Set<String> out) {
        out.clear();
        if (csv == null) {
            return;
        }
        for (String s : csv.split(",")) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t.indexOf(':') < 0 ? "minecraft:" + t : t);
            }
        }
    }

    public Verdict classify(String itemId) {
        if (junk.contains(itemId)) {
            return Verdict.HIDE;
        }
        if (highlight.contains(itemId)) {
            return Verdict.HIGHLIGHT;
        }
        return showOthers ? Verdict.SHOW : Verdict.HIDE;
    }
}
