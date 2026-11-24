package com.utilitysuite.jobs.schematic.bom;

import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.StorageEntry;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Restock policy for Build Assist: how much of each wanted item can be fetched from nearby remembered storage.
 * Pure function over {@link IStorageIndex} snapshots (stale containers are never counted), so the resulting
 * {@code GatherMany} plans to FETCH steps only and never sends the player mining or crafting mid-build.
 */
public final class MaterialRestock {

    private MaterialRestock() {
    }

    /**
     * @param wanted    item id to count, in priority order (the build's next missing palette items)
     * @param dimension dimension of the build
     * @param from      packed position the distance is measured from (the player)
     * @param radius    only containers within this many blocks count
     * @return item id to the count to fetch (capped to what nearby containers hold), same order; empty when nothing
     *         wanted is stored nearby
     */
    public static Map<String, Integer> fromNearbyStorage(Map<String, Integer> wanted, IStorageIndex storage,
                                                         String dimension, long from, int radius) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (storage == null) {
            return out;
        }
        long r2 = (long) radius * radius;
        for (Map.Entry<String, Integer> w : wanted.entrySet()) {
            int want = w.getValue();
            if (want <= 0) {
                continue;
            }
            int stored = 0;
            List<StorageEntry> holders = storage.containersWith(w.getKey(), dimension, from);
            for (StorageEntry e : holders) {
                if (e.stale() || !dimension.equals(e.dimension()) || distSq(e.packedPos(), from) > r2) {
                    continue;
                }
                stored += e.contents().getOrDefault(w.getKey(), 0);
                if (stored >= want) {
                    break;
                }
            }
            int take = Math.min(want, stored);
            if (take > 0) {
                out.put(w.getKey(), take);
            }
        }
        return out;
    }

    static long distSq(long a, long b) {
        long dx = BlockPos.getX(a) - BlockPos.getX(b);
        long dy = BlockPos.getY(a) - BlockPos.getY(b);
        long dz = BlockPos.getZ(a) - BlockPos.getZ(b);
        return dx * dx + dy * dy + dz * dz;
    }
}
