package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.tactics.ThreatRank;

/**
 * Bounded primitive table of the mobs near the local player that matter to a fight, for the threat stages. Filled by
 * {@link #refresh} at most every {@link #REFRESH_TICKS} fight ticks (the Minecraft implementation is
 * {@code combat.assistant.mc.McNearbyThreats}; tests use a scripted subclass). Rows are the nearest first, at most
 * {@link #CAPACITY}; flags are {@link ThreatRank}'s ({@code HOSTILE}, {@code SWELLING}, {@code DRAWING},
 * {@code BEHIND}, {@code MELEE_IN_RANGE}, {@code RANGED}, {@code BABY}, {@code LOS}). Players are never rows.
 * <p>
 * Reading is allocation free; type ids are shared registry strings (never built per refresh). Client thread only.
 */
public abstract class NearbyThreats {

    public static final int CAPACITY = 16;
    public static final int REFRESH_TICKS = 5;

    public final int[] ids = new int[CAPACITY];
    /** Entity type registry id ({@code minecraft:creeper}, {@code somemod:thing}). */
    public final String[] types = new String[CAPACITY];
    /** Feet position. */
    public final double[] x = new double[CAPACITY];
    public final double[] y = new double[CAPACITY];
    public final double[] z = new double[CAPACITY];
    /** Horizontal distance to the local player. */
    public final double[] distance = new double[CAPACITY];
    /** Health plus absorption. */
    public final float[] health = new float[CAPACITY];
    public final int[] flags = new int[CAPACITY];
    /** Ticks left on a swelling creeper's fuse (0 when not swelling). */
    public final int[] fuseTicks = new int[CAPACITY];
    public int size;

    /** A projectile (not ours) on a course toward the player at the last refresh, and where it is relative to us. */
    public boolean projectileIncoming;
    public double projectileRx, projectileRz;

    private long refreshedAt;
    private boolean everRefreshed;

    /**
     * Called by a stage every engaged tick with the fight clock: refreshes the table when it is due.
     *
     * @return true on a refresh tick
     */
    public final boolean update(long clock) {
        if (everRefreshed && clock >= refreshedAt && clock - refreshedAt < REFRESH_TICKS) {
            return false;
        }
        everRefreshed = true;
        refreshedAt = clock;
        refresh();
        return true;
    }

    /** Refills the rows (clear, then {@link #add} the nearest first). */
    protected abstract void refresh();

    public final void clear() {
        size = 0;
        projectileIncoming = false;
    }

    /** Appends one row; false (dropped) when full. */
    public final boolean add(int id, String type, double ex, double ey, double ez, double dist, float hp, int f,
                             int fuse) {
        if (size >= CAPACITY) {
            return false;
        }
        int i = size++;
        ids[i] = id;
        types[i] = type;
        x[i] = ex;
        y[i] = ey;
        z[i] = ez;
        distance[i] = dist;
        health[i] = hp;
        flags[i] = f;
        fuseTicks[i] = fuse;
        return true;
    }

    public final boolean has(int row, int flag) {
        return (flags[row] & flag) != 0;
    }

    /** The row of an entity, or -1. */
    public final int indexOf(int id) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    /** Fight ended: the next {@link #update} refreshes at once. */
    public void reset() {
        clear();
        everRefreshed = false;
    }
}
