package com.utilitysuite.jobs.storage;

import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.jobs.planner.PackedPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageMemoryTest {

    private static final String OW = "minecraft:overworld";
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final StorageMemory mem = new StorageMemory(now::get, 60_000);

    @Test
    void staleByAgeAndByMark() {
        long a = PackedPos.pack(0, 64, 0);
        long b = PackedPos.pack(5, 64, 0);
        mem.record(OW, a, "minecraft:chest", Map.of("minecraft:diamond", 3));
        mem.record(OW, b, "minecraft:chest", Map.of("minecraft:diamond", 2));
        assertEquals(5, mem.count("minecraft:diamond", false));

        mem.markStale(OW, b);
        assertEquals(3, mem.count("minecraft:diamond", false));
        assertEquals(5, mem.count("minecraft:diamond", true));

        now.addAndGet(61_000);
        mem.refreshAges();
        assertEquals(0, mem.count("minecraft:diamond", false));
        assertTrue(mem.entries().stream().allMatch(StorageEntry::stale));

        mem.record(OW, a, "minecraft:chest", Map.of("minecraft:diamond", 1));
        assertEquals(1, mem.count("minecraft:diamond", false), "re-recording clears staleness");
    }

    @Test
    void containersWithSortedNearestAndDimensionScoped() {
        long far = PackedPos.pack(100, 64, 0);
        long near = PackedPos.pack(3, 64, 0);
        mem.record(OW, far, "minecraft:chest", Map.of("minecraft:iron_ingot", 9));
        mem.record(OW, near, "minecraft:barrel", Map.of("minecraft:iron_ingot", 1));
        mem.record("minecraft:the_nether", near, "minecraft:chest", Map.of("minecraft:iron_ingot", 64));
        List<StorageEntry> c = mem.containersWith("minecraft:iron_ingot", OW, PackedPos.pack(0, 64, 0));
        assertEquals(2, c.size());
        assertEquals(near, c.get(0).packedPos());
        assertEquals(far, c.get(1).packedPos());
        assertNull(mem.depositTarget("minecraft:iron_ingot", OW, PackedPos.pack(0, 64, 0), 2));
        assertEquals(near, mem.depositTarget("minecraft:iron_ingot", OW, PackedPos.pack(0, 64, 0), 16).packedPos());
    }

    @Test
    void markStaleNearAndRemove() {
        long a = PackedPos.pack(0, 64, 0);
        long b = PackedPos.pack(40, 64, 0);
        mem.record(OW, a, "minecraft:chest", Map.of("minecraft:coal", 4));
        mem.record(OW, b, "minecraft:chest", Map.of("minecraft:coal", 4));
        mem.markStaleNear(OW, PackedPos.pack(1, 64, 1), 8);
        assertEquals(4, mem.count("minecraft:coal", false));
        mem.remove(OW, b);
        assertFalse(mem.contains(OW, b));
        assertEquals(0, mem.count("minecraft:coal", false));
    }

    @Test
    void jsonRoundTripAndDirtyFlag() {
        long a = PackedPos.pack(-12, 5, 300);
        mem.record(OW, a, "minecraft:chest", Map.of("minecraft:diamond", 7, "minecraft:torch", 12));
        mem.markStale(OW, a);
        assertTrue(mem.consumeDirty());
        assertFalse(mem.consumeDirty());
        String json = mem.toJson();

        StorageMemory loaded = new StorageMemory(now::get, 60_000);
        loaded.loadJson(json);
        assertEquals(1, loaded.entries().size());
        StorageEntry e = loaded.entries().get(0);
        assertEquals(a, e.packedPos());
        assertEquals(OW, e.dimension());
        assertEquals(7, e.contents().get("minecraft:diamond"));
        assertTrue(e.stale());
        loaded.loadJson("not json");
        assertTrue(loaded.entries().size() <= 1, "corrupt file must not throw");
    }
}
