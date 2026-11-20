package com.utilitysuite.tools.rules;

/** What rule conditions can read. Implemented by a Minecraft adapter and by test fakes. Client thread only. */
public interface RuleContext {

    /** Share of the 36 inventory slots holding anything, 0-100. */
    double inventoryFullPct();

    double health();

    /** Food level 0-20. */
    double hunger();

    /** Day time 0-23999. */
    long timeOfDay();

    /** Active job status name ({@code JobStatus.name()}) or {@code "NONE"}. */
    String jobStatus();

    /** Count of an item id ("minecraft:coal") across the inventory. */
    int itemCount(String itemId);

    /** Remaining durability points of the main-hand item, or -1 when it has no durability. */
    default int mainHandDurability() {
        return -1;
    }

    /** Max durability of the main-hand item, or 0 when it has no durability. */
    default int mainHandMaxDurability() {
        return 0;
    }
}
