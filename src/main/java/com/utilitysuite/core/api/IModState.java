package com.utilitysuite.core.api;

/** Global mod state readable by every module. */
public interface IModState {

    /** Clean-Screen / Streamer Mode: every overlay, ESP box, waypoint and status line is suppressed. */
    boolean isCleanScreen();

    void setCleanScreen(boolean active);

    /** Mirrors {@link IActionArbiter#isAutomationStopped()}. */
    boolean isAutomationStopped();

    /** The active per-world profile (the global profile while no world is loaded). */
    ProfileConfig profile();

    /** Global settings shared by every world. */
    ProfileConfig global();

    /** Schedules a save of the active and global profiles (debounced, written off-thread). */
    void markDirty();
}
