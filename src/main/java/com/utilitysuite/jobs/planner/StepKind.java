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
    BUILD
}
