package com.utilitysuite.jobs.planner;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshots the client's recipe manager (crafting, smelting, blasting, smoking) into an immutable
 * {@link MapRecipeSource}. Built on the client thread at login and when recipes are re-synced; planning then reads the
 * snapshot from the worker thread. Special (dynamic) recipes are skipped.
 */
public final class MinecraftRecipeSource {

    private MinecraftRecipeSource() {
    }

    public static MapRecipeSource snapshot(RecipeManager manager, HolderLookup.Provider registries) {
        List<PlannerRecipe> out = new ArrayList<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            PlannerRecipe r = convert(holder, registries);
            if (r != null) {
                out.add(r);
            }
        }
        return new MapRecipeSource(out);
    }

    private static PlannerRecipe convert(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();
        RecipeKind kind = kindOf(recipe.getType());
        if (kind == null || recipe.isSpecial() || recipe.isIncomplete()) {
            return null;
        }
        ItemStack result = recipe.getResultItem(registries);
        if (result.isEmpty()) {
            return null;
        }
        // group identical ingredient slots: 3 planks slots -> one ingredient x3
        Map<List<String>, Integer> grouped = new LinkedHashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) {
                continue;
            }
            List<String> options = new ArrayList<>();
            for (ItemStack s : ing.getItems()) {
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                if (!options.contains(id)) {
                    options.add(id);
                }
            }
            if (options.isEmpty()) {
                return null;
            }
            grouped.merge(List.copyOf(options), 1, Integer::sum);
        }
        if (grouped.isEmpty()) {
            return null;
        }
        List<RecipeIngredient> ings = new ArrayList<>(grouped.size());
        grouped.forEach((opts, n) -> ings.add(new RecipeIngredient(opts, n)));
        boolean needsTable = kind == RecipeKind.CRAFTING && !recipe.canCraftInDimensions(2, 2);
        return new PlannerRecipe(holder.id().toString(), kind,
                BuiltInRegistries.ITEM.getKey(result.getItem()).toString(), result.getCount(), ings, needsTable);
    }

    private static RecipeKind kindOf(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) {
            return RecipeKind.CRAFTING;
        }
        if (type == RecipeType.SMELTING) {
            return RecipeKind.SMELTING;
        }
        if (type == RecipeType.BLASTING) {
            return RecipeKind.BLASTING;
        }
        if (type == RecipeType.SMOKING) {
            return RecipeKind.SMOKING;
        }
        // Stonecutting is understood by the planner but left out of the in-game snapshot: the stonecutter has no
        // recipe-book placement, so the executor cannot run it yet.
        return null;
    }
}
