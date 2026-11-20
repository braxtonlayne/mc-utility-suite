package com.utilitysuite.core.api;

/** Kinds of real user input reported to the arbiter by the input hooks. */
public enum ManualInput {
    /** W/A/S/D, jump, sneak or sprint pressed by the user. Affects MOVEMENT. */
    MOVEMENT_KEY,
    /** Mouse moved more than the configured threshold. Affects CAMERA. */
    MOUSE_LOOK,
    /** Attack or use pressed by the user. Affects WORLD_INTERACT. */
    ATTACK_OR_USE,
    /** Hotbar number key or scroll. Affects HOTBAR. */
    HOTBAR_SELECT
}
