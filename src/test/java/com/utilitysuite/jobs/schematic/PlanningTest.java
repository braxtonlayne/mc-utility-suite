package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.plan.BuildOrderPlanner;
import com.utilitysuite.jobs.schematic.plan.BuildPlan;
import com.utilitysuite.jobs.schematic.plan.StagingPlanner;
import com.utilitysuite.jobs.schematic.world.PlacementScan;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import com.utilitysuite.jobs.schematic.world.SiteSurvey;
import com.utilitysuite.jobs.schematic.world.SiteSurveyReport;
import com.utilitysuite.jobs.schematic.world.WorldProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningTest {

    // ---- build order ------------------------------------------------------------------------------------------------

    @Test
    void gravityAfterSolidsOfItsLayerAndAttachmentsLast() {
        // 2x2x1 layer: sand at (0,0,0), stone elsewhere; torch on top of a stone at y=1; glass on top of the sand.
        SchematicVolume.Builder b = SchematicVolume.builder("g", "test", 2, 2, 2);
        b.set(0, 0, 0, "minecraft:sand");
        b.set(1, 0, 0, "minecraft:stone");
        b.set(0, 0, 1, "minecraft:stone");
        b.set(1, 0, 1, "minecraft:stone");
        b.set(1, 1, 1, "minecraft:torch");
        b.set(0, 1, 0, "minecraft:glass");
        b.set(1, 1, 0, "minecraft:air");
        b.set(0, 1, 1, "minecraft:air");
        BuildPlan plan = BuildOrderPlanner.plan(b.build(), null, BuildOrderPlanner.Options.DEFAULT);
        List<BuildPlan.Step> s = plan.steps();
        assertEquals(6, s.size());
        assertEquals("minecraft:stone", s.get(0).state());
        assertEquals("minecraft:stone", s.get(1).state());
        assertEquals("minecraft:stone", s.get(2).state());
        assertEquals("minecraft:sand", s.get(3).state());
        assertEquals("minecraft:glass", s.get(4).state());
        assertEquals("minecraft:torch", s.get(5).state());
    }

    @Test
    void overhangGrowsFromItsWallWithoutScaffolding() {
        // Wall column at x=0 (y=0..3) and a beam at y=3 reaching out to x=4: the beam is placed outward from the wall.
        SchematicVolume.Builder b = SchematicVolume.builder("beam", "test", 5, 4, 1);
        for (int y = 0; y < 4; y++) {
            b.set(0, y, 0, "minecraft:stone");
        }
        for (int x = 1; x < 5; x++) {
            b.set(x, 3, 0, "minecraft:oak_planks");
        }
        BuildPlan plan = BuildOrderPlanner.plan(b.build(), null, BuildOrderPlanner.Options.DEFAULT);
        assertEquals(0, plan.count(BuildPlan.SCAFFOLD));
        List<BuildPlan.Step> s = plan.steps();
        int[] beamX = s.stream().filter(st -> st.y() == 3 && st.x() > 0).mapToInt(BuildPlan.Step::x).toArray();
        assertEquals(List.of(1, 2, 3, 4), java.util.Arrays.stream(beamX).boxed().toList());
    }

    @Test
    void floatingBlockGetsScaffoldColumnRemovedAtTheEnd() {
        // A single block floating at y=3 above an empty 1x4x1 column.
        SchematicVolume.Builder b = SchematicVolume.builder("float", "test", 1, 4, 1);
        b.set(0, 0, 0, "minecraft:air");
        b.set(0, 1, 0, "minecraft:air");
        b.set(0, 2, 0, "minecraft:air");
        b.set(0, 3, 0, "minecraft:gold_block");
        BuildPlan plan = BuildOrderPlanner.plan(b.build(), null, BuildOrderPlanner.Options.DEFAULT);
        List<BuildPlan.Step> s = plan.steps();
        // scaffold y=0,1,2 bottom-up, then the block, then removal top-down
        assertEquals(7, s.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(BuildPlan.SCAFFOLD, s.get(i).kind());
            assertEquals(i, s.get(i).y());
        }
        assertEquals("minecraft:gold_block", s.get(3).state());
        assertEquals(BuildPlan.REMOVE_SCAFFOLD, s.get(4).kind());
        assertEquals(2, s.get(4).y());
        assertEquals(0, s.get(6).y());
        assertEquals(0, plan.unsupportedPlacements());
    }

    @Test
    void tooTallGapIsReportedUnsupported() {
        SchematicVolume.Builder b = SchematicVolume.builder("tall", "test", 1, 6, 1);
        b.set(0, 5, 0, "minecraft:stone");
        BuildPlan plan = BuildOrderPlanner.plan(b.build(), null, new BuildOrderPlanner.Options("minecraft:dirt", 3));
        assertEquals(1, plan.unsupportedPlacements());
        assertEquals(0, plan.count(BuildPlan.SCAFFOLD));
    }

    // ---- site survey ------------------------------------------------------------------------------------------------

    @Test
    void surveyFindsObstructionsFluidsGravityAndHeightLimit() {
        // 3x2x1: y=0 [sand, stone, air]; y=1 [stone, air, gravel]
        SchematicVolume.Builder b = SchematicVolume.builder("s", "test", 3, 2, 1);
        b.set(0, 0, 0, "minecraft:sand");
        b.set(1, 0, 0, "minecraft:stone");
        b.set(2, 0, 0, "minecraft:air");
        b.set(0, 1, 0, "minecraft:stone");
        b.set(1, 1, 0, "minecraft:air");
        b.set(2, 1, 0, "minecraft:gravel");
        SchematicVolume v = b.build();
        int ay = 318; // layer y=1 lands on world y=319, above a build limit of 319 (exclusive)
        FakeWorld world = new FakeWorld(v);
        world.maxY = 319;
        world.set(2, ay, 0, "minecraft:dirt");               // dirt in the air cell (2,0) -> obstruction
        world.set(0, ay - 1, 0, "minecraft:water[level=0]"); // under the bottom-layer sand: fluid -> unsupported
        world.set(1, ay, 0, "minecraft:water[level=0]");     // fluid in a block cell
        PlacementScan scan = new PlacementScan(v, 0, ay, 0);
        while (!scan.step(world, 100)) {
            // budgeted
        }
        SiteSurveyReport r = SiteSurvey.survey(scan, scan.snapshot(), -64, 319);
        assertEquals(1, r.obstructions());
        assertEquals(1, r.fluidConflicts());
        assertEquals(2, r.unsupportedGravity()); // sand over water, gravel over air
        assertEquals(2, r.outOfBounds());         // the y=1 blocks at world y=319
        assertEquals(4, r.totalBlocks());
        assertTrue(r.summaryLines().size() >= 4);
    }

    // ---- staging ----------------------------------------------------------------------------------------------------

    @Test
    void stagingGroupsByPhaseAndPacksStacks() {
        // 10x2x10: y=0 is 100 stone, y=1 is 100 oak planks plus nothing else; 1 layer per phase.
        SchematicVolume.Builder b = SchematicVolume.builder("st", "test", 10, 3, 10);
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 10; x++) {
                b.set(x, 0, z, "minecraft:stone");
                b.set(x, 1, z, "minecraft:oak_planks");
            }
        }
        b.set(0, 2, 0, "minecraft:oak_sign[rotation=0,waterlogged=false]");
        BuildPlan plan = BuildOrderPlanner.plan(b.build(), null, BuildOrderPlanner.Options.DEFAULT);
        StagingPlanner.StagingPlan staging = StagingPlanner.plan(plan, 1, 1, 3);
        // stone: 2 stacks (64+36), planks: 2 stacks -> chest 0 holds 3 slots, the 4th overflows; sign last phase.
        assertEquals(3, staging.phases());
        StagingPlanner.Assignment first = staging.assignments().get(0);
        assertEquals("minecraft:stone", first.item());
        assertEquals(0, first.chest());
        assertEquals(100, first.count());
        assertEquals(2, first.slots());
        StagingPlanner.Assignment planks = staging.assignments().get(1);
        assertEquals("minecraft:oak_planks", planks.item());
        assertEquals(0, planks.chest());
        assertEquals(64, planks.count());
        StagingPlanner.Assignment overflow = staging.assignments().get(2);
        assertEquals(-1, overflow.chest());
        assertEquals(36, overflow.count());
        StagingPlanner.Assignment sign = staging.assignments().get(3);
        assertEquals("minecraft:oak_sign", sign.item());
        assertEquals(3, sign.phase(), "attachments form the phase after the last layer band (3 layers -> 0..2)");
        assertEquals(2, staging.chestsNeeded());
        assertEquals(16, StagingPlanner.stackSize("minecraft:oak_sign"));
        assertEquals(1, StagingPlanner.stackSize("minecraft:red_bed"));
    }

    // ---- hot path ---------------------------------------------------------------------------------------------------

    /** Array-backed, allocation-free probe: every cell empty except a floor at y=0. */
    private static final class ArrayProbe implements WorldProbe {
        @Override
        public int probe(int x, int y, int z, int paletteIndex) {
            return y <= 64 ? PlacementStatus.W_OTHER : PlacementStatus.W_EMPTY;
        }
    }

    @Test
    void scanStepDoesNotAllocate() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        SchematicVolume.Builder b = SchematicVolume.builder("big", "test", 64, 32, 64);
        for (int y = 0; y < 32; y += 2) {
            for (int z = 0; z < 64; z++) {
                for (int x = 0; x < 64; x++) {
                    b.set(x, y, z, (x + z) % 3 == 0 ? "minecraft:sand" : "minecraft:stone_bricks");
                }
            }
        }
        SchematicVolume v = b.build();
        PlacementScan scan = new PlacementScan(v, 0, 64, 0);
        ArrayProbe probe = new ArrayProbe();
        int[] counts = new int[8];
        long bytes = AllocationProbe.measure(2_000, () -> {
            if (scan.step(probe, 512)) {
                scan.restart();
            }
            scan.countStatuses(counts);
        });
        assertEquals(0L, bytes, "PlacementScan.step must not allocate");
        assertTrue(counts[PlacementStatus.MISSING] + counts[PlacementStatus.WRONG] > 0);
    }

    @Test
    void largeScanIsBudgeted() {
        SchematicVolume.Builder b = SchematicVolume.builder("big", "test", 128, 16, 128);
        for (int z = 0; z < 128; z++) {
            for (int x = 0; x < 128; x++) {
                b.set(x, 0, z, "minecraft:stone");
            }
        }
        SchematicVolume v = b.build();
        PlacementScan scan = new PlacementScan(v, 0, 64, 0);
        int[] calls = new int[1];
        WorldProbe counting = (x, y, z, p) -> {
            calls[0]++;
            return PlacementStatus.W_EMPTY;
        };
        int ticks = 0;
        while (!scan.step(counting, 1024)) {
            ticks++;
            assertTrue(calls[0] <= 1024L * (ticks + 1));
        }
        assertEquals(16384, calls[0], "void cells cost nothing; only the 16384 block cells were probed");
        assertTrue(ticks >= 15);
    }
}
