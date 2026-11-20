package com.utilitysuite.jobs.planner;

import java.util.List;

/**
 * Engine-agnostic recipe snapshot.
 *
 * @param id         recipe id ("minecraft:iron_pickaxe"), used by the executor to place the recipe
 * @param needsTable crafting recipe that does not fit the 2x2 inventory grid
 */
public record PlannerRecipe(String id, RecipeKind kind, String output, int outputCount,
                            List<RecipeIngredient> ingredients, boolean needsTable) {
    public PlannerRecipe {
        ingredients = List.copyOf(ingredients);
        if (outputCount <= 0) {
            throw new IllegalArgumentException("outputCount <= 0 for " + id);
        }
    }

    /** Station item needed, or null. */
    public String station() {
        return kind.station(needsTable);
    }

    public static PlannerRecipe crafting(String output, int count, boolean needsTable, RecipeIngredient... ingredients) {
        String out = Items.qualify(output);
        return new PlannerRecipe(out, RecipeKind.CRAFTING, out, count, List.of(ingredients), needsTable);
    }

    public static PlannerRecipe cooking(RecipeKind kind, String output, String input) {
        String out = Items.qualify(output);
        String in = Items.qualify(input);
        return new PlannerRecipe(out + "_from_" + kind.name().toLowerCase() + "_" + Items.path(in), kind, out, 1,
                List.of(RecipeIngredient.of(1, in)), false);
    }
}
