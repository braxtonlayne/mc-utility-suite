package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.StorageEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.utilitysuite.jobs.planner.AcceptanceScenario1Test.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyPlannerTest {

    private final DependencyPlanner planner = VanillaRecipes.planner();

    private static DependencyPlanner.Target craft(String item, int n) {
        return new DependencyPlanner.Target("minecraft:" + item, n, DependencyPlanner.TargetMode.CRAFT);
    }

    private static DependencyPlanner.Target gather(String item, int n) {
        return new DependencyPlanner.Target("minecraft:" + item, n, DependencyPlanner.TargetMode.GATHER);
    }

    private static PlanStep find(Plan p, StepKind kind, String item) {
        for (PlanStep s : p.steps()) {
            if (s.kind() == kind && s.itemId().equals(item)) {
                return s;
            }
        }
        return null;
    }

    @Test
    void surplusIsCreditedWithinThePlan() {
        // 1 log -> 4 planks; 2 planks -> 4 sticks. Crafting 1 stick needs 2 planks: 2 planks and 3 sticks are surplus.
        Plan p = planner.plan(craft("stick", 1),
                PlanContext.ofInventory(Map.of("minecraft:oak_log", 1), 0).withReturn(false));
        assertEquals(Map.of("minecraft:oak_planks", 2, "minecraft:stick", 3), p.surplus());
        // a second consumer in the same plan uses the 2 surplus planks instead of a second log
        Plan both = planner.plan(List.of(craft("stick", 1), craft("crafting_table", 1)),
                PlanContext.ofInventory(Map.of("minecraft:oak_log", 2), 0).withReturn(false));
        PlanStep planks = find(both, StepKind.CRAFT, "minecraft:oak_planks");
        assertEquals(2, planks.crafts(), "2 logs -> 8 planks covers 2 (sticks) + 4 (table) with 2 left");
        assertEquals(2, both.surplus().get("minecraft:oak_planks"));
        assertTrue(both.missing().isEmpty(), both.missing().toString());
    }

    @Test
    void surplusIsCreditedToQueuedJobs() {
        Plan first = planner.plan(craft("stick", 1),
                PlanContext.ofInventory(Map.of("minecraft:oak_log", 1), 0).withReturn(false));
        // queued job: the first job's 2 surplus planks are credited, so only 2 more planks (1 log) are needed
        Plan second = planner.plan(craft("crafting_table", 1),
                PlanContext.ofInventory(Map.of(), 0).withReturn(false).withCredit(first.surplus()));
        assertEquals(1, step(second, "minecraft:oak_log").count());
        assertEquals(1, find(second, StepKind.CRAFT, "minecraft:oak_planks").crafts());
    }

    @Test
    void prefersRecipesCoveredByInventoryOverMining() {
        Plan p = planner.plan(gather("diamond", 9), PlanContext.ofInventory(Map.of("minecraft:diamond_block", 1), 0));
        assertEquals(1, p.steps().size(), String.join("\n", p.describe()));
        assertEquals(StepKind.CRAFT, p.steps().get(0).kind());
        assertTrue(p.missing().isEmpty());

        long chest = PackedPos.pack(5, 70, 5);
        StorageEntry e = new StorageEntry(chest, "minecraft:overworld", "minecraft:barrel",
                Map.of("minecraft:iron_block", 1), 0, false);
        Plan hopper = planner.plan(craft("hopper", 1), PlanContext.ofInventory(Map.of("minecraft:chest", 1,
                "minecraft:crafting_table", 1), 0).withStorage(List.of(e)).withReturn(false));
        assertEquals(List.of("Fetch Iron Block", "Iron Ingots", "Craft 1 Hopper"), hopper.milestones());
        assertNull(find(hopper, StepKind.MINE, "minecraft:raw_iron"), "no mining when storage covers the recipe");
    }

    @Test
    void picksTheAlternativeIngredientBestCoveredByInventory() {
        Plan p = planner.plan(craft("crafting_table", 1),
                PlanContext.ofInventory(Map.of("minecraft:spruce_log", 1), 0).withReturn(false));
        assertEquals(List.of("Planks", "Craft 1 Crafting Table"), p.milestones());
        assertEquals("minecraft:spruce_planks", p.steps().get(0).itemId());
    }

    @Test
    void fuelComesFromInventoryBeforeMining() {
        Map<String, Integer> inv = Map.of("minecraft:raw_iron", 3, "minecraft:coal", 1, "minecraft:furnace", 1);
        Plan p = planner.plan(craft("iron_ingot", 3), PlanContext.ofInventory(inv, 2).withReturn(false));
        assertEquals(List.of("Smelt 3 Iron Ingots"), p.milestones());

        Map<String, Integer> inv10 = Map.of("minecraft:raw_iron", 10, "minecraft:coal", 1, "minecraft:furnace", 1);
        Plan p10 = planner.plan(craft("iron_ingot", 10), PlanContext.ofInventory(inv10, 2).withReturn(false));
        PlanStep coal = find(p10, StepKind.MINE, "minecraft:coal");
        assertEquals(1, coal.count(), "1 carried coal smelts 8; 2 more smelts need 1 mined coal");
        assertEquals(StepRole.FUEL, coal.role());
    }

    @Test
    void storedFuelIsFetchedAndPreserveTerrainUsesPlanks() {
        long chest = PackedPos.pack(0, 64, 3);
        StorageEntry e = new StorageEntry(chest, "minecraft:overworld", "minecraft:chest",
                Map.of("minecraft:charcoal", 5), 0, false);
        Map<String, Integer> inv = Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1);
        Plan fetched = planner.plan(craft("iron_ingot", 3),
                PlanContext.ofInventory(inv, 2).withStorage(List.of(e)).withReturn(false));
        PlanStep f = find(fetched, StepKind.FETCH, "minecraft:charcoal");
        assertEquals(1, f.count());
        assertNull(find(fetched, StepKind.MINE, "minecraft:coal"));

        Plan planks = planner.plan(craft("iron_ingot", 3), PlanContext.ofInventory(
                Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1, "minecraft:oak_log", 1), 2)
                .withCost(CostProfile.PRESERVE_TERRAIN).withReturn(false));
        assertNull(find(planks, StepKind.MINE, "minecraft:coal"));
        assertEquals(StepRole.FUEL, find(planks, StepKind.CRAFT, "minecraft:oak_planks").role());
    }

    @Test
    void obsidianNeedsADiamondPick() {
        Plan p = planner.plan(gather("obsidian", 10),
                PlanContext.ofInventory(Map.of("minecraft:iron_pickaxe", 1, "minecraft:crafting_table", 1,
                        "minecraft:stick", 2), 3));
        assertEquals(List.of("Diamond", "Diamond Pick", "Mine 10 Obsidian", "Return/Deposit"), p.milestones());
        assertEquals(3, step(p, "minecraft:diamond").count());
        assertEquals(4, step(p, "minecraft:obsidian").requiredTier());
    }

    @Test
    void unknownItemsAreReportedNotLooped() {
        Plan p = planner.plan(gather("dragon_egg", 1), PlanContext.ofInventory(Map.of(), 0));
        assertFalse(p.warnings().isEmpty());
        assertEquals(1, p.missing().get("minecraft:dragon_egg"));
    }

    @Test
    void toolRepairPlansOnlyTheToolBranch() {
        Plan p = planner.planTool(3, PlanContext.ofInventory(Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2,
                "minecraft:crafting_table", 1), 2).withReturn(false));
        assertEquals(List.of("Iron Pick"), p.milestones());
    }

    @Test
    void mineByYieldArithmeticWithFortune() {
        HarvestTable.Source diamondOre = HarvestTable.vanilla().sourcesFor("minecraft:diamond").get(0);
        assertEquals(1.0, HarvestTable.expectedDrops(diamondOre, 0), 1e-9);
        assertEquals(4.0 / 3.0, HarvestTable.expectedDrops(diamondOre, 1), 1e-9);
        assertEquals(1.75, HarvestTable.expectedDrops(diamondOre, 2), 1e-9);
        assertEquals(2.2, HarvestTable.expectedDrops(diamondOre, 3), 1e-9);
        assertEquals(32, HarvestTable.blocksNeeded(diamondOre, 0, 32));
        assertEquals(15, HarvestTable.blocksNeeded(diamondOre, 3, 32), "ceil(32 / 2.2)");
        assertEquals(0, HarvestTable.blocksNeeded(diamondOre, 3, 0));
        HarvestTable.Source redstone = HarvestTable.vanilla().sourcesFor("minecraft:redstone").get(0);
        assertEquals(6.0, HarvestTable.expectedDrops(redstone, 3), 1e-9);
        HarvestTable.Source stone = HarvestTable.vanilla().sourcesFor("minecraft:cobblestone").get(0);
        assertEquals(1.0, HarvestTable.expectedDrops(stone, 3), 1e-9, "Fortune does not affect stone");
    }

    @Test
    void packedPosMatchesBlockPosLayout() {
        long p = PackedPos.pack(-123, -60, 4567);
        assertEquals(-123, PackedPos.x(p));
        assertEquals(-60, PackedPos.y(p));
        assertEquals(4567, PackedPos.z(p));
        assertEquals(net.minecraft.core.BlockPos.asLong(-123, -60, 4567), p);
    }

    @Test
    void errandsAreBatchedByContainerAndYBand() {
        long chest = PackedPos.pack(1, 64, 1);
        PlanStep fetchA = new PlanStep(StepKind.FETCH, "minecraft:iron_ingot", 3, 0, null, List.of(), 0, chest,
                "minecraft:overworld", StepRole.PRIMARY, "Fetch Iron Ingots", null);
        PlanStep fetchB = new PlanStep(StepKind.FETCH, "minecraft:coal", 2, 0, null, List.of(), 0, chest,
                "minecraft:overworld", StepRole.PRIMARY, "Fetch Coal", null);
        PlanStep diamonds = new PlanStep(StepKind.MINE, "minecraft:diamond", 5, 0, null,
                List.of("minecraft:diamond_ore"), 3, PlanStep.NO_POS, "minecraft:overworld", StepRole.TARGET, "d", null);
        PlanStep redstone = new PlanStep(StepKind.MINE, "minecraft:redstone", 20, 0, null,
                List.of("minecraft:redstone_ore"), 3, PlanStep.NO_POS, "minecraft:overworld", StepRole.TARGET, "r",
                null);
        Plan a = new Plan(List.of(fetchA, diamonds), Map.of(), List.of(), Map.of());
        Plan b = new Plan(List.of(fetchB, redstone), Map.of(), List.of(), Map.of());
        List<ErrandBatcher.Errand> errands = ErrandBatcher.batch(List.of(new ErrandBatcher.JobPlan(1, a),
                new ErrandBatcher.JobPlan(2, b)), ErrandBatcher.DEFAULT_BAND_HEIGHT);
        assertEquals(2, errands.size());
        assertTrue(errands.get(0).key().startsWith("chest@"));
        assertEquals(List.of(1L, 2L), errands.get(0).jobIds());
        assertTrue(errands.get(1).key().startsWith("yband@"));
        assertTrue(ErrandBatcher.sameBand(diamonds, redstone, 16));
    }
}
