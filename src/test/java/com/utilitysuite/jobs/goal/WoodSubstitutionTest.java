package com.utilitysuite.jobs.goal;

import com.utilitysuite.jobs.planner.DependencyPlanner;
import com.utilitysuite.jobs.planner.Plan;
import com.utilitysuite.jobs.planner.PlanContext;
import com.utilitysuite.jobs.planner.PlanStep;
import com.utilitysuite.jobs.planner.StepKind;
import com.utilitysuite.jobs.planner.StepRole;
import com.utilitysuite.jobs.planner.VanillaRecipes;
import com.utilitysuite.jobs.planner.WoodTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-20 playtest: "gather 4 coal" from an empty inventory at a jungle/birch spawn mined non-oak logs, then failed
 * "Missing ingredients" crafting oak planks. Wood steps now accept any log and the planks craft follows the logs on
 * hand.
 */
class WoodSubstitutionTest {

    static PlanStep logStep(Plan p) {
        for (PlanStep s : p.steps()) {
            if (s.kind() == StepKind.GATHER && WoodTypes.isLog(s.itemId())) {
                return s;
            }
        }
        return null;
    }

    static PlanStep planksStep(Plan p) {
        for (PlanStep s : p.steps()) {
            if (s.kind() == StepKind.CRAFT && s.itemId().endsWith("_planks")) {
                return s;
            }
        }
        return null;
    }

    @Test
    void freshSpawnWoodStepFellsAnyTree() {
        Plan p = VanillaRecipes.planner().plan(new DependencyPlanner.Target("minecraft:coal", 4,
                DependencyPlanner.TargetMode.GATHER), PlanContext.ofInventory(Map.of(), 0));
        PlanStep wood = logStep(p);
        assertNotNull(wood, p.describe()::toString);
        assertTrue(wood.blockIds().containsAll(List.of("minecraft:oak_log", "minecraft:birch_log",
                "minecraft:jungle_log")), wood.blockIds()::toString);
    }

    @Test
    void explicitLogTargetStaysThatWood() {
        Plan p = VanillaRecipes.planner().plan(new DependencyPlanner.Target("minecraft:oak_log", 4,
                DependencyPlanner.TargetMode.GATHER), PlanContext.ofInventory(Map.of(), 0));
        assertEquals(List.of("minecraft:oak_log"), logStep(p).blockIds());
    }

    @Test
    void anyLogCountsTowardTheWoodStep() {
        Plan p = VanillaRecipes.planner().plan(new DependencyPlanner.Target("minecraft:coal", 4,
                DependencyPlanner.TargetMode.GATHER), PlanContext.ofInventory(Map.of(), 0));
        Fakes.Inventory inv = new Fakes.Inventory();
        inv.add("minecraft:birch_log", 2);
        inv.add("minecraft:jungle_log", 1);
        inv.add("minecraft:stick", 5);
        assertEquals(3, JobExecutor.stepCount(inv, logStep(p)));
        PlanStep coal = p.steps().stream().filter(s -> s.itemId().equals("minecraft:coal")
                && s.kind() == StepKind.MINE).findFirst().orElseThrow();
        assertEquals(0, JobExecutor.stepCount(inv, coal));
    }

    @Test
    void planksCraftFollowsLogsOnHand() {
        PlanStep oak = new PlanStep(StepKind.CRAFT, "minecraft:oak_planks", 8, 2, "minecraft:oak_planks", List.of(), 0,
                PlanStep.NO_POS, "minecraft:overworld", StepRole.PRIMARY, "Planks", null);
        Fakes.Inventory inv = new Fakes.Inventory();
        inv.add("minecraft:birch_log", 1);
        inv.add("minecraft:jungle_log", 3);
        List<PlanStep> sub = JobExecutor.planksForLogsOnHand(inv, oak);
        assertNotNull(sub);
        assertEquals(2, sub.size());
        assertEquals("minecraft:birch_planks", sub.get(0).itemId());
        assertEquals("minecraft:birch_planks", sub.get(0).recipeId());
        assertEquals(1, sub.get(0).crafts());
        assertEquals(4, sub.get(0).count());
        assertEquals("minecraft:jungle_planks", sub.get(1).itemId());
        assertEquals(1, sub.get(1).crafts());

        // planned wood on hand first, topped up by another wood
        inv.add("minecraft:oak_log", 1);
        List<PlanStep> mixed = JobExecutor.planksForLogsOnHand(inv, oak);
        assertEquals("minecraft:oak_planks", mixed.get(0).itemId());
        assertEquals(1, mixed.get(0).crafts());
        assertEquals(1, mixed.get(1).crafts());

        inv.add("minecraft:oak_log", 1);
        assertNull(JobExecutor.planksForLogsOnHand(inv, oak), "enough oak: unchanged");

        Fakes.Inventory empty = new Fakes.Inventory();
        assertNull(JobExecutor.planksForLogsOnHand(empty, oak), "no logs at all: normal failure/replan path");
    }

    @Test
    void woodNames() {
        assertEquals("minecraft:crimson_planks", WoodTypes.planksFor("minecraft:crimson_stem"));
        assertEquals("minecraft:crimson_stem", WoodTypes.logFor("minecraft:crimson_planks"));
        assertEquals("minecraft:dark_oak_log", WoodTypes.logFor("minecraft:dark_oak_planks"));
        assertNull(WoodTypes.logFor("minecraft:bamboo_planks"));
    }
}
