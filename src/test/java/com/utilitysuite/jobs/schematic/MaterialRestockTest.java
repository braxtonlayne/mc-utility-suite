package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.jobs.schematic.bom.MaterialRestock;
import com.utilitysuite.jobs.schematic.plan.RestockCollector;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Build Assist's automatic material restock (item 4.2): what to ask for, and what nearby storage can supply. */
class MaterialRestockTest {

    private static final String OW = "minecraft:overworld";
    private static final long PLAYER = BlockPos.asLong(0, 64, 0);

    @Test
    void collectorKeepsTheFirstMissingItemsInPlanOrderWithCounts() {
        RestockCollector c = new RestockCollector(3);
        int[] seq = {7, 7, 2, 7, 9, 4, 2, 4, 9};
        for (int k : seq) {
            c.add(k);
        }
        assertEquals(3, c.size());
        assertEquals(7, c.key(0));
        assertEquals(3, c.count(0));
        assertEquals(2, c.key(1));
        assertEquals(2, c.count(1));
        assertEquals(9, c.key(2));
        assertEquals(2, c.count(2));
        assertFalse(c.add(4), "a fourth item type is left for the next round");
        c.reset();
        assertEquals(0, c.size());
        assertEquals(RestockCollector.DEFAULT_CAPACITY, new RestockCollector().capacity());
    }

    @Test
    void collectorAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        RestockCollector c = new RestockCollector();
        long bytes = AllocationProbe.measure(10_000, () -> {
            c.reset();
            for (int k = 0; k < 40; k++) {
                c.add(k % 7);
            }
        });
        assertEquals(0, bytes);
    }

    @Test
    void fetchIsCappedToWhatNearbyFreshContainersHold() {
        AcceptanceScenario5Test.FakeStorage storage = new AcceptanceScenario5Test.FakeStorage(List.of(
                chest(10, 64, 0, Map.of("minecraft:stone_bricks", 40, "minecraft:glass", 3), false),
                chest(-20, 64, 5, Map.of("minecraft:stone_bricks", 50), false),
                chest(5, 64, 5, Map.of("minecraft:oak_planks", 64), true), // stale: not trusted
                chest(500, 64, 0, Map.of("minecraft:oak_planks", 64), false), // too far
                new StorageEntry(BlockPos.asLong(1, 64, 1), "minecraft:the_nether", "chest",
                        Map.of("minecraft:glass", 64), 0L, false))); // other dimension
        Map<String, Integer> wanted = new LinkedHashMap<>();
        wanted.put("minecraft:stone_bricks", 120);
        wanted.put("minecraft:glass", 2);
        wanted.put("minecraft:oak_planks", 10);
        wanted.put("minecraft:torch", 4);

        Map<String, Integer> fetch = MaterialRestock.fromNearbyStorage(wanted, storage, OW, PLAYER, 96);

        assertEquals(Map.of("minecraft:stone_bricks", 90, "minecraft:glass", 2), fetch);
        assertEquals(List.of("minecraft:stone_bricks", "minecraft:glass"), List.copyOf(fetch.keySet()),
                "plan order is kept");
    }

    @Test
    void nothingStoredGivesAnEmptyRequest() {
        AcceptanceScenario5Test.FakeStorage storage = new AcceptanceScenario5Test.FakeStorage(List.of(
                chest(3, 64, 3, Map.of("minecraft:dirt", 64), false)));
        assertTrue(MaterialRestock.fromNearbyStorage(Map.of("minecraft:glass", 8), storage, OW, PLAYER, 96)
                .isEmpty());
        assertTrue(MaterialRestock.fromNearbyStorage(Map.of("minecraft:glass", 8), null, OW, PLAYER, 96).isEmpty(),
                "no storage index registered");
    }

    private static StorageEntry chest(int x, int y, int z, Map<String, Integer> contents, boolean stale) {
        return new StorageEntry(BlockPos.asLong(x, y, z), OW, "chest", contents, 0L, stale);
    }
}
