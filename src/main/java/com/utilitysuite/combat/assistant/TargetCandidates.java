package com.utilitysuite.combat.assistant;

/**
 * Reusable, fixed-capacity buffer of nearby living entities filled by {@link CombatWorld#scanTargets} for the
 * proactive engagement mode. Primitive arrays only, so a scan allocates nothing on the engine-free side. Entries past
 * {@link #CAPACITY} are dropped (the world fills the nearest ones first where it can).
 */
public final class TargetCandidates {

    public static final int CAPACITY = 48;

    /** A monster ({@code Enemy}) that is not a neutral mob left alone (an unprovoked enderman or zombified piglin). */
    public static final int HOSTILE = 1;
    /** Another player (never the local one). */
    public static final int PLAYER = 1 << 1;
    /** The entity's synced "aggressive" flag is set (it is attacking or charging something). */
    public static final int AGGRESSIVE = 1 << 2;
    /** A clear line of sight from the local player's eyes to the entity's eyes. Only set when LOS was computed. */
    public static final int LOS = 1 << 3;
    /** A creeper that is swelling (about to explode). */
    public static final int SWELLING = 1 << 4;
    /** A ranged attacker (skeleton family, blaze, ghast, pillager, witch...). */
    public static final int RANGED = 1 << 5;
    /** An adult, unnamed, unleashed animal that drops food (cow, pig, sheep, chicken, rabbit, mooshroom). */
    public static final int FOOD = 1 << 6;

    public final int[] ids = new int[CAPACITY];
    public final double[] distSq = new double[CAPACITY];
    /** Health plus absorption. */
    public final float[] health = new float[CAPACITY];
    public final int[] flags = new int[CAPACITY];
    public int size;

    public void clear() {
        size = 0;
    }

    /** Appends one entity; returns false (dropping it) when full. */
    public boolean add(int id, double distanceSq, float effectiveHealth, int flagBits) {
        if (size >= CAPACITY) {
            return false;
        }
        ids[size] = id;
        distSq[size] = distanceSq;
        health[size] = effectiveHealth;
        flags[size] = flagBits;
        size++;
        return true;
    }

    public boolean has(int index, int flag) {
        return (flags[index] & flag) != 0;
    }
}
