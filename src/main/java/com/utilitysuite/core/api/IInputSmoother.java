package com.utilitysuite.core.api;

/**
 * The only path through which automation changes key states, camera rotation or the selected hotbar slot. Every
 * mutating call takes a {@link ControlLease}; the call is ignored (returns {@code false}) when the lease is inactive or
 * does not cover the channel the call needs. Client thread only.
 */
public interface IInputSmoother {

    /**
     * HARD RULE, no head snapping: no automated look change (camera or server-visible) turns faster than this,
     * whatever speed a {@code RotationTarget} asks for. Modules plan time-critical turns with it.
     */
    double MAX_TURN_DEG_PER_SEC = 450.0;

    // ---- camera -------------------------------------------------------------------------------------------------

    /** Starts (or retargets) a curved, eased rotation toward the target. Needs CAMERA. */
    boolean setRotationTarget(ControlLease lease, RotationTarget target);

    /** Stops steering the camera for this lease; silent mode hands packet rotation back to the real camera. */
    void clearRotation(ControlLease lease);

    /** True when the current target is reached within {@code toleranceDeg} (or no target is set). */
    boolean isRotationSettled(float toleranceDeg);

    /**
     * Guards against code outside the core turning the player (a path library aiming at the block it mines): while on,
     * any rotation made between the start of a tick and the movement input is undone and becomes a smoothed, capped
     * VISIBLE target for whoever holds CAMERA, and the movement keys are re-aimed along the wanted heading so the walk
     * stays on course while the head catches up. No head snapping, whoever asks for the turn. Client thread only.
     */
    default void setForeignRotationGuard(boolean on) {
    }

    // ---- server-visible pose (packet layer) ---------------------------------------------------------------------

    /**
     * Overrides the sneak and sprint states the server (and so other players) sees, without changing how the local
     * player moves; {@code null} = report the real state. Needs CAMERA (like a SILENT rotation, it is what the server
     * sees of the player). Lapses when the lease ends; cleared by {@link #releaseFor}/{@link #releaseAll}.
     */
    boolean setServerPose(ControlLease lease, Boolean sneak, Boolean sprint);

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
