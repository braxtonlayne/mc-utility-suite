package com.utilitysuite.core.api.visual;

/** What the player is doing, as the context-morphing HUD sees it. */
public enum HudContextState {
    EXPLORING, BUILDING, COMBAT, FALLING,
    /** Standing still (no movement, no look change) for the configured time: the HUD fades back. */
    IDLE_VIEW
}
