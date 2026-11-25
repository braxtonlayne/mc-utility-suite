package com.utilitysuite.jobs.storage.hall;

import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.storage.ChestSlots;
import com.utilitysuite.jobs.storage.StorageMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallStorageTest {

    private static final String OW = "minecraft:overworld";
    private static final long A = PackedPos.pack(0, 64, 0);
    private static final long B = PackedPos.pack(2, 64, 0);
    private static final long FAR = PackedPos.pack(500, 64, 0);

    private static StorageMemory memory() {
        return new StorageMemory(() -> 1000L, 3_600_000L);
    }

    @Test
    void slotsAndTagsRoundTripThroughJson() {
        StorageMemory m = memory();
        ChestSlots s = new ChestSlots(27).put(0, "minecraft:oak_log", 64, 64, "")
                .put(5, "minecraft:enchanted_book", 1, 1, ChestSlots.enchantKey(Map.of("minecraft:sharpness", 5)));
        m.record(OW, A, "minecraft:chest", Map.of("minecraft:oak_log", 64, "minecraft:enchanted_book", 1), List.of(), s);
        assertEquals(StorageCategories.ORES, m.cycleTag(OW, A));
        String json = m.toJson();

        StorageMemory back = memory();
        back.loadJson(json);
        ChestSlots r = back.slots(OW, A);
        assertEquals(27, r.size());
        assertEquals("minecraft:oak_log", r.item(0));
        assertEquals(64, r.count(0));
        assertEquals("minecraft:sharpness=5", r.variant(5));
        assertNull(r.item(1));
        assertEquals(StorageCategories.ORES, back.tag(OW, A));
    }

    @Test
    void oldSavesWithoutSlotsStillLoad() {
        StorageMemory m = memory();
        m.loadJson("{\"v\":1,\"entries\":[{\"p\":" + A + ",\"d\":\"" + OW
                + "\",\"t\":\"minecraft:chest\",\"ts\":5,\"c\":{\"minecraft:dirt\":3}}]}");
        assertEquals(3, m.count("minecraft:dirt", true));
        assertNull(m.slots(OW, A));
        assertEquals("", m.tag(OW, A));
    }

    @Test
    void tagCyclesThroughEveryCategoryBackToNone() {
        StorageMemory m = memory();
        for (String c : StorageCategories.ALL) {
            assertEquals(c, m.cycleTag(OW, A));
        }
        assertEquals("", m.cycleTag(OW, A));
        assertTrue(m.consumeDirty());
    }

    @Test
    void hallChestsKeepsNearbyCapturedChestsNearestFirst() {
        StorageMemory m = memory();
        m.record(OW, B, "minecraft:chest", Map.of(), List.of(), new ChestSlots(27));
        m.record(OW, A, "minecraft:chest", Map.of(), List.of(), new ChestSlots(27).put(0, "minecraft:dirt", 5, 64, ""));
        m.record(OW, FAR, "minecraft:chest", Map.of(), List.of(), new ChestSlots(27));
        m.record(OW, PackedPos.pack(1, 64, 1), "minecraft:chest", Map.of("minecraft:dirt", 1)); // no slots
        List<HallSortPlanner.Chest> c = m.hallChests(OW, A, 16);
        assertEquals(2, c.size());
        assertEquals(A, c.get(0).pos);
        assertEquals(5, c.get(0).count[0]);
    }

    @Test
    void nearlyFullFindsCapturedChestsAboveTheShare() {
        StorageMemory m = memory();
        ChestSlots full = new ChestSlots(4);
        for (int i = 0; i < 4; i++) {
            full.put(i, "minecraft:dirt", 64, 64, "");
        }
        m.record(OW, A, "minecraft:chest", Map.of(), List.of(), full);
        m.record(OW, B, "minecraft:chest", Map.of(), List.of(), new ChestSlots(4).put(0, "minecraft:dirt", 1, 64, ""));
        m.record(OW, FAR, "minecraft:chest", Map.of(), List.of(), full);
        long[] out = new long[4];
        assertEquals(1, m.nearlyFull(OW, A, 16, 0.9, out));
        assertEquals(A, out[0]);
        assertEquals(2, m.nearlyFull(OW, A, 16, 0.25, out));
    }

    @Test
    void tripsGroupBySourceAndSkipInChestMerges() {
        HallSortPlanner.Chest wood = new HallSortPlanner.Chest(A, StorageCategories.WOOD, 27)
                .put(0, "minecraft:oak_log", "", 64, 64)
                .put(1, "minecraft:cobblestone", "", 20, 64)
                .put(2, "minecraft:raw_iron", "", 10, 64)
                .put(3, "minecraft:oak_log", "", 10, 64)
                .put(4, "minecraft:oak_log", "", 10, 64);
        HallSortPlanner.Chest stone = new HallSortPlanner.Chest(B, StorageCategories.STONE, 27);
        HallSortPlanner.Chest ores = new HallSortPlanner.Chest(FAR, StorageCategories.ORES, 27);
        List<HallSortPlanner.Chest> chests = List.of(wood, stone, ores);
        HallSortPlanner.Plan plan = new HallSortPlanner().plan(chests, 0.9);
        HallSortTrips.Result r = HallSortTrips.build(chests, plan);
        assertEquals(1, r.trips().size());
        HallSortTrips.Trip t = r.trips().get(0);
        assertEquals(A, t.source());
        assertEquals(Map.of("minecraft:cobblestone", 20, "minecraft:raw_iron", 10), t.take());
        assertEquals(Map.of("minecraft:cobblestone", 20), t.deposits().get(B));
        assertEquals(Map.of("minecraft:raw_iron", 10), t.deposits().get(FAR));
        assertTrue(r.skipped() >= 1, "the oak log merge inside the chest is not a trip");
    }

    @Test
    void mixedBookChestMovesAreSkipped() {
        HallSortPlanner.Chest books1 = new HallSortPlanner.Chest(A, StorageCategories.BOOKS, 27)
                .put(0, "minecraft:enchanted_book", "minecraft:mending=1", 1, 1)
                .put(1, "minecraft:enchanted_book", "minecraft:sharpness=5", 1, 1);
        HallSortPlanner.Chest books2 = new HallSortPlanner.Chest(B, StorageCategories.BOOKS, 27)
                .put(0, "minecraft:enchanted_book", "minecraft:mending=1", 1, 1)
                .put(1, "minecraft:enchanted_book", "minecraft:mending=1", 1, 1);
        List<HallSortPlanner.Chest> chests = List.of(books1, books2);
        HallSortPlanner.Plan plan = new HallSortPlanner().plan(chests, 0.9);
        HallSortTrips.Result r = HallSortTrips.build(chests, plan);
        assertTrue(r.trips().isEmpty());
        assertTrue(r.skipped() >= 1);
    }
}
