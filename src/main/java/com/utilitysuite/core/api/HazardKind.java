package com.utilitysuite.core.api;

/** Kinds of danger a source can report to the {@link IDangerModel}. One slot per kind (the nearest one wins). */
public enum HazardKind {
    /** A fall that would hurt on landing. */
    FALL,
    /** An arrow, trident or other projectile on a hit course. */
    PROJECTILE,
    /** A creeper fuse, primed TNT, crystal or bed about to blow. */
    EXPLOSION,
    /** A melee wind-up from a mob or player in reach. */
    MELEE,
    /** Burning, or standing in fire. */
    FIRE,
    /** Lava at or next to the feet, or on the path ahead. */
    LAVA,
    /** Air is running out. */
    DROWNING,
    /** Health is low enough that any hit may kill. */
    LOW_HEALTH,
    /** Falling toward the void. */
    VOID;

    private static final HazardKind[] VALUES = values();

    /** Shared array, do not modify. Lets hot paths iterate without the {@code values()} clone. */
    public static HazardKind[] all() {
        return VALUES;
    }
}
