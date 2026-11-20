package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.StorageEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance Scenario 1: "gather 32 diamonds" from a fresh spawn bootstraps the whole tool chain in the exact order;
 * with an iron pickaxe in storage or inventory, the bootstrap chain is skipped.
 */
class AcceptanceScenario1Test {

    private final DependencyPlanner planner = VanillaRecipes.planner();
    private final DependencyPlanner.Target diamonds =
            new DependencyPlanner.Target("minecraft:diamond", 32, DependencyPlanner.TargetMode.GATHER);

    @Test
    void freshSpawnProducesTheExactBootstrapChain() {
        Plan plan = planner.plan(diamonds, PlanContext.ofInventory(Map.of(), 0));

        assertEquals(List.of("Wood", "Planks", "Crafting Table", "Wooden Pick", "Cobblestone",
                "Stone Pick + Furnace", "Iron Ore + Fuel", "Iron Ingots", "Iron Pick", "Mine 32 Diamonds",
                "Return/Deposit"), plan.milestones(), String.join("\n", plan.describe()));
        assertTrue(plan.warnings().isEmpty(), plan.warnings().toString());

        // one step per item, aggregated counts
        assertEquals(3, step(plan, "minecraft:oak_log").count(), "logs: table + pick planks + sticks");
        assertEquals(11, step(plan, "minecraft:cobblestone").count(), "3 for the stone pick + 8 for the furnace");
        assertEquals(3, step(plan, "minecraft:raw_iron").count());
        assertEquals(1, step(plan, "minecraft:coal").count(), "1 coal smelts 8 >= 3 ingots");
        assertEquals(StepRole.FUEL, step(plan, "minecraft:coal").role());
        PlanStep target = step(plan, "minecraft:diamond");
        assertEquals(StepKind.MINE, target.kind());
        assertEquals(3, target.requiredTier());
        assertEquals(List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"), target.blockIds());
        assertEquals(Map.of("minecraft:oak_log", 3, "minecraft:cobblestone", 11, "minecraft:raw_iron", 3,
                "minecraft:coal", 1, "minecraft:diamond", 32), plan.missing());
    }

    @Test
    void ironPickInStorageSkipsTheBootstrap() {
        long chest = PackedPos.pack(10, 64, 10);
        StorageEntry e = new StorageEntry(chest, "minecraft:overworld", "minecraft:chest",
                Map.of("minecraft:iron_pickaxe", 1, "minecraft:cobblestone", 40), 0L, false);
        Plan plan = planner.plan(diamonds, PlanContext.ofInventory(Map.of(), 0).withStorage(List.of(e)));

        assertEquals(List.of("Fetch Iron Pick", "Mine 32 Diamonds", "Return/Deposit"), plan.milestones());
        assertEquals(chest, plan.steps().get(0).storagePos());
    }

    @Test
    void ironPickInInventorySkipsTheBootstrap() {
        Plan plan = planner.plan(diamonds,
                PlanContext.ofInventory(Map.of("minecraft:iron_pickaxe", 1), 3));
        assertEquals(List.of("Mine 32 Diamonds", "Return/Deposit"), plan.milestones());
    }

    @Test
    void stonePickOnlyAddsTheIronBranch() {
        Plan plan = planner.plan(diamonds, PlanContext.ofInventory(
                Map.of("minecraft:stone_pickaxe", 1, "minecraft:crafting_table", 1, "minecraft:furnace", 1,
                        "minecraft:stick", 2), 2));
        assertEquals(List.of("Iron Ore + Fuel", "Iron Ingots", "Iron Pick", "Mine 32 Diamonds", "Return/Deposit"),
                plan.milestones(), String.join("\n", plan.describe()));
    }

    static PlanStep step(Plan p, String item) {
        for (PlanStep s : p.steps()) {
            if (s.itemId().equals(item)) {
                return s;
            }
        }
        throw new AssertionError("no step for " + item + " in\n" + String.join("\n", p.describe()));
    }
}
