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
     * Hotbar slot 0..8 of the strongest melee item (main-hand attack damage times attack speed, so a sword's damage
     * per second beats a slower axe), or {@code fallback} when nothing in the hotbar beats the bare hand. Called once
     * when a fight starts, not per tick.
     */
    default int bestMeleeSlot(int fallback) {
        return fallback;
    }

    /**
     * Cheap offhand check for the totem keeper (no target needed, allocation free): when the local player exists, no
     * screen is open and the offhand holds no totem, the inventory index 0..35 of a totem; otherwise -1.
     */
    int totemRefillSlot();

    /** Single-player, or a server on the shared allow list ({@code core.ServerAllowList}). Cheap; may be cached. */
    boolean extrasAllowedHere();

    /** Address of the current server ("singleplayer" in a local world, "" when unknown). Not a tick path. */
    default String serverKey() {
        return "";
    }

    /**
     * Proactive-mode scan: fills {@code out} with living entities (players and mobs, never the local player) within
     * {@code radius} blocks, flagged per {@link TargetCandidates}. Line of sight is only traced when
     * {@code computeLos}. Called a few times a second at most, only while proactive engagement is armed and dormant.
     */
    void scanTargets(double radius, boolean computeLos, TargetCandidates out);

    /** Safe food items (not rotten flesh, spider eyes...) in the player's inventory and offhand. Called rarely. */
    int inventoryFood();

    /** Safe food in remembered containers within {@code radius} blocks ({@link FoodObjective#storedNearby}). */
    int storedFoodNearby(double radius);

    /**
     * The nearest dropped food item within {@code radius} blocks of the player and within 1.5 blocks of its feet height
     * (never one that fell into a pit): writes its offset from the player's feet (dx, dy, dz) into {@code outXyz} and
     * returns true, or returns false when there is none.
     */
    boolean nearestFoodDrop(double radius, double[] outXyz);
}
