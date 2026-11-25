package com.utilitysuite.core.api;

import java.util.List;
import java.util.Map;

/** Read-only summary of the player's inventory, used by planners so they stay testable without a game instance. */
public interface IInventoryView {

    /** Item id -> total count across main inventory, hotbar, offhand. */
    Map<String, Integer> counts();

    int count(String itemId);

    /** Highest harvest tier among pickaxes held (0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond, 5 netherite). */
    int bestPickaxeTier();

    /**
     * Shulker boxes carried (main inventory, hotbar, offhand) with their contents. Their items are not in
     * {@link #counts()}: using them needs a place-open-take step. Not for per-tick use (allocates).
     */
    default List<NestedContainer> carriedContainers() {
        return List.of();
    }
}
