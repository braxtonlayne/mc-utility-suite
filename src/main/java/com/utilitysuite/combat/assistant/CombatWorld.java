package com.utilitysuite.combat.assistant;

import java.util.UUID;

/**
 * Engine-agnostic view of the client world used by the combat code. The Minecraft implementation is
 * {@code combat.assistant.mc.MinecraftCombatWorld}; tests use a fake. Entities are addressed by their network id.
 * Client thread only; implementations must not allocate in {@link #sample}.
 */
public interface CombatWorld {

    /** Network id of the local player, or -1 when there is none. */
    int localPlayerId();

    /** True when the entity is loaded, is a living entity and is alive. */
    boolean isLivingAlive(int entityId);

    boolean isPlayer(int entityId);

    /** The entity's UUID (no copy), or null when it is not loaded. */
    UUID uuidOf(int entityId);

    /** Short description for logs (may allocate; never called per tick). */
    String describe(int entityId);

    /** Squared distance between the local player and the entity (feet), or {@code Double.MAX_VALUE}. */
    double distanceSqToLocal(int entityId);

    /** Entity under the crosshair, or -1. */
    int crosshairEntityId();

    /**
     * Fills {@code out} with the local player's and the target's state. Returns false (and leaves {@code out}
     * undefined) when the player or the target is missing, removed or dead.
     */
    boolean sample(int targetId, CombatSnapshot out);

    /**
     * Cheap offhand check for the totem keeper (no target needed, allocation free): when the local player exists, no
     * screen is open and the offhand holds no totem, the inventory index 0..35 of a totem; otherwise -1.
     */
    int totemRefillSlot();

    /** Single-player, or a server on the shared allow list ({@code core.ServerAllowList}). Cheap; may be cached. */
    boolean extrasAllowedHere();
}
