package com.utilitysuite.core.api;

import java.util.List;
import java.util.Map;

/**
 * Dry-run result shown before a goal executes.
 *
 * @param interpretedTarget e.g. "32 x minecraft:diamond"
 * @param steps             ordered human-readable plan steps (bootstrap steps first)
 * @param missingMaterials  item id -> count still needed after inventory and known storage
 * @param warnings          parser/planner warnings (ambiguous item, no fuel source, ...)
 * @param estimatedSeconds  rough duration from per-step weights; 0 when unknown
 * @param requiredTools     item ids of the tools the plan needs in hand (pickaxe tier, axe, shovel)
 */
public record PlanPreview(GoalRequest request, String interpretedTarget, List<String> steps,
                          Map<String, Integer> missingMaterials, List<String> warnings, int estimatedSeconds,
                          List<String> requiredTools) {

    public PlanPreview {
        steps = List.copyOf(steps);
        missingMaterials = Map.copyOf(missingMaterials);
        warnings = List.copyOf(warnings);
        requiredTools = List.copyOf(requiredTools);
    }

    /** A preview without an estimate. */
    public PlanPreview(GoalRequest request, String interpretedTarget, List<String> steps,
                       Map<String, Integer> missingMaterials, List<String> warnings) {
        this(request, interpretedTarget, steps, missingMaterials, warnings, 0, List.of());
    }
}
