package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.PlanPreview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Per-step weights and the required-tools summary of a plan. */
class PlanEstimateTest {

    private static PlanStep step(StepKind kind, String item, int count, int crafts, List<String> blocks, int tier) {
        return new PlanStep(kind, item, count, crafts, null, blocks, tier, PlanStep.NO_POS, "minecraft:overworld",
                StepRole.TARGET, kind.name(), null);
    }

    @Test
    void sumsStepWeights() {
        List<PlanStep> steps = List.of(
                step(StepKind.MINE, "minecraft:diamond", 4, 0, List.of("minecraft:diamond_ore"), 3),
                step(StepKind.CRAFT, "minecraft:stick", 4, 2, List.of(), 0),
                step(StepKind.SMELT, "minecraft:iron_ingot", 3, 3, List.of(), 0),
                step(StepKind.RETURN, "", 0, 0, List.of(), 0));
        assertEquals(4 * PlanEstimate.MINE_PER_ITEM + 2 * PlanEstimate.CRAFT_PER_OP + 3 * PlanEstimate.SMELT_PER_OP
                + PlanEstimate.RETURN, PlanEstimate.seconds(steps));
        assertEquals(0, PlanEstimate.seconds(List.of()));
    }

    @Test
    void requiredToolsFollowTheHighestTierAndSoftGathers() {
        List<PlanStep> steps = List.of(
                step(StepKind.MINE, "minecraft:iron_ore", 3, 0, List.of("minecraft:iron_ore"), 2),
                step(StepKind.MINE, "minecraft:diamond", 4, 0, List.of("minecraft:diamond_ore"), 3),
                step(StepKind.GATHER, "minecraft:oak_log", 6, 0, List.of("minecraft:oak_log"), 0),
                step(StepKind.GATHER, "minecraft:gravel", 64, 0, List.of("minecraft:gravel"), 0));
        assertEquals(List.of("minecraft:iron_pickaxe", "minecraft:wooden_axe", "minecraft:wooden_shovel"),
                PlanEstimate.requiredTools(steps));
        assertTrue(PlanEstimate.requiredTools(List.of()).isEmpty());
    }

    @Test
    void previewKeepsTheFiveArgumentConstructor() {
        PlanPreview p = new PlanPreview(null, "t", List.of(), java.util.Map.of(), List.of());
        assertEquals(0, p.estimatedSeconds());
        assertTrue(p.requiredTools().isEmpty());
    }
}
