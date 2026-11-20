package com.utilitysuite.core.api;

/**
 * Lifecycle of a feature module. The core calls {@link #onTick()} once per client tick (post) only while the module
 * is enabled and a world is loaded. {@link #onTick()} is a hot path: no allocation, no unbounded loops.
 */
public interface IModule {

    /** Stable lowercase id, e.g. "survival_autopilot". Used for config keys and lease owner ids. */
    String id();

    ModuleCategory category();

    boolean isEnabled();

    void setEnabled(boolean enabled);

    void onTick();

    /** Stop-All was triggered: drop leases, clear state, stay enabled but idle. */
    default void onStopAll() {
    }

    /** The world/server was left. Release everything and drop world-scoped state. */
    default void onWorldUnload() {
    }

    /** The profile was (re)loaded; read settings from it. */
    default void onProfileLoaded(ProfileConfig profile) {
    }
}
