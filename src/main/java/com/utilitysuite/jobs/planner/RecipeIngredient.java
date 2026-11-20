package com.utilitysuite.jobs.planner;

import java.util.List;

/**
 * One ingredient of a recipe: {@code count} items, each of which may be any of {@code options} (a tag such as
 * {@code #minecraft:planks} expanded to item ids, in registry order).
 */
public record RecipeIngredient(List<String> options, int count) {
    public RecipeIngredient {
        options = List.copyOf(options);
        if (options.isEmpty() || count <= 0) {
            throw new IllegalArgumentException("Empty ingredient");
        }
    }

    public static RecipeIngredient of(int count, String... options) {
        String[] q = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            q[i] = Items.qualify(options[i]);
        }
        return new RecipeIngredient(List.of(q), count);
    }
}
