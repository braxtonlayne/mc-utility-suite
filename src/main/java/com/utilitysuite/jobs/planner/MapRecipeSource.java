package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable recipe index keyed by output item, insertion order preserved. */
public final class MapRecipeSource implements RecipeSource {

    public static final MapRecipeSource EMPTY = new MapRecipeSource(List.of());

    private final Map<String, List<PlannerRecipe>> byOutput;
    private final int size;

    public MapRecipeSource(Collection<PlannerRecipe> recipes) {
        Map<String, List<PlannerRecipe>> m = new LinkedHashMap<>();
        for (PlannerRecipe r : recipes) {
            m.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
        }
        Map<String, List<PlannerRecipe>> frozen = new LinkedHashMap<>();
        m.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        this.byOutput = frozen;
        this.size = recipes.size();
    }

    @Override
    public List<PlannerRecipe> recipesFor(String itemId) {
        return byOutput.getOrDefault(itemId, List.of());
    }

    public int size() {
        return size;
    }
}
