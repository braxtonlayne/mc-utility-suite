package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An ordered, immutable plan. Steps are in execution order: every step's prerequisites come before it.
 *
 * @param missing  raw materials that must be gathered (mined/gathered counts plus unresolvable items)
 * @param surplus  items the plan produces beyond what it consumes (credited to later queued jobs)
 */
public record Plan(List<PlanStep> steps, Map<String, Integer> missing, List<String> warnings,
                   Map<String, Integer> surplus) {

    public Plan {
        steps = List.copyOf(steps);
        missing = Map.copyOf(missing);
        warnings = List.copyOf(warnings);
        surplus = Map.copyOf(surplus);
    }

    public static final Plan EMPTY = new Plan(List.of(), Map.of(), List.of(), Map.of());

    /** Grouped milestone labels in order, consecutive duplicates collapsed. */
    public List<String> milestones() {
        List<String> out = new ArrayList<>();
        for (PlanStep s : steps) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(s.milestone())) {
                out.add(s.milestone());
            }
        }
        return out;
    }

    /** One detail line per step for the "Show Plan" view. */
    public List<String> describe() {
        List<String> out = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            out.add((i + 1) + ". " + steps.get(i).describe());
        }
        return out;
    }
}
