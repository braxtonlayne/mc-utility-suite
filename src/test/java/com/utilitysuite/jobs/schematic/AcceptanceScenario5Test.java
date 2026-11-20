package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.jobs.schematic.bom.BillOfMaterials;
import com.utilitysuite.jobs.schematic.bom.MaterialDiff;
import com.utilitysuite.jobs.schematic.bom.SubstitutionRules;
import com.utilitysuite.jobs.schematic.format.SchematicFormats;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.plan.BuildOrderPlanner;
import com.utilitysuite.jobs.schematic.plan.BuildPlan;
import com.utilitysuite.jobs.schematic.world.PlacementScan;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import com.utilitysuite.jobs.schematic.world.SiteSurvey;
import com.utilitysuite.jobs.schematic.world.SiteSurveyReport;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance Scenario 5 (schematic BOM &amp; diff): the same 3 x 4 x 3 hut written as a vanilla structure {@code .nbt},
 * Sponge v2 and v3 {@code .schem}, and a two-region {@code .litematic} (one region saved with a negative size) is
 * parsed, diffed against a world where part of it is already built, then a mock inventory and mock storage are
 * subtracted. Asserts exact remaining counts, the gather request and the build order.
 */
class AcceptanceScenario5Test {

    static final int AX = 100, AY = 64, AZ = -20;

    @TempDir
    Path dir;

