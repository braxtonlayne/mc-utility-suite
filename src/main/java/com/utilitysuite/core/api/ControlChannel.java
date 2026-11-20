package com.utilitysuite.core.api;

/** Independently leasable player-control channels. Exactly one module may own each channel at a time. */
public enum ControlChannel {
    /** Forward/back/strafe/jump/sneak/sprint key states. */
    MOVEMENT,
    /** Player yaw/pitch (visible or silent rotation targets). */
    CAMERA,
    /** Selected hotbar slot and inventory/container clicks. */
    HOTBAR,
    /** Attack/use key states, block breaking and placing, entity interaction. */
    WORLD_INTERACT;

    private static final ControlChannel[] VALUES = values();

    /** Cached values() so hot paths do not clone the array. */
    public static ControlChannel[] all() {
        return VALUES;
    }
}
