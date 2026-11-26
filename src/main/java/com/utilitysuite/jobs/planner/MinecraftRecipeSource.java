package com.utilitysuite.jobs.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshots the recipes the client can use ({@link McRecipeCatalog}: crafting, smelting, blasting, smoking) into an
 * immutable {@link MapRecipeSource}. Built on the client thread at login and when recipes are re-synced; planning then
 * reads the snapshot from the worker thread. Special (dynamic) recipes are skipped.
 * <p>
 * 26.3: recipes are server-side. With the integrated server (single player) every recipe is read as before; on a
 * remote server only recipes a server mod synced, or else the recipe book's unlocked recipes, are known.
 */
public final class MinecraftRecipeSource {

    private MinecraftRecipeSource() {
    }

    public static MapRecipeSource snapshot(Minecraft mc) {
        if (mc.level == null) {
            return MapRecipeSource.EMPTY;
        }
        ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
        List<PlannerRecipe> out = new ArrayList<>();
        for (McRecipeCatalog.Entry e : McRecipeCatalog.all(mc)) {
            PlannerRecipe r = convert(e, ctx);
            if (r != null) {
                out.add(r);
            }
        }
        return new MapRecipeSource(out);
    }

    private static PlannerRecipe convert(McRecipeCatalog.Entry e, ContextMap ctx) {
        ItemStack result = e.display().result().resolveForFirstStack(ctx);
        if (result.isEmpty()) {
            return null;
        }
        List<SlotDisplay> slots = McRecipeCatalog.ingredientSlots(e.display());
        if (slots == null) {
            return null;
        }
        // group identical ingredient slots: 3 planks slots -> one ingredient x3
        Map<List<String>, Integer> grouped = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) {
                continue;
            }
            List<String> options = McRecipeCatalog.optionIds(slot, ctx);
            if (options.isEmpty()) {
                return null; // an ingredient nothing satisfies (empty tag): the old "incomplete" recipe
            }
            grouped.merge(List.copyOf(options), 1, Integer::sum);
        }
        if (grouped.isEmpty()) {
            return null;
        }
        List<RecipeIngredient> ings = new ArrayList<>(grouped.size());
        grouped.forEach((opts, n) -> ings.add(new RecipeIngredient(opts, n)));
        boolean needsTable = e.kind() == RecipeKind.CRAFTING && e.needsTable();
        return new PlannerRecipe(e.id(), e.kind(), BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
                result.getCount(), ings, needsTable);
    }
}
