package com.utilitysuite.jobs.planner;

/** What a plan step asks the executor to do. */
public enum StepKind {
    /** Break blocks by hand or with any tool (trees). */
    GATHER,
    /** Break blocks with a pickaxe of at least {@code requiredTier}, counted by item yield. */
    MINE,
    /** Craft in the inventory grid or at a crafting table. */
    CRAFT,
    /** Smelt/blast/smoke in a furnace-type station. */
    SMELT,
    /** Withdraw from a known container. */
    FETCH,
    /** Return to the start/base and deposit. */
    RETURN,
    /** Path to a coordinate. */
    TRAVEL,
    /**
     * Place a carried shulker box next to the player, open it, take {@code count} of the item, close it, break it and
     * pick it back up. {@code blockIds} holds the shulker box item id the planner expected to unpack.
     */
    SHULKER_RESTOCK,
    /** Hand off to the schematic toolkit. */
    BUILD,
    /**
     * Load one remembered furnace-type station at {@code storagePos}: {@code count} of {@code itemId} into the input
     * slot and {@code crafts} of {@code blockIds[0]} (when present) into the fuel slot (see FurnaceBatchPlanner).
     */
    FURNACE_LOAD,
    /** Take the smelted {@code itemId} ({@code count}) out of the station at {@code storagePos}, waiting until ready. */
    FURNACE_COLLECT,
    /**
     * One brew at the brewing stand at {@code storagePos}: {@code count} bottles of {@code blockIds[0]} (potion key),
     * then each ingredient in {@code blockIds[1..]} in order, waiting for every brew; blaze powder is loaded as needed.
     */
    BREW,
    /** Fill {@code count} glass bottles with water at a nearby water source or water cauldron. */
    FILL_BOTTLES,
    /** Open the crafter at {@code storagePos} and save its slot locks and items as template {@code itemId} (name). */
    CRAFTER_SAVE,
    /**
     * Open the crafter at {@code storagePos} and apply the template encoded in {@code blockIds[0]}
     * ({@link CrafterTemplate#encode}): clear wrong items, toggle slot locks, seed items.
     */
    CRAFTER_APPLY,
    /**
     * Storage hall sort: open the container at {@code storagePos} and take exact slots. {@code blockIds} holds one
     * {@code "slot|count|item|variant"} entry per slot (see {@code HallSortSteps}); each slot's item and stored
     * enchantments are checked before it is clicked. {@code crafts} 1 = merge partial stacks before closing.
     */
    HALL_TAKE,
    /**
     * Storage hall sort: put what the last HALL_TAKE carried into the container at {@code storagePos}.
     * {@code blockIds} holds {@code "count|item|variant"} entries. {@code crafts} 1 = merge partial stacks after.
     */
    HALL_PUT,
    /** Storage hall sort: open the container at {@code storagePos} and merge its partial stacks with slot clicks. */
    HALL_MERGE
}
