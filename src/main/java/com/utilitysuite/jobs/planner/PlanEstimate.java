package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * Rough duration and required-tools summary of a {@link Plan}, for the dry-run preview. Pure arithmetic over the
 * steps; the numbers are order-of-magnitude guesses (per-step weights), not a simulation. Runs off-thread with the
 * rest of the preview.
 */
public final class PlanEstimate {

    /** Seconds per mined item (finding, pathing and breaking). */
    static final int MINE_PER_ITEM = 6;
    static final int GATHER_PER_ITEM = 2;
    static final int CRAFT_PER_OP = 1;
    /** A furnace works through one item in 10 s. */
    static final int SMELT_PER_OP = 10;
    static final int FETCH = 20;
    /** Place, open, take, close, break and pick up a shulker box. */
    static final int SHULKER_RESTOCK = 12;
    static final int RETURN = 40;
    static final int TRAVEL = 45;
    static final int BUILD = 120;

    private PlanEstimate() {
    }

    /** Sum of the step weights in seconds (saturating). */
    public static int seconds(List<PlanStep> steps) {
        long total = 0;
        for (int i = 0, n = steps.size(); i < n; i++) {
            total += stepSeconds(steps.get(i));
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    static long stepSeconds(PlanStep s) {
        return switch (s.kind()) {
            case MINE -> (long) Math.max(1, s.count()) * MINE_PER_ITEM;
            case GATHER -> (long) Math.max(1, s.count()) * GATHER_PER_ITEM;
            case CRAFT -> (long) Math.max(1, s.crafts()) * CRAFT_PER_OP;
            case SMELT -> (long) Math.max(1, s.crafts()) * SMELT_PER_OP;
            case FETCH -> FETCH;
            case SHULKER_RESTOCK -> SHULKER_RESTOCK;
            case RETURN -> RETURN;
            case TRAVEL -> TRAVEL;
            case BUILD -> BUILD;
            case FURNACE_LOAD, FURNACE_COLLECT, FILL_BOTTLES -> FETCH;
            case BREW -> (long) Math.max(1, s.blockIds().size() - 1) * 20 + FETCH;
        };
    }

    /**
     * Tools the plan needs in hand, as item ids: the pickaxe for the highest tier of any MINE step, and an axe or
     * shovel (any tier) when gathering logs or soft blocks. Tools the plan crafts itself are still listed.
     */
    public static List<String> requiredTools(List<PlanStep> steps) {
        int tier = 0;
        boolean axe = false;
        boolean shovel = false;
        for (int i = 0, n = steps.size(); i < n; i++) {
            PlanStep s = steps.get(i);
            if (s.kind() == StepKind.MINE) {
                tier = Math.max(tier, s.requiredTier());
            } else if (s.kind() == StepKind.GATHER) {
                for (String b : s.blockIds()) {
                    if (b.endsWith("_log") || b.endsWith("_stem")) {
                        axe = true;
                    } else if (b.endsWith("dirt") || b.endsWith("sand") || b.endsWith("gravel")
                            || b.endsWith("clay")) {
                        shovel = true;
                    }
                }
            }
        }
        List<String> out = new ArrayList<>(3);
        if (tier > 0) {
            out.add(ToolTiers.pickaxeForTier(tier));
        }
        if (axe) {
            out.add("minecraft:wooden_axe");
        }
        if (shovel) {
            out.add("minecraft:wooden_shovel");
        }
        return out;
    }
}
