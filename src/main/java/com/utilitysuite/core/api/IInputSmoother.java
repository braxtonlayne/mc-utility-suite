package com.utilitysuite.core.api;

/**
 * The only path through which automation changes key states, camera rotation or the selected hotbar slot. Every
 * mutating call takes a {@link ControlLease}; the call is ignored (returns {@code false}) when the lease is inactive or
 * does not cover the channel the call needs. Client thread only.
 */
public interface IInputSmoother {

    // ---- camera -------------------------------------------------------------------------------------------------

    /** Starts (or retargets) a curved, eased rotation toward the target. Needs CAMERA. */
    boolean setRotationTarget(ControlLease lease, RotationTarget target);

    /** Stops steering the camera for this lease; silent mode hands packet rotation back to the real camera. */
    void clearRotation(ControlLease lease);

    /** True when the current target is reached within {@code toleranceDeg} (or no target is set). */
    boolean isRotationSettled(float toleranceDeg);

    // ---- keys ---------------------------------------------------------------------------------------------------

    /** Holds or releases a simulated key. Needs the key's channel. */
    boolean setKeyHeld(ControlLease lease, SimKey key, boolean down);

    /** Presses the key now and releases it after a random duration in [minTicks, maxTicks]. Needs the key's channel. */
    boolean scheduleKeyTap(ControlLease lease, SimKey key, int minTicks, int maxTicks);

    /** True while automation (not the user) holds this key down. */
    boolean isKeyHeldByAutomation(SimKey key);

    // ---- hotbar -------------------------------------------------------------------------------------------------

    /** Selects hotbar slot 0..8. Needs HOTBAR. */
    boolean selectHotbarSlot(ControlLease lease, int slot);

    // ---- timing -------------------------------------------------------------------------------------------------

    /** Gaussian sample clamped to [min, max] milliseconds. */
    double nextGaussianDelayMs(double mean, double stdDev, double min, double max);

    /** Gaussian sample in whole ticks clamped to [minTicks, maxTicks]. */
    int nextGaussianTicks(double meanTicks, double stdDevTicks, int minTicks, int maxTicks);

    // ---- cleanup ------------------------------------------------------------------------------------------------

    /** Releases every key automation holds for this lease's channels and clears its rotation. */
    void releaseFor(ControlLease lease);

    /** Releases every automation-held key and clears every rotation target. Used by Stop-All and world unload. */
    void releaseAll();
}
