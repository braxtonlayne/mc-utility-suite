package com.utilitysuite.core.api;

import java.util.Map;

/** Read-only summary of the player's inventory, used by planners so they stay testable without a game instance. */
public interface IInventoryView {

    /** Item id -> total count across main inventory, hotbar, offhand. */
    Map<String, Integer> counts();

    int count(String itemId);

    /** Highest harvest tier among pickaxes held (0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond, 5 netherite). */
    int bestPickaxeTier();
}
