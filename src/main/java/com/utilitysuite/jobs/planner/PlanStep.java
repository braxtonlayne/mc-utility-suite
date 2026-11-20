package com.utilitysuite.jobs.planner;

import java.util.List;

/**
 * One immutable plan step.
 *
 * @param itemId       item produced or collected
 * @param count        items wanted from this step
 * @param crafts       craft/smelt operations (recipe executions); 0 for other kinds
 * @param recipeId     recipe to place, or null
 * @param blockIds     candidate blocks for GATHER/MINE (all sources of the item), empty otherwise
 * @param requiredTier pickaxe tier needed (MINE)
 * @param storagePos   packed container position for FETCH, {@link Long#MIN_VALUE} otherwise
 * @param milestone    grouped label shown in "Show Plan" (several steps can share one)
 */
public record PlanStep(StepKind kind, String itemId, int count, int crafts, String recipeId, List<String> blockIds,
                       int requiredTier, long storagePos, String dimension, StepRole role, String label,
                       String milestone) {

    public static final long NO_POS = Long.MIN_VALUE;

    public PlanStep {
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        milestone = milestone == null ? label : milestone;
    }

    public PlanStep withMilestone(String m) {
        return new PlanStep(kind, itemId, count, crafts, recipeId, blockIds, requiredTier, storagePos, dimension, role,
                label, m);
    }

    /** "Mine 32 Diamonds", "Craft 4 Planks (1x)" style detail line. */
    public String describe() {
        return switch (kind) {
            case CRAFT, SMELT -> label + " (" + count + " " + itemId + ", " + crafts + "x " + recipeId + ")";
            case FETCH -> label + " (" + count + " " + itemId + " from container)";
            case GATHER, MINE -> label + " (" + count + " " + itemId + " from " + String.join("/", blockIds)
                    + (requiredTier > 0 ? ", tier " + requiredTier : "") + ")";
            default -> label;
        };
    }
}
