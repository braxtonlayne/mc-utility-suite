package com.utilitysuite.jobs.planner;

import java.util.List;
import java.util.Map;

/** Smelt operations per fuel item (vanilla burn time / 200 ticks). Injectable for tests and modpacks. */
public final class FuelTable {

    private final Map<String, Double> smelts;
    /** Fuels the planner may consume from inventory/storage, best first. Wood is excluded: it is building material. */
    private final List<String> consumable;

    public FuelTable(Map<String, Double> smelts, List<String> consumable) {
        this.smelts = Map.copyOf(smelts);
        this.consumable = List.copyOf(consumable);
    }

    private static final FuelTable VANILLA = new FuelTable(Map.of(
            "minecraft:coal", 8.0,
            "minecraft:charcoal", 8.0,
            "minecraft:coal_block", 80.0,
            "minecraft:blaze_rod", 12.0,
            "minecraft:lava_bucket", 100.0,
            "minecraft:oak_planks", 1.5,
            "minecraft:spruce_planks", 1.5,
            "minecraft:birch_planks", 1.5,
            "minecraft:stick", 0.5),
            List.of("minecraft:coal", "minecraft:charcoal", "minecraft:coal_block", "minecraft:blaze_rod",
                    "minecraft:lava_bucket"));

    public static FuelTable vanilla() {
        return VANILLA;
    }

    /** Smelts per item; planks and logs of any wood count 1.5. 0 when not a fuel. */
    public double smeltsPer(String itemId) {
        Double v = smelts.get(itemId);
        if (v != null) {
            return v;
        }
        if (itemId.endsWith("_planks") || itemId.endsWith("_log")) {
            return 1.5;
        }
        return 0;
    }

    public List<String> consumableFuels() {
        return consumable;
    }
}
