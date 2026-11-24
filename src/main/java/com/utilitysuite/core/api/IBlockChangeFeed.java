package com.utilitysuite.core.api;

/**
 * Client block changes as they arrive (from {@code ClientLevel.sendBlockUpdated}: server block and section updates and
 * the client's own predicted placements), for modules that must react to the world changing under a plan instead of
 * polling it. Only the position is passed: a listener reads what it needs from its own world view, and only for the
 * positions it watches. Listeners are called on the client thread and must be cheap and allocation free (an explosion
 * can deliver hundreds of changes in one tick); a listener that needs work should set a flag and do it on its tick.
 */
public interface IBlockChangeFeed {

    @FunctionalInterface
    interface Listener {
        /** @param packedPos the changed block, {@code BlockPos.asLong} layout */
        void onBlockChanged(long packedPos);
    }

    /** Adds a listener (idempotent). */
    void subscribe(Listener listener);

    void unsubscribe(Listener listener);
}
