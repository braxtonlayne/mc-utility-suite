package com.utilitysuite.core.api;

/**
 * Player-like block hands for modules outside the field-work package (e.g. the highway builder's tunnel digging).
 * Implemented by the field-work module: one block action at a time through a WORLD_INTERACT + CAMERA + HOTBAR
 * {@link ControlLease}, smoothed visible look, ATTACK held through {@link IInputSmoother} (relaxed mode: silent look and
 * a lease-checked direct call). Manual input and Stop-All end the lease like any automation. Client thread only.
 */
public interface IBlockWork {

    /** A fresh pair of hands for one owner module; keep it for the module's lifetime (allocation once). */
    Hands hands(String ownerId, ControlPriority priority);

    interface Hands {
        int RUNNING = 0;
        int DONE = 1;
        int FAILED = 2;

        /** True when the lease is held (requested when not). False while manual input or a higher module holds it. */
        boolean ensureLease();

        boolean hasLease();

        /** True (once) after the user's own input or Stop-All took the lease: the owner should stop its run. */
        boolean consumePreempted();

        /**
         * Breaks the block at (x, y, z) like a player. DONE when the cell is air or replaceable, FAILED on timeout or
         * (with {@code requireLos}) when something else is in the way, RUNNING otherwise. Needs the lease.
         */
        int tickBreak(int x, int y, int z, boolean relaxed, boolean requireLos);

        /** Hands the camera back to the user between actions. */
        void clearLook();

        /** Releases the keys and the lease. */
        void release();
    }
}
