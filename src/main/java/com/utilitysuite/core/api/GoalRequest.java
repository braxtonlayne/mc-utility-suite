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

    record Custom(String text, CostProfile cost, DataSourceMode dataSource) implements GoalRequest {
        public String label() {
            return text;
        }
    }
}
