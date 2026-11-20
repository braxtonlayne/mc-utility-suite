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
 */
public record PlanPreview(GoalRequest request, String interpretedTarget, List<String> steps,
                          Map<String, Integer> missingMaterials, List<String> warnings) {

    public PlanPreview {
        steps = List.copyOf(steps);
        missingMaterials = Map.copyOf(missingMaterials);
        warnings = List.copyOf(warnings);
    }
}
