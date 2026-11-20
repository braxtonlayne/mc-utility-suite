package com.utilitysuite.core.api;

/** How a rotation target is applied. */
public enum RotationMode {
    /** Smoothly turns the player's real, rendered yaw/pitch. */
    VISIBLE,
    /**
     * Sends the target angles only in outgoing movement packets while the rendered camera stays where the user put it.
     * Used for background placement/mining while walking.
     */
    SILENT
}
