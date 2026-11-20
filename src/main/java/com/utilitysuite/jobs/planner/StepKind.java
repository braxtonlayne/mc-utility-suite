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
    /** Hand off to the schematic toolkit. */
    BUILD
}
