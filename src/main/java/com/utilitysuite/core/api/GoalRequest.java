package com.utilitysuite.core.api;

import java.util.Map;

/** A typed, immutable user goal produced by the goal console. Item/block ids are registry ids ("minecraft:diamond"). */
public sealed interface GoalRequest {

    CostProfile cost();

    DataSourceMode dataSource();

    /** Short human label, e.g. "Gather 32 minecraft:diamond". */
    String label();

    record Gather(String itemId, int count, CostProfile cost, DataSourceMode dataSource) implements GoalRequest {
        public String label() {
            return "Gather " + count + " " + itemId;
        }
    }

    record Craft(String itemId, int count, CostProfile cost, DataSourceMode dataSource) implements GoalRequest {
        public String label() {
            return "Craft " + count + " " + itemId;
        }
    }

    /**
     * @param anchor     packed {@code BlockPos} of the schematic origin, or {@link Long#MIN_VALUE} when unset
     * @param gatherOnly only gather the missing materials, do not place blocks
     */
    record Build(String schematicPath, long anchor, boolean gatherOnly, CostProfile cost, DataSourceMode dataSource)
            implements GoalRequest {
        public String label() {
            return (gatherOnly ? "Gather materials for " : "Build ") + schematicPath;
        }
    }

    /** Several items at once (e.g. a schematic's missing materials). */
    record GatherMany(Map<String, Integer> items, String reason, CostProfile cost, DataSourceMode dataSource)
            implements GoalRequest {
        public GatherMany {
            items = Map.copyOf(items);
        }

        public String label() {
            return "Gather " + items.size() + " item types (" + reason + ")";
        }
    }

    record Travel(int x, int y, int z, String dimension, CostProfile cost, DataSourceMode dataSource)
            implements GoalRequest {
        public String label() {
            return "Travel to " + x + " " + y + " " + z;
        }
    }

    /**
     * Smelt {@code count} of an input item across the remembered furnaces ("furnaces 192 raw_iron").
     *
     * @param station only this station type ("minecraft:blast_furnace"), or null for any furnace type
     */
    record SmeltArray(String itemId, int count, String station, CostProfile cost, DataSourceMode dataSource)
            implements GoalRequest {
        public String label() {
            return "Smelt " + count + " " + itemId + " in furnaces";
        }
    }

    /**
     * Brew {@code count} of a potion at the nearest remembered brewing stand ("brew 3 splash strong_healing").
     *
     * @param potion vanilla potion id without namespace ("strong_healing")
     * @param form   DRINK, SPLASH or LINGERING
     */
    record Brew(String potion, String form, int count, CostProfile cost, DataSourceMode dataSource)
            implements GoalRequest {
        public String label() {
            return "Brew " + count + " " + (form.equals("DRINK") ? "" : form.toLowerCase(java.util.Locale.ROOT) + " ")
                    + potion;
        }
    }

    record Custom(String text, CostProfile cost, DataSourceMode dataSource) implements GoalRequest {
        public String label() {
            return text;
        }
    }
}
