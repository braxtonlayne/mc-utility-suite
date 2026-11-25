package com.utilitysuite.jobs.schematic.bom;

import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.StorageEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Auto-supply between layers: what the next layer still lacks compared with the inventory, and whether a supply trip
 * to remembered storage is worth making. Pure functions over snapshots ({@link IInventoryView}, {@link IStorageIndex});
 * called once per layer change or out-of-materials stall, never per tick.
 */
public final class SupplyTrip {

    /** Why a trip is or is not made. */
    public enum Kind {
        /** The inventory already covers the layer. */
        NONE_NEEDED,
        /** Nothing missing is held by any fresh container in this dimension. */
        NOT_STORED,
        /** Missing items are stored, but only farther away than the max trip distance. */
        TOO_FAR,
        /** Fetch {@link Decision#fetch()} from storage, nearest container {@link Decision#nearestChest()}. */
        TRIP
    }

    /**
     * @param fetch        item id to count to withdraw (plan order), empty unless {@link Kind#TRIP}
     * @param nearestChest packed position of the nearest container used, {@link Long#MIN_VALUE} when none
     * @param shortfall    what the layer lacks (item id to count), for card notes
     */
    public record Decision(Kind kind, Map<String, Integer> fetch, long nearestChest, Map<String, Integer> shortfall) {
        public Decision {
            fetch = Map.copyOf(fetch);
            shortfall = Map.copyOf(shortfall);
        }

        public boolean trip() {
            return kind == Kind.TRIP;
        }
    }

    private SupplyTrip() {
    }

    /** Item id to the count {@code need} asks for beyond what {@code inventory} holds (plan order kept). */
    public static Map<String, Integer> shortfall(Map<String, Integer> need, IInventoryView inventory) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int have = inventory == null ? 0 : inventory.count(e.getKey());
            int missing = e.getValue() - have;
            if (missing > 0) {
                out.put(e.getKey(), missing);
            }
        }
        return out;
    }

    /**
     * Decides a supply trip for {@code shortfall}.
     * <ul>
     *   <li>Stale containers and other dimensions never count.</li>
     *   <li>Only containers within {@code maxDistance} blocks (straight line) of {@code from} are used.</li>
     *   <li>Pro-player rounding: each item is fetched in whole stacks (up to {@code stacksPerItem}) so the next layers
     *       do not need another trip, capped to what the reachable containers hold.</li>
     * </ul>
     *
     * @param stackSize item id to its max stack size (values below 1 count as 1)
     */
    public static Decision decide(Map<String, Integer> shortfall, IStorageIndex storage, String dimension, long from,
                                  int maxDistance, ToIntFunction<String> stackSize, int stacksPerItem) {
        if (shortfall.isEmpty()) {
            return new Decision(Kind.NONE_NEEDED, Map.of(), Long.MIN_VALUE, shortfall);
        }
        if (storage == null) {
            return new Decision(Kind.NOT_STORED, Map.of(), Long.MIN_VALUE, shortfall);
        }
        long max2 = (long) maxDistance * maxDistance;
        Map<String, Integer> fetch = new LinkedHashMap<>();
        boolean storedFar = false;
        long nearest = Long.MIN_VALUE;
        long nearestD2 = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> w : shortfall.entrySet()) {
            String id = w.getKey();
            int stack = Math.max(1, stackSize.applyAsInt(id));
            int stacks = Math.max(1, (w.getValue() + stack - 1) / stack);
            int want = Math.min(stacks, Math.max(1, stacksPerItem)) * stack; // whole stacks, capped (inventory room)
            int stored = 0;
            List<StorageEntry> holders = storage.containersWith(id, dimension, from);
            for (StorageEntry e : holders) {
                int has = e.contents().getOrDefault(id, 0);
                if (e.stale() || has <= 0 || !dimension.equals(e.dimension())) {
                    continue;
                }
                long d2 = MaterialRestock.distSq(e.packedPos(), from);
                if (d2 > max2) {
                    storedFar = true;
                    continue;
                }
                if (d2 < nearestD2) {
                    nearestD2 = d2;
                    nearest = e.packedPos();
                }
                stored += has;
                if (stored >= want) {
                    break;
                }
            }
            int take = Math.min(want, stored);
            if (take > 0) {
                fetch.put(id, take);
            }
        }
        if (fetch.isEmpty()) {
            return new Decision(storedFar ? Kind.TOO_FAR : Kind.NOT_STORED, Map.of(), Long.MIN_VALUE, shortfall);
        }
        return new Decision(Kind.TRIP, fetch, nearest, shortfall);
    }
}
