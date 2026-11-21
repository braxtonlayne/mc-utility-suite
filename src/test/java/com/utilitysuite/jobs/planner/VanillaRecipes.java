package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.List;

import static com.utilitysuite.jobs.planner.PlannerRecipe.cooking;
import static com.utilitysuite.jobs.planner.PlannerRecipe.crafting;
import static com.utilitysuite.jobs.planner.RecipeIngredient.of;

/** Test fixture: the vanilla recipes the planner tests need, including cycles (blocks <-> ingots, nuggets). */
public final class VanillaRecipes {

    public static final String[] PLANKS = {"oak_planks", "spruce_planks", "birch_planks"};
    public static final String[] STONE_TOOL = {"cobblestone", "cobbled_deepslate", "blackstone"};

    private VanillaRecipes() {
    }

    public static MapRecipeSource create() {
        List<PlannerRecipe> r = new ArrayList<>();
        r.add(crafting("oak_planks", 4, false, of(1, "oak_log")));
        r.add(crafting("spruce_planks", 4, false, of(1, "spruce_log")));
        r.add(crafting("birch_planks", 4, false, of(1, "birch_log")));
        r.add(crafting("crafting_table", 1, false, of(4, PLANKS)));
        r.add(crafting("stick", 4, false, of(2, PLANKS)));
        r.add(crafting("wooden_pickaxe", 1, true, of(3, PLANKS), of(2, "stick")));
        r.add(crafting("stone_pickaxe", 1, true, of(3, STONE_TOOL), of(2, "stick")));
        r.add(crafting("iron_pickaxe", 1, true, of(3, "iron_ingot"), of(2, "stick")));
        r.add(crafting("diamond_pickaxe", 1, true, of(3, "diamond"), of(2, "stick")));
        r.add(crafting("stone_sword", 1, true, of(2, STONE_TOOL), of(1, "stick")));
        r.add(crafting("wooden_sword", 1, true, of(2, PLANKS), of(1, "stick")));
        r.add(crafting("wooden_shovel", 1, true, of(1, PLANKS), of(2, "stick")));
        r.add(crafting("stone_shovel", 1, true, of(1, STONE_TOOL), of(2, "stick")));
        r.add(crafting("wooden_axe", 1, true, of(3, PLANKS), of(2, "stick")));
        r.add(crafting("stone_axe", 1, true, of(3, STONE_TOOL), of(2, "stick")));
        r.add(crafting("furnace", 1, true, of(8, STONE_TOOL)));
        r.add(crafting("chest", 1, true, of(8, PLANKS)));
        r.add(crafting("hopper", 1, true, of(5, "iron_ingot"), of(1, "chest")));
        r.add(crafting("torch", 4, false, of(1, "coal", "charcoal"), of(1, "stick")));
        r.add(cooking(RecipeKind.SMELTING, "iron_ingot", "raw_iron"));
        r.add(cooking(RecipeKind.SMELTING, "iron_ingot", "iron_ore"));
        r.add(cooking(RecipeKind.BLASTING, "iron_ingot", "raw_iron"));
        r.add(crafting("iron_ingot", 9, false, of(1, "iron_block")));
        r.add(crafting("iron_ingot", 1, true, of(9, "iron_nugget")));
        r.add(crafting("iron_block", 1, true, of(9, "iron_ingot")));
        r.add(cooking(RecipeKind.SMELTING, "iron_nugget", "iron_pickaxe"));
        r.add(crafting("diamond", 9, false, of(1, "diamond_block")));
        r.add(crafting("diamond_block", 1, true, of(9, "diamond")));
        r.add(crafting("coal", 9, false, of(1, "coal_block")));
        r.add(crafting("coal_block", 1, true, of(9, "coal")));
        r.add(cooking(RecipeKind.SMELTING, "charcoal", "oak_log"));
        r.add(crafting("blast_furnace", 1, true, of(5, "iron_ingot"), of(1, "furnace"), of(3, "smooth_stone")));
        r.add(cooking(RecipeKind.SMELTING, "smooth_stone", "stone"));
        r.add(cooking(RecipeKind.SMELTING, "stone", "cobblestone"));
        return new MapRecipeSource(r);
    }

    public static DependencyPlanner planner() {
        return new DependencyPlanner(create(), HarvestTable.vanilla(), FuelTable.vanilla());
    }
}
