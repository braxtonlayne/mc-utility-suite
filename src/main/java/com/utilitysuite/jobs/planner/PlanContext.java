package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.StorageEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable inputs for one planning run, captured on the client thread so planning can run on a worker.
 *
 * @param inventory          item id -> count the player carries
 * @param pickaxeTier        best pickaxe tier carried
 * @param storage            known containers (same dimension, not stale, not excluded, within range), nearest first
 * @param credit             expected surplus of jobs queued ahead of this one (shared errands)
 * @param nearbyStations     station items already placed near the player ("minecraft:crafting_table")
 * @param includeReturn      append a Return/Deposit step after harvesting
 */
public record PlanContext(Map<String, Integer> inventory, int pickaxeTier, List<StorageEntry> storage,
                          String dimension, long origin, CostProfile cost, Map<String, Integer> credit,
                          Set<String> nearbyStations, boolean includeReturn) {

    public static final int DEFAULT_STORAGE_RANGE = 256;

    public PlanContext {
        inventory = Map.copyOf(inventory);
        storage = List.copyOf(storage);
        credit = Map.copyOf(credit);
        nearbyStations = Set.copyOf(nearbyStations);
        cost = cost == null ? CostProfile.FASTEST : cost;
    }

    /**
     * Captures the inputs from live services.
     *
     * @param storageIndex       may be null (no storage module)
     * @param excludedContainers packed positions to ignore (a container found empty during replanning)
     */
    public static PlanContext capture(IInventoryView inv, IStorageIndex storageIndex, String dimension, long origin,
                                      CostProfile cost, Map<String, Integer> credit, Set<String> nearbyStations,
                                      Set<Long> excludedContainers, boolean includeReturn) {
        List<StorageEntry> eligible = new ArrayList<>();
        if (storageIndex != null) {
            long maxSq = (long) DEFAULT_STORAGE_RANGE * DEFAULT_STORAGE_RANGE;
            for (StorageEntry e : storageIndex.entries()) {
                if (e.stale() || !e.dimension().equals(dimension) || excludedContainers.contains(e.packedPos())
                        || PackedPos.distSq(e.packedPos(), origin) > maxSq) {
                    continue;
                }
                eligible.add(e);
            }
            eligible.sort((a, b) -> Long.compare(PackedPos.distSq(a.packedPos(), origin),
                    PackedPos.distSq(b.packedPos(), origin)));
        }
        return new PlanContext(inv.counts(), inv.bestPickaxeTier(), eligible, dimension, origin, cost, credit,
                nearbyStations, includeReturn);
    }

    /** Context with only an inventory (tests, quick previews). */
    public static PlanContext ofInventory(Map<String, Integer> inventory, int pickaxeTier) {
        return new PlanContext(inventory, pickaxeTier, List.of(), "minecraft:overworld", 0L, CostProfile.FASTEST,
                Map.of(), Set.of(), true);
    }

    public PlanContext withStorage(List<StorageEntry> entries) {
        return new PlanContext(inventory, pickaxeTier, entries, dimension, origin, cost, credit, nearbyStations,
                includeReturn);
    }

    public PlanContext withCost(CostProfile c) {
        return new PlanContext(inventory, pickaxeTier, storage, dimension, origin, c, credit, nearbyStations,
                includeReturn);
    }

    public PlanContext withCredit(Map<String, Integer> c) {
        return new PlanContext(inventory, pickaxeTier, storage, dimension, origin, cost, c, nearbyStations,
                includeReturn);
    }

    public PlanContext withReturn(boolean r) {
        return new PlanContext(inventory, pickaxeTier, storage, dimension, origin, cost, credit, nearbyStations, r);
    }

    public PlanContext withStations(Set<String> s) {
        return new PlanContext(inventory, pickaxeTier, storage, dimension, origin, cost, credit, s, includeReturn);
    }
}
