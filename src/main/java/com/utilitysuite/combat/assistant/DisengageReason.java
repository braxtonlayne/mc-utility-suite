package com.utilitysuite.combat.assistant;

/** Why the assistant left ENGAGED. */
public enum DisengageReason {
    TARGET_LOST,
    TARGET_IS_ALLY,
    OUT_OF_RANGE,
    LINE_OF_SIGHT_LOST,
    CANCEL_KEY,
    MANUAL_MOVEMENT,
    STOP_ALL,
    DISARMED,
    LEASE_ENDED,
    WORLD_UNLOAD
}
