package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.api.StorageEntry;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The proactive mode's passive food objective. When the player has no food in the inventory and none in remembered
 * containers nearby (the storage index), food animals in range become targets whenever no hostile does, and their
 * drops are picked up afterwards. Once there is food again the objective switches itself off, so it never wipes out
 * a farm. Engine-free: edibility comes in as a predicate over item ids.
 */
public final class FoodObjective {

    /** How often (ticks) the need is re-evaluated; it is also re-checked right after a pickup. */
    public static final int REFRESH_TICKS = 100;
    /** Remembered containers within this many blocks count as "food nearby". */
    public static final double STORAGE_RADIUS = 32.0;
    /** Walking to a drop gives up after this many ticks. */
    public static final int COLLECT_MAX_TICKS = 100;
    /** Drops are collected within this radius of the kill. */
    public static final double COLLECT_RADIUS = 6.0;

    private FoodObjective() {
    }

    /** True when there is no food on the player and none stored nearby. */
    public static boolean needed(int inventoryFood, int storedFoodNearby) {
        return inventoryFood <= 0 && storedFoodNearby <= 0;
    }

    /**
     * Food items in remembered, non-stale containers of the dimension within {@code radius} blocks of the player.
     * Called every {@value #REFRESH_TICKS} ticks at most.
     */
    public static int storedNearby(List<StorageEntry> entries, String dimension, double px, double py, double pz,
                                   double radius, Predicate<String> isFood) {
        int total = 0;
        double r2 = radius * radius;
        for (StorageEntry e : entries) {
            if (e.stale() || !dimension.equals(e.dimension())) {
                continue;
            }
            long p = e.packedPos();
            double dx = unpackX(p) + 0.5 - px;
            double dy = unpackY(p) + 0.5 - py;
            double dz = unpackZ(p) + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > r2) {
                continue;
            }
            for (Map.Entry<String, Integer> item : e.contents().entrySet()) {
                if (item.getValue() > 0 && isFood.test(item.getKey())) {
                    total += item.getValue();
                }
            }
        }
        return total;
    }

    // BlockPos.asLong layout (26 bits x, 12 bits y, 26 bits z), kept engine-free
    static int unpackX(long packed) {
        return (int) (packed << 64 - 38 - 26 >> 64 - 26);
    }

    static int unpackY(long packed) {
        return (int) (packed << 64 - 12 >> 64 - 12);
    }

    static int unpackZ(long packed) {
        return (int) (packed << 64 - 12 - 26 >> 64 - 26);
    }

    /** {@code BlockPos.asLong} for tests. */
    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) | ((long) z & 0x3FFFFFFL) << 12;
    }
}
