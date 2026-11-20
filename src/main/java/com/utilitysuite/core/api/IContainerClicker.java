package com.utilitysuite.core.api;

/**
 * Inventory/container slot clicks for automation (hotbar refill, deposit, auto-armor, tool swaps from the main
 * inventory). Every call needs an active HOTBAR lease and is spaced by the smoother's gaussian timing. Implemented by
 * the core input layer. Client thread only.
 */
public interface IContainerClicker {

    /** Kinds of click, mirroring vanilla {@code ClickType} without exposing it. */
    enum Click { PICKUP, QUICK_MOVE, SWAP, THROW }

    /**
     * Queues a click on a menu slot of the currently open menu (or the player inventory menu when none is open).
     *
     * @param button mouse button, or the hotbar index for {@link Click#SWAP}
     * @return false when the lease is inactive or lacks HOTBAR
     */
    boolean queueClick(ControlLease lease, int slotIndex, int button, Click click);

    /** Clicks still waiting for their delay. */
    int pending();

    /** Drops queued clicks for the lease (e.g. after preemption). */
    void cancel(ControlLease lease);
}
