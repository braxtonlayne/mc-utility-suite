package com.utilitysuite.jobs.planner;

/** Recipe families the planner understands. Mirrors the vanilla recipe types without depending on them. */
public enum RecipeKind {
    CRAFTING,
    SMELTING,
    BLASTING,
    SMOKING,
    STONECUTTING;

    public boolean isCooking() {
        return this == SMELTING || this == BLASTING || this == SMOKING;
    }

    /** Station block item the recipe needs, or {@code null} (2x2 crafting in the inventory grid). */
    public String station(boolean needsTable) {
        return switch (this) {
            case CRAFTING -> needsTable ? Items.CRAFTING_TABLE : null;
            case SMELTING -> Items.FURNACE;
            case BLASTING -> Items.BLAST_FURNACE;
            case SMOKING -> Items.SMOKER;
            case STONECUTTING -> Items.STONECUTTER;
        };
    }
}
