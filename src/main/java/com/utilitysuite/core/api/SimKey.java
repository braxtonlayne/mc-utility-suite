package com.utilitysuite.core.api;

/** Keys automation may hold or tap, each bound to the channel that must be leased to drive it. */
public enum SimKey {
    FORWARD(ControlChannel.MOVEMENT),
    BACK(ControlChannel.MOVEMENT),
    LEFT(ControlChannel.MOVEMENT),
    RIGHT(ControlChannel.MOVEMENT),
    JUMP(ControlChannel.MOVEMENT),
    SNEAK(ControlChannel.MOVEMENT),
    SPRINT(ControlChannel.MOVEMENT),
    ATTACK(ControlChannel.WORLD_INTERACT),
    USE(ControlChannel.WORLD_INTERACT);

    private static final SimKey[] VALUES = values();

    private final ControlChannel channel;

    SimKey(ControlChannel channel) {
        this.channel = channel;
    }

    public ControlChannel channel() {
        return channel;
    }

    public static SimKey[] all() {
        return VALUES;
    }
}
