package com.utilitysuite.core.api;

/**
 * Inventory/container slot clicks for automation (hotbar refill, deposit, auto-armor, tool swaps from the main
 * inventory). Every call needs an active HOTBAR lease and is spaced by the smoother's gaussian timing. Implemented by
 * the core input layer. Client thread only.
 */
public interface IContainerClicker {

    /** Kinds of click, mirroring vanilla {@code ContainerInput} (1.21.1 {@code ClickType}) without exposing it. */
    enum Click { PICKUP, QUICK_MOVE, SWAP, THROW }

    /**
     * Queues a click on a menu slot of the currently open menu (or the player inventory menu when none is open).
     *
     * @param button mouse button, or the hotbar index for {@link Click#SWAP}
     * @return false when the lease is inactive or lacks HOTBAR
     */
    boolean queueClick(ControlLease lease, int slotIndex, int button, Click click);

    /**
     * Asks the server to fill the open crafting grid from the recipe book. Performed immediately (one request, like a
     * recipe-book click). Needs HOTBAR.
     * <p>
     * 26.3: the client no longer knows recipe ids; the recipe book holds {@code RecipeDisplayEntry}s keyed by a
     * {@code RecipeDisplayId(int index)}. {@code recipeId} is that index as a decimal string (optionally prefixed
     * with {@code #}); prefer {@link #placeRecipe(ControlLease, int, boolean)}. A namespaced id such as
     * {@code minecraft:hopper} (1.21.1 form) cannot be resolved and returns false.
     *
     * @param craftAll fill as many crafts as the inventory allows (shift-click in the recipe book)
     * @return false when the lease is inactive or lacks HOTBAR, no menu is open, or the recipe is unknown
     */
    boolean placeRecipe(ControlLease lease, String recipeId, boolean craftAll);

    /**
     * {@link #placeRecipe(ControlLease, String, boolean)} with the recipe book display index
     * ({@code RecipeDisplayEntry.id().index()}).
     */
    default boolean placeRecipe(ControlLease lease, int recipeDisplayIndex, boolean craftAll) {
        return placeRecipe(lease, Integer.toString(recipeDisplayIndex), craftAll);
    }

    /**
     * Enables or disables one grid slot of the open 1.21 Crafter (the slot-lock toggle a player makes by clicking an
     * empty crafter slot). Sends the vanilla slot-state packet immediately. Needs HOTBAR.
     *
     * @return false when the lease is inactive or lacks HOTBAR, or the open menu is not a crafter
     */
    default boolean toggleSlotState(ControlLease lease, int slotIndex, boolean enabled) {
        return false;
    }

    /** Clicks still waiting for their delay. */
    int pending();

    /** Drops queued clicks for the lease (e.g. after preemption). */
    void cancel(ControlLease lease);
}
