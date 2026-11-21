package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.IInventoryView;

/**
 * Engine-free decisions shared by the field modules: when Auto-Torch places a torch, and which cheap block Lava Plug
 * spends (a fixed list of filler blocks, so ores, valuables and building blocks are never used).
 */
public final class FieldRules {

    /** Torch items in preference order. */
    public static final String[] TORCH_ITEMS = {"minecraft:torch", "minecraft:soul_torch"};

    /** Filler blocks a player would happily throw into lava, cheapest first. Nothing else is ever used. */
    public static final String[] PLUG_ITEMS = {
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:netherrack", "minecraft:dirt",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:gravel",
            "minecraft:blackstone", "minecraft:basalt", "minecraft:deepslate", "minecraft:stone"};

    private FieldRules() {
    }

    /**
     * Auto-Torch: place a torch when block light at the feet is below the threshold, the player carries one, and they
     * have moved at least {@code minGap} blocks from the last torch (no torch spam while standing in a dark corner).
     *
     * @param blocksSinceLast blocks walked (Chebyshev) since the last placed torch; large when none placed yet
     */
    public static boolean shouldPlaceTorch(int blockLight, int threshold, int torchesCarried, int blocksSinceLast,
                                           int minGap) {
        return torchesCarried > 0 && blockLight < threshold && blocksSinceLast >= minGap;
    }

    /** First carried torch item, or null. */
    public static String torchItem(IInventoryView inv) {
        for (String t : TORCH_ITEMS) {
            if (inv.count(t) > 0) {
                return t;
            }
        }
        return null;
    }

    /** True when the item is on the filler list. */
    public static boolean isPlugItem(String itemId) {
        for (String p : PLUG_ITEMS) {
            if (p.equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    /** Cheapest carried filler block, or null when the player carries none (then the module only warns). */
    public static String plugItem(IInventoryView inv) {
        for (String p : PLUG_ITEMS) {
            if (inv.count(p) > 0) {
                return p;
            }
        }
        return null;
    }
}
