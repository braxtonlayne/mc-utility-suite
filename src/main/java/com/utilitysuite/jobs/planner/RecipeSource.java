package com.utilitysuite.jobs.planner;

import java.util.List;

/**
 * Where the planner looks up recipes. The Minecraft adapter snapshots the client's recipe manager; tests use a fixture.
 * Implementations must be immutable or thread-safe: planning runs on a worker thread.
 */
public interface RecipeSource {
    /** Recipes producing the item, in a stable order. Empty when none. */
    List<PlannerRecipe> recipesFor(String itemId);
}
