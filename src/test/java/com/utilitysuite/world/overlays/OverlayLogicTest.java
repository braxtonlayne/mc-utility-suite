package com.utilitysuite.world.overlays;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayLogicTest {

    // ---- trajectory ---------------------------------------------------------------------------------------------

    @Test
    void arrowFollowsVanillaTickOrder() {
        TrajectoryIntegrator t = new TrajectoryIntegrator(100);
        // Horizontal shot toward +Z (yaw 0, pitch 0), full draw, no shooter motion, no world.
        t.simulate(TrajectoryIntegrator.Kind.ARROW, 0, 100, 0, 0f, 0f, 1.0, 0, 0, 0, null);
        // Reference: pos += v; v *= 0.99; v.y -= 0.05 per tick.
        double x = 0;
        double y = 100;
        double z = 0;
        double vx = TrajectoryIntegrator.dirX(0f, 0f) * 3.0;
        double vy = TrajectoryIntegrator.dirY(0f) * 3.0;
        double vz = TrajectoryIntegrator.dirZ(0f, 0f) * 3.0;
        for (int i = 1; i < 40; i++) {
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.99;
            vy *= 0.99;
            vz *= 0.99;
            vy -= 0.05;
            assertEquals((float) x, t.x(i), 1e-4);
            assertEquals((float) y, t.y(i), 1e-4);
            assertEquals((float) z, t.z(i), 1e-4);
        }
        assertEquals(3.0f, t.z(1), 1e-5);
        assertEquals(5.97f, t.z(2), 1e-5);
        assertEquals(99.95f, t.y(2), 1e-5);
    }

    @Test
    void windChargeFliesStraightAndStopsAtWalls() {
        TrajectoryIntegrator t = new TrajectoryIntegrator(64);
        TrajectoryIntegrator.Collider wall = (x, y, z) -> z >= 10 ? TrajectoryIntegrator.Collider.SOLID
                : TrajectoryIntegrator.Collider.FREE;
        t.simulate(TrajectoryIntegrator.Kind.WIND_CHARGE, 0.5, 64, 0.5, 0f, 0f, 1.0, 0, 0, 0, wall);
        assertTrue(t.hit());
        assertEquals(64.0, t.hitY(), 1e-9, "no gravity");
        assertTrue(t.hitZ() >= 10.0 && t.hitZ() < 10.3, "hit at the wall face: " + t.hitZ());
        for (int i = 0; i < t.count(); i++) {
            assertEquals(0.5f, t.x(i), 1e-6);
        }
    }

    @Test
    void waterSlowsArrowsMoreThanTridents() {
        TrajectoryIntegrator.Collider water = (x, y, z) -> TrajectoryIntegrator.Collider.WATER;
        TrajectoryIntegrator a = new TrajectoryIntegrator(30);
        a.simulate(TrajectoryIntegrator.Kind.ARROW, 0, 0, 0, 0, 0, 0, 0, 0, 3, water);
        TrajectoryIntegrator b = new TrajectoryIntegrator(30);
        b.simulate(TrajectoryIntegrator.Kind.TRIDENT, 0, 0, 0, 0, 0, 0, 0, 0, 3, water);
        assertTrue(b.z(20) > a.z(20) * 1.5, "trident water drag 0.99 vs arrow 0.6");
        assertEquals(1.0, TrajectoryIntegrator.bowPower(20), 1e-9);
        assertEquals((0.25 + 1.0) / 3.0, TrajectoryIntegrator.bowPower(10), 1e-9);
    }

    @Test
    void integratorDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        TrajectoryIntegrator t = new TrajectoryIntegrator(200);
        TrajectoryIntegrator.Collider ground = (x, y, z) -> y < 60 ? TrajectoryIntegrator.Collider.SOLID
                : TrajectoryIntegrator.Collider.FREE;
        long bytes = AllocationProbe.measure(20_000, () -> t.simulate(TrajectoryIntegrator.Kind.THROWN, 0, 70, 0,
                45f, -30f, 1.0, 0.1, 0, 0.1, ground));
        assertTrue(bytes < 1024, "allocated " + bytes);
    }

    // ---- categories, presets, items -----------------------------------------------------------------------------

    @Test
    void blockCategories() {
        Set<String> custom = Set.of("minecraft:amethyst_cluster");
        assertEquals(BlockCategories.ORE_RARE, BlockCategories.classify("minecraft:deepslate_diamond_ore", custom));
        assertEquals(BlockCategories.ORE_RARE, BlockCategories.classify("minecraft:ancient_debris", custom));
        assertEquals(BlockCategories.ORE_PRECIOUS, BlockCategories.classify("minecraft:emerald_ore", custom));
        assertEquals(BlockCategories.ORE_COMMON, BlockCategories.classify("minecraft:nether_gold_ore", custom));
        assertEquals(BlockCategories.STORAGE, BlockCategories.classify("minecraft:red_shulker_box", custom));
        assertEquals(BlockCategories.SPAWNER, BlockCategories.classify("minecraft:trial_spawner", custom));
        assertEquals(BlockCategories.LAVA, BlockCategories.classify("minecraft:lava", custom));
        assertEquals(BlockCategories.CUSTOM, BlockCategories.classify("minecraft:amethyst_cluster", custom));
        assertEquals(BlockCategories.NONE, BlockCategories.classify("minecraft:stone", custom));
        for (FilterPreset p : FilterPreset.values()) {
            assertTrue((p.mask() & BlockCategories.CUSTOM) != 0, p + " always includes custom ids");
        }
        assertEquals(0, FilterPreset.RARE_ORES.mask() & BlockCategories.STORAGE);
        assertEquals(0xFFE0A040, WorldRadarModule.storageColorFor("chest"));
        assertTrue(WorldRadarModule.storageColorFor("trapped_chest") != WorldRadarModule.storageColorFor("chest"),
                "storage is colour-coded by type");
    }

    @Test
    void itemFilter() {
        ItemFilter f = new ItemFilter();
        assertEquals(ItemFilter.Verdict.HIGHLIGHT, f.classify("minecraft:diamond"));
        assertEquals(ItemFilter.Verdict.HIDE, f.classify("minecraft:cobblestone"));
        assertEquals(ItemFilter.Verdict.HIDE, f.classify("minecraft:bread"));
        f.set("bread", "diamond", true);
        assertEquals(ItemFilter.Verdict.HIGHLIGHT, f.classify("minecraft:bread"), "ids without namespace");
        assertEquals(ItemFilter.Verdict.HIDE, f.classify("minecraft:diamond"), "junk wins");
        assertEquals(ItemFilter.Verdict.SHOW, f.classify("minecraft:apple"));
    }

    // ---- highlight index, cave cells, chunk age -----------------------------------------------------------------

    @Test
    void highlightIndexReplacesSectionsAndFeedsObservations() {
        HighlightIndex idx = new HighlightIndex();
        idx.setDimension("minecraft:overworld");
        int diamond = idx.idIndex("minecraft:diamond_ore");
        LongArrayList pos = new LongArrayList(new long[] {BlockPosPacking.pack(1, 2, 3), BlockPosPacking.pack(4, 5, 6)});
        IntArrayList val = new IntArrayList(new int[] {HighlightIndex.value(BlockCategories.ORE_RARE, true, diamond),
                HighlightIndex.value(BlockCategories.ORE_RARE, false, diamond)});
        long section = HighlightIndex.sectionKey(0, 0, 0);
        idx.replaceSection(section, pos, val);
        idx.rebuildRenderListIfDirty();
        assertEquals(2, idx.renderCount());
        int v = idx.get(BlockPosPacking.pack(1, 2, 3));
        assertTrue(HighlightIndex.exposed(v));
        assertEquals(BlockCategories.ORE_RARE, HighlightIndex.categories(v));
        assertEquals("minecraft:diamond_ore", idx.idAt(HighlightIndex.idIndexOf(v)));

        List<Long> seen = new ArrayList<>();
        idx.forEachObserved("minecraft:overworld", Set.of("minecraft:diamond_ore"), BlockPosPacking.pack(0, 0, 0), 16,
                (p, id) -> seen.add(p));
        assertEquals(2, seen.size());

        idx.setBlock(BlockPosPacking.pack(1, 2, 3), 0); // mined
        idx.replaceSection(section, new LongArrayList(), new IntArrayList()); // rescan found nothing
        idx.rebuildRenderListIfDirty();
        assertEquals(0, idx.renderCount());
        idx.setDimension("minecraft:the_nether");
        assertEquals(0, idx.size());
    }


    @Test
    void renderListRebuildDoesNotAllocatePerEntry() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HighlightIndex idx = new HighlightIndex();
        idx.setDimension("minecraft:overworld");
        int iron = idx.idIndex("minecraft:iron_ore");
        LongArrayList pos = new LongArrayList();
        IntArrayList val = new IntArrayList();
        for (int i = 0; i < 4096; i++) {
            pos.add(BlockPosPacking.pack(i & 15, i >> 8, (i >> 4) & 15));
            val.add(HighlightIndex.value(BlockCategories.ORE_COMMON, true, iron));
        }
        idx.replaceSection(HighlightIndex.sectionKey(0, 0, 0), pos, val);
        idx.rebuildRenderListIfDirty(); // grows the render arrays once
        long p = BlockPosPacking.pack(1, 1, 1);
        long bytes = AllocationProbe.measure(2_000, () -> {
            idx.setBlock(p, HighlightIndex.value(BlockCategories.ORE_COMMON, false, iron)); // marks dirty
            idx.setBlock(p, HighlightIndex.value(BlockCategories.ORE_COMMON, true, iron));
            idx.rebuildRenderListIfDirty();
        });
        assertEquals(4096, idx.renderCount());
        // One iterator object per rebuild is fine; one entry object per highlighted block (4096 here, ~65 KB per
        // rebuild) is not. The 256 B/rebuild bound leaves room for JIT warm-up noise (one run measured 88 B).
        assertTrue(bytes < 2_000 * 256, "rebuild allocated " + bytes + " bytes over 2k rebuilds");
    }
    @Test
    void caveCellsPackOneSectionIntoALong() {
        CaveCells c = new CaveCells();
        long key = HighlightIndex.sectionKey(2, -1, 3);
        long mask = (1L << CaveCells.bit(0, 0, 0)) | (1L << CaveCells.bit(3, 2, 1));
        c.setSection(key, mask);
        c.rebuildRenderListIfDirty();
        assertEquals(2, c.renderCount());
        List<Long> cells = List.of(c.renderCell(0), c.renderCell(1));
        assertTrue(cells.contains(BlockPosPacking.pack(32, -16, 48)));
        assertTrue(cells.contains(BlockPosPacking.pack(32 + 12, -16 + 8, 48 + 4)));
        c.evictOutside(2, -1, 3, 1);
        assertEquals(1, c.sectionCount());
        c.evictOutside(10, 0, 10, 2);
        assertEquals(0, c.sectionCount());
    }

    @Test
    void chunkAgeHeuristic() {
        ChunkAgeTracker t = new ChunkAgeTracker();
        t.configure(60, 2);
        t.onChunkLoad(0, 0, 100, true);
        t.onChunkLoad(1, 0, 100, true);
        t.onChunkLoad(2, 0, 100, false);
        t.onFluidUpdate(0, 0, 110);
        t.onFluidUpdate(0, 0, 120);
        t.onFluidUpdate(1, 0, 200); // outside the window: ignored
        t.settle(200);
        assertEquals(ChunkAgeTracker.NEW, t.stateOf(0, 0), "settling liquids right after arrival");
        assertEquals(ChunkAgeTracker.OLD, t.stateOf(1, 0), "quiet chunk with fluids");
        assertEquals(ChunkAgeTracker.UNKNOWN, t.stateOf(2, 0), "no fluids: no evidence");
        t.onChunkUnload(0, 0);
        assertEquals(ChunkAgeTracker.NEW, t.stateOf(0, 0), "the trail stays after unloading");
    }
}
