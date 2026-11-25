package com.utilitysuite.world.seed;

import com.google.gson.JsonObject;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.overlays.HighlightIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedDiffServiceTest {

    private static final long TEST_SEED = 0x123456789ABCDEF0L;

    private static int[] flatHeights(int y) {
        int[] heights = new int[256];
        Arrays.fill(heights, y);
        return heights;
    }

    private static long[] solidSection() {
        long[] mask = new long[64];
        Arrays.fill(mask, -1L);
        return mask;
    }

    @Test
    void naturalTerrainProducesLowAnomalyScore() {
        TerrainBaseline baseline = new TerrainBaseline();
        int[] heights = flatHeights(64);
        long[] naturalMask = new long[64];
        baseline.expectedSectionMask(TEST_SEED, 3, 1, -2, heights, naturalMask);

        TerrainBaseline.DiffResult result = baseline.diffSection(TEST_SEED, 3, 1, -2, naturalMask, heights);
        assertTrue(result.score() < 0.35f, "Natural terrain should have low anomaly score, got " + result.score());
        assertFalse(result.hasTunnel());
        assertFalse(result.hasChamber());
    }

    @Test
    void stripMineTunnelIsFlaggedCleanly() {
        TerrainBaseline baseline = new TerrainBaseline();
        int[] heights = flatHeights(64);
        long[] mask = solidSection();

        // Carve a straight 1x2 strip-mine tunnel along X from x=1..14 at y=4..5, z=7.
        for (int x = 1; x <= 14; x++) {
            TerrainBaseline.setSolid(mask, x, 4, 7, false);
            TerrainBaseline.setSolid(mask, x, 5, 7, false);
        }

        TerrainBaseline.DiffResult result = baseline.diffSection(TEST_SEED, 0, 1, 0, mask, heights);
        assertTrue(result.score() >= 0.65f, "1x2 tunnel should score >= 0.65, got " + result.score());
        assertTrue(result.hasTunnel(), "Expected FLAG_TUNNEL");
        assertTrue(result.hints().length > 0, "Expected packed hint position");
    }

    @Test
    void flatStoneBunkerChamberIsFlaggedCleanly() {
        TerrainBaseline baseline = new TerrainBaseline();
        int[] heights = flatHeights(64);
        long[] mask = solidSection();

        // Carve a flat 7x4x7 stone bunker at x=4..10, y=3..6, z=4..10.
        for (int y = 3; y <= 6; y++) {
            for (int z = 4; z <= 10; z++) {
                for (int x = 4; x <= 10; x++) {
                    TerrainBaseline.setSolid(mask, x, y, z, false);
                }
            }
        }

        TerrainBaseline.DiffResult result = baseline.diffSection(TEST_SEED, -2, 0, 5, mask, heights);
        assertTrue(result.score() >= 0.70f, "7x4x7 bunker should score >= 0.70, got " + result.score());
        assertTrue(result.hasChamber(), "Expected FLAG_CHAMBER");
        assertTrue(result.hints().length > 0, "Expected packed hint position");
    }

    @Test
    void serviceRunsOffThreadAndPublishesConfidenceMap() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SeedDiffService service = new SeedDiffService(queue::add);

        int[] heights = flatHeights(64);
        long[] mask = solidSection();
        for (int y = 3; y <= 6; y++) {
            for (int z = 4; z <= 10; z++) {
                for (int x = 4; x <= 10; x++) {
                    TerrainBaseline.setSolid(mask, x, y, z, false);
                }
            }
        }

        assertTrue(service.submit(TEST_SEED, 2, 1, 3, mask, heights));
        assertFalse(service.submit(TEST_SEED, 2, 1, 3, mask, heights), "Duplicate pending submit must be rejected");
        assertEquals(1, queue.size());

        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals(1, service.drainCompleted(16));
        long key = HighlightIndex.sectionKey(2, 1, 3);
        assertTrue(service.confidence(key) >= 0.70f);
        assertEquals(1, service.flaggedCount());
        assertEquals(1, service.renderCount());

        service.clear();
        assertEquals(0, service.flaggedCount());
        assertEquals(0, service.renderCount());
    }

    @Test
    void moduleConstructionAndSettingsWorkWithoutMinecraft() {
        SeedDiffModule module = new SeedDiffModule(null);
        assertEquals(SeedDiffModule.ID, module.id());
        assertEquals("Seed Diff Detector", module.displayName());
        assertFalse(module.isEnabled(), "Module should be disabled by default");

        List<SettingSpec> specs = module.settings();
        assertEquals(5, specs.size());
        assertEquals("radius_chunks", specs.get(0).key());
        assertEquals("min_confidence", specs.get(1).key());
        assertEquals("flag_tunnels", specs.get(2).key());
        assertEquals("flag_chambers", specs.get(3).key());
        assertEquals("chunks_per_tick", specs.get(4).key());

        ProfileConfig profile = new ProfileConfig("test", new JsonObject());
        module.onProfileLoaded(profile);
        module.setEnabled(true);
        assertTrue(module.isEnabled());
        module.setEnabled(false);
        assertFalse(module.isEnabled());
    }
}
