package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.StorageEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four cost profiles produce genuinely different plans for the same goal: "gather 3 iron ingots" from an empty
 * inventory with a chest holding a diamond pickaxe, a stone pickaxe and some cooked beef (no fuel stored).
 */
class CostProfilePlanTest {

    final DependencyPlanner planner = VanillaRecipes.planner();
    final long chest = PackedPos.pack(4, 64, 4);
    final StorageEntry entry = new StorageEntry(chest, "minecraft:overworld", "minecraft:chest",
            Map.of("minecraft:diamond_pickaxe", 1, "minecraft:stone_pickaxe", 1, "minecraft:cooked_beef", 6), 0,
            false);

    Plan plan(CostProfile cost) {
        return planner.plan(new DependencyPlanner.Target("minecraft:iron_ingot", 3,
                        DependencyPlanner.TargetMode.GATHER),
                PlanContext.ofInventory(Map.of(), 0).withStorage(List.of(entry)).withCost(cost));
    }

    static PlanStep find(Plan p, StepKind kind, String item) {
        for (PlanStep s : p.steps()) {
            if (s.kind() == kind && s.itemId().equals(item)) {
                return s;
            }
        }
        return null;
    }

    static int indexOf(Plan p, StepKind kind, String item) {
        for (int i = 0; i < p.steps().size(); i++) {
            PlanStep s = p.steps().get(i);
            if (s.kind() == kind && s.itemId().equals(item)) {
                return i;
            }
        }
        return -1;
    }

    static int firstMine(Plan p) {
        for (int i = 0; i < p.steps().size(); i++) {
            if (p.steps().get(i).kind() == StepKind.MINE) {
                return i;
            }
        }
        return -1;
    }

    static List<String> signature(Plan p) {
        List<String> out = new ArrayList<>();
        for (PlanStep s : p.steps()) {
            out.add(s.kind() + " " + s.itemId() + " x" + s.count());
        }
        return out;
    }

    @Test
    void allFourProfilesProduceDistinctPlans() {
        Map<CostProfile, List<String>> sigs = new EnumMap<>(CostProfile.class);
        for (CostProfile c : CostProfile.values()) {
            sigs.put(c, signature(plan(c)));
        }
        Set<List<String>> unique = new HashSet<>(sigs.values());
        assertEquals(4, unique.size(), sigs::toString);
    }

    @Test
    void fastestTakesBestStoredPickaxeAndNoPrep() {
        Plan p = plan(CostProfile.FASTEST);
        assertNotNull(find(p, StepKind.FETCH, "minecraft:diamond_pickaxe"), p.describe()::toString);
        assertNull(find(p, StepKind.FETCH, "minecraft:stone_pickaxe"));
        assertNull(find(p, StepKind.FETCH, "minecraft:cooked_beef"));
        assertNull(find(p, StepKind.CRAFT, "minecraft:stone_sword"));
        assertNotNull(find(p, StepKind.MINE, "minecraft:coal"), "fastest fuel is mined coal");
    }

    @Test
    void cheapestKeepsTheDiamondPickaxeInStorage() {
        Plan p = plan(CostProfile.CHEAPEST);
        assertNotNull(find(p, StepKind.FETCH, "minecraft:stone_pickaxe"), p.describe()::toString);
        assertNull(find(p, StepKind.FETCH, "minecraft:diamond_pickaxe"));
        assertNull(find(p, StepKind.CRAFT, "minecraft:stone_sword"));
        assertNull(find(p, StepKind.FETCH, "minecraft:cooked_beef"));
    }

    @Test
    void safestAddsFoodAndSwordBeforeMining() {
        Plan p = plan(CostProfile.SAFEST);
        int food = indexOf(p, StepKind.FETCH, "minecraft:cooked_beef");
        int sword = indexOf(p, StepKind.CRAFT, "minecraft:stone_sword");
        int ore = indexOf(p, StepKind.MINE, "minecraft:raw_iron");
        assertTrue(food >= 0 && sword >= 0 && ore >= 0, p.describe()::toString);
        assertEquals(6, p.steps().get(food).count());
        assertTrue(food < ore && sword < ore, p.describe()::toString);
        assertTrue(food < firstMine(p), "food is fetched before any mining");
        assertNotNull(find(p, StepKind.FETCH, "minecraft:diamond_pickaxe"));
        // the sword shares the plan's single cobblestone trip
        assertEquals(1, p.steps().stream().filter(s -> s.kind() == StepKind.MINE
                && s.itemId().equals("minecraft:cobblestone")).count());
    }

    @Test
    void safestWarnsWithoutFoodAndSkipsPrepWhenNothingIsDug() {
        Plan noFood = planner.plan(new DependencyPlanner.Target("minecraft:iron_ingot", 1,
                DependencyPlanner.TargetMode.GATHER), PlanContext.ofInventory(Map.of(), 0).withCost(CostProfile.SAFEST));
        assertTrue(noFood.warnings().stream().anyMatch(w -> w.contains("no food")), noFood.warnings()::toString);
        assertNotNull(find(noFood, StepKind.CRAFT, "minecraft:stone_sword"));

        Plan logs = planner.plan(new DependencyPlanner.Target("minecraft:oak_log", 4,
                DependencyPlanner.TargetMode.GATHER), PlanContext.ofInventory(Map.of(), 0).withCost(CostProfile.SAFEST));
        assertNull(find(logs, StepKind.CRAFT, "minecraft:stone_sword"), "hand gathering needs no prep");

        Plan armed = planner.plan(new DependencyPlanner.Target("minecraft:iron_ingot", 1,
                        DependencyPlanner.TargetMode.GATHER),
                PlanContext.ofInventory(Map.of("minecraft:iron_sword", 1, "minecraft:bread", 10), 0)
                        .withCost(CostProfile.SAFEST));
        assertNull(find(armed, StepKind.CRAFT, "minecraft:stone_sword"));
        assertFalse(armed.warnings().stream().anyMatch(w -> w.contains("no food")));
    }

    @Test
    void preserveTerrainBurnsPlanksAndUsesLowestPickaxe() {
        Plan p = plan(CostProfile.PRESERVE_TERRAIN);
        assertNull(find(p, StepKind.MINE, "minecraft:coal"), p.describe()::toString);
        assertNotNull(find(p, StepKind.FETCH, "minecraft:stone_pickaxe"));
        assertNull(find(p, StepKind.CRAFT, "minecraft:stone_sword"));
    }

    @Test
    void everyProfileHasAPreviewNote() {
        Set<String> notes = new HashSet<>();
        for (CostProfile c : CostProfile.values()) {
            notes.add(DependencyPlanner.profileNote(c));
        }
        assertEquals(4, notes.size());
        assertTrue(DependencyPlanner.profileNote(CostProfile.SAFEST).contains("Y=" + DependencyPlanner.SAFE_MIN_Y));
    }
}