    private Path writeFixture(String format) throws Exception {
        return switch (format) {
            case "nbt" -> SchematicFixtures.write(dir, "hut.nbt", SchematicFixtures.structure(3, 4, 3,
                    SchematicFixtures.HUT));
            case "schem2" -> SchematicFixtures.write(dir, "hut.schem", SchematicFixtures.sponge(2, 3, 4, 3,
                    SchematicFixtures.HUT));
            case "schem3" -> SchematicFixtures.write(dir, "hut.schem", SchematicFixtures.sponge(3, 3, 4, 3,
                    SchematicFixtures.HUT));
            case "litematic" -> SchematicFixtures.write(dir, "hut.litematic", SchematicFixtures.litematic(List.of(
                    new SchematicFixtures.Region("Lower", 0, 0, 0, 3, 2, 3, false),
                    new SchematicFixtures.Region("Upper", 0, 2, 0, 3, 2, 3, true)), SchematicFixtures.HUT));
            default -> throw new IllegalArgumentException(format);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"nbt", "schem2", "schem3", "litematic"})
    void billOfMaterialsDiffAndBuildOrder(String format) throws Exception {
        SchematicVolume v = SchematicFormats.read(writeFixture(format));
        assertEquals(3, v.sizeX());
        assertEquals(4, v.sizeY());
        assertEquals(3, v.sizeZ());
        assertEquals(BlockStateString.canonical(SchematicFixtures.DOOR_LOWER), v.state(1, 1, 0));

        // Full bill: door and slabs counted once / by type, waterlogging ignored, wall torch -> torch.
        BillOfMaterials full = BillOfMaterials.compute(v, SubstitutionRules.NONE);
        assertEquals(Map.of("minecraft:cobblestone", 9, "minecraft:oak_planks", 12, "minecraft:oak_door", 1,
                "minecraft:oak_slab", 3, "minecraft:torch", 1, "minecraft:stone_bricks", 9), full.asMap());
        assertEquals(35, full.totalBlocks()); // 36 cells - 1 air

        // World: floor built, 4 planks placed, one dirt in a plank cell, door placed facing another way.
        FakeWorld world = new FakeWorld(v);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                world.set(AX + x, AY, AZ + z, "minecraft:cobblestone");
                world.set(AX + x, AY - 1, AZ + z, "minecraft:stone"); // ground
            }
        }
        world.set(AX, AY + 1, AZ, "minecraft:oak_planks");
        world.set(AX + 2, AY + 1, AZ, "minecraft:oak_planks");
        world.set(AX, AY + 1, AZ + 1, "minecraft:oak_planks");
        world.set(AX + 2, AY + 1, AZ + 1, "minecraft:oak_planks");
        world.set(AX, AY + 1, AZ + 2, "minecraft:dirt");
        world.set(AX + 1, AY + 1, AZ, SchematicFixtures.DOOR_LOWER.replace("facing=north", "facing=south"));

        PlacementScan scan = new PlacementScan(v, AX, AY, AZ);
        int ticks = 0;
        while (!scan.step(world, 8)) { // tiny budget: the scan must resume across ticks
            ticks++;
        }
        assertTrue(ticks >= 3, "scan should take several budgeted steps");
        byte[] status = scan.snapshot();
        assertEquals(PlacementStatus.WRONG, status[v.index(0, 1, 2)]);
        assertEquals(PlacementStatus.SAME_BLOCK, status[v.index(1, 1, 0)]);
        assertEquals(PlacementStatus.CLEAR, status[v.index(1, 1, 1)]);

        BillOfMaterials remaining = BillOfMaterials.compute(v, status, SubstitutionRules.NONE);
        assertEquals(Map.of("minecraft:oak_planks", 8, "minecraft:oak_slab", 3, "minecraft:torch", 1,
                "minecraft:stone_bricks", 9), remaining.asMap());
        assertEquals(14, remaining.placedBlocks()); // 9 floor + 4 planks + door lower

        Map<String, Integer> inventory = Map.of("minecraft:oak_planks", 5, "minecraft:oak_slab", 1,
                "minecraft:torch", 4, "minecraft:cobblestone", 64);
        IStorageIndex storage = new FakeStorage(List.of(
                new StorageEntry(1L, "minecraft:overworld", "chest",
                        Map.of("minecraft:oak_planks", 1, "minecraft:stone_bricks", 20), 0L, false),
                new StorageEntry(2L, "minecraft:overworld", "barrel", Map.of("minecraft:oak_slab", 1), 0L, false),
                new StorageEntry(3L, "minecraft:overworld", "chest", Map.of("minecraft:oak_slab", 64), 0L, true)));
        MaterialDiff diff = MaterialDiff.compute(remaining, inventory, MaterialDiff.storageCounts(storage));

        MaterialDiff.Line planks = diff.line("minecraft:oak_planks");
        assertEquals(5, planks.fromInventory());
        assertEquals(1, planks.fromStorage());
        assertEquals(2, planks.missing());
        assertEquals(0, diff.line("minecraft:stone_bricks").missing());
        assertEquals(9, diff.line("minecraft:stone_bricks").fromStorage());
        assertEquals(0, diff.line("minecraft:torch").missing());
        // The stale chest's 64 slabs are not counted.
        assertEquals(Map.of("minecraft:oak_planks", 2, "minecraft:oak_slab", 1), diff.missing());

        GoalRequest.GatherMany gather = new GoalRequest.GatherMany(diff.missing(), "schematic hut",
                CostProfile.values()[0], DataSourceMode.values()[0]);
        assertEquals(2, gather.items().size());

        // Survey: one wrong block, nothing else.
        SiteSurveyReport survey = SiteSurvey.survey(scan, status, -64, 320);
        assertEquals(1, survey.wrongBlocks());
        assertEquals(0, survey.obstructions());
        assertEquals(14, survey.placed());

        // Build order: layer by layer, attachments (door, torch) after every solid, nothing already placed.
        BuildPlan plan = BuildOrderPlanner.plan(v, status, BuildOrderPlanner.Options.DEFAULT);
        List<BuildPlan.Step> steps = plan.steps();
        assertEquals(0, plan.count(BuildPlan.SCAFFOLD));
        // 35 blocks - 14 placed; the door's upper half is part of the plan (placing the lower half creates it).
        assertEquals(35 - 14, steps.size());
        int lastSolidY = -1;
        int firstAttachment = -1;
        int lastSolid = -1;
        for (int i = 0; i < steps.size(); i++) {
            BuildPlan.Step s = steps.get(i);
            boolean attachment = s.state().contains("torch") || s.state().contains("_door");
            if (attachment) {
                if (firstAttachment < 0) {
                    firstAttachment = i;
                }
            } else {
                assertTrue(s.y() >= lastSolidY, "solids go bottom-up: " + s);
                lastSolidY = s.y();
                lastSolid = i;
            }
        }
        assertTrue(firstAttachment > lastSolid, "attachments come after all solid blocks");
        assertEquals(1, steps.get(0).y(), "the first open layer is y=1 (the floor is already built)");
        assertEquals(BlockStateString.canonical(SchematicFixtures.WALL_TORCH), steps.get(steps.size() - 1).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nbt", "schem2", "schem3", "litematic"})
    void everyFormatYieldsTheSameCells(String format) throws Exception {
        SchematicVolume v = SchematicFormats.read(writeFixture(format));
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    assertEquals(BlockStateString.canonical(SchematicFixtures.HUT.at(x, y, z)), v.state(x, y, z),
                            format + " at " + x + "," + y + "," + z);
                }
            }
        }
    }

    record FakeStorage(List<StorageEntry> entries) implements IStorageIndex {
        @Override
        public int count(String itemId, boolean includeStale) {
            int c = 0;
            for (StorageEntry e : entries) {
                if (includeStale || !e.stale()) {
                    c += e.contents().getOrDefault(itemId, 0);
                }
            }
            return c;
        }

        @Override
        public List<StorageEntry> containersWith(String itemId, String dimension, long fromPackedPos) {
            return entries.stream().filter(e -> e.contents().containsKey(itemId)).toList();
        }
    }
}
