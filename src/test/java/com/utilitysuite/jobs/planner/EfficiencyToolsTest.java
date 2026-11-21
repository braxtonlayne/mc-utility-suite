package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.CostProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pro-player defaults: a shovel before a lot of dirt, an axe before a lot of logs. */
class EfficiencyToolsTest {

    private static DependencyPlanner.Target gather(String item, int n) {
        return new DependencyPlanner.Target("minecraft:" + item, n, DependencyPlanner.TargetMode.GATHER);
    }

    private static int indexOf(Plan p, StepKind kind, String item) {
        List<PlanStep> s = p.steps();
        for (int i = 0; i < s.size(); i++) {
            if (s.get(i).kind() == kind && s.get(i).itemId().equals(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int digIndex(Plan p, String item) {
        int g = indexOf(p, StepKind.GATHER, item);
        return g >= 0 ? g : indexOf(p, StepKind.MINE, item);
    }

    @Test
    void lotsOfDirtGetsAShovelFirst() {
        DependencyPlanner planner = VanillaRecipes.planner();
        Plan p = planner.plan(gather("dirt", 64), PlanContext.ofInventory(Map.of(), 0).withReturn(false));
        int shovel = indexOf(p, StepKind.CRAFT, "minecraft:wooden_shovel");
        assertTrue(shovel >= 0, "crafts a shovel: " + p.steps());
        assertTrue(shovel < digIndex(p, "minecraft:dirt"), "before the digging");
        assertTrue(p.warnings().stream().anyMatch(w -> w.startsWith("Pro habit")), "says why");
    }

    @Test
    void aFewBlocksAreNotWorthATool() {
        Plan p = VanillaRecipes.planner().plan(gather("dirt", 8),
                PlanContext.ofInventory(Map.of(), 0).withReturn(false));
        assertEquals(-1, indexOf(p, StepKind.CRAFT, "minecraft:wooden_shovel"));
    }

    @Test
    void withAPickaxeAtHandItMakesAStoneShovel() {
        Plan p = VanillaRecipes.planner().plan(gather("sand", 40),
                PlanContext.ofInventory(Map.of("minecraft:wooden_pickaxe", 1), 1).withReturn(false));
        assertTrue(indexOf(p, StepKind.CRAFT, "minecraft:stone_shovel") >= 0, p.steps().toString());
    }

    @Test
    void carryingOneAlreadyMeansNoNewTool() {
        Plan p = VanillaRecipes.planner().plan(gather("dirt", 64),
                PlanContext.ofInventory(Map.of("minecraft:iron_shovel", 1), 0).withReturn(false));
        assertEquals(-1, indexOf(p, StepKind.CRAFT, "minecraft:wooden_shovel"));
        assertEquals(-1, indexOf(p, StepKind.CRAFT, "minecraft:stone_shovel"));
    }

    @Test
    void cheapestProfileAndOffSettingSkipIt() {
        Plan cheap = VanillaRecipes.planner().plan(gather("dirt", 64),
                PlanContext.ofInventory(Map.of(), 0).withReturn(false).withCost(CostProfile.CHEAPEST));
        assertEquals(-1, indexOf(cheap, StepKind.CRAFT, "minecraft:wooden_shovel"));
        DependencyPlanner off = VanillaRecipes.planner();
        off.setEfficiencyTools(EfficiencyTools.Settings.OFF);
        Plan p = off.plan(gather("dirt", 64), PlanContext.ofInventory(Map.of(), 0).withReturn(false));
        assertEquals(-1, indexOf(p, StepKind.CRAFT, "minecraft:wooden_shovel"));
    }

    @Test
    void aBigLogHaulGetsAnAxe() {
        Plan p = VanillaRecipes.planner().plan(gather("oak_log", 32),
                PlanContext.ofInventory(Map.of(), 0).withReturn(false));
        int axe = indexOf(p, StepKind.CRAFT, "minecraft:wooden_axe");
        assertTrue(axe >= 0, p.steps().toString());
    }

    @Test
    void customThresholds() {
        DependencyPlanner planner = VanillaRecipes.planner();
        planner.setEfficiencyTools(new EfficiencyTools.Settings(true, 8, 1000));
        Plan p = planner.plan(gather("gravel", 10), PlanContext.ofInventory(Map.of(), 0).withReturn(false));
        assertTrue(indexOf(p, StepKind.CRAFT, "minecraft:wooden_shovel") >= 0);
        assertFalse(EfficiencyTools.isTool("minecraft:iron_pickaxe", EfficiencyTools.Kind.AXE));
    }
}
