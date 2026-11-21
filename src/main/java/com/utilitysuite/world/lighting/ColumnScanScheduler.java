package com.utilitysuite.world.lighting;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Engine-free, budgeted column scheduler for the light-level overlay. A pass walks every column of the square of
 * {@code radius} around a centre in rows; dirty columns (block updates near the player) jump the queue. Each tick the
 * caller takes at most {@code budget} columns: {@link #beginTick(int)} then {@link #next()} until it returns false,
 * reading {@link #x()} / {@link #z()} after each true. Allocation-free after construction (the dirty queue grows once).
 */
public final class ColumnScanScheduler {

    private int centerX;
    private int centerZ;
    private int radius;
    private int cursor;
    private int side;
    private boolean passActive;
    private int budgetLeft;
    private int x;
    private int z;
    private int passes;
    private final LongArrayFIFOQueue dirty = new LongArrayFIFOQueue();
    private final LongOpenHashSet dirtySet = new LongOpenHashSet();

    /** Starts a new full pass around (cx, cz). Pending dirty columns stay queued. */
    public void restart(int cx, int cz, int radius) {
        this.centerX = cx;
        this.centerZ = cz;
        this.radius = radius;
        this.side = radius * 2 + 1;
        this.cursor = 0;
        this.passActive = true;
    }

    /** Re-queues one column ahead of the pass (deduplicated). Ignored outside the current square. */
    public void markDirty(int colX, int colZ) {
        if (Math.abs(colX - centerX) > radius || Math.abs(colZ - centerZ) > radius) {
            return;
        }
        long key = ((long) colX << 32) | (colZ & 0xFFFFFFFFL);
        if (dirtySet.add(key)) {
            dirty.enqueue(key);
        }
    }

    public void beginTick(int budget) {
        budgetLeft = budget;
    }

    /** Advances to the next column within this tick's budget; false when the budget or all work is used up. */
    public boolean next() {
        if (budgetLeft <= 0) {
            return false;
        }
        if (!dirty.isEmpty()) {
            long key = dirty.dequeueLong();
            dirtySet.remove(key);
            x = (int) (key >> 32);
            z = (int) key;
            budgetLeft--;
            return true;
        }
        if (!passActive) {
            return false;
        }
        int total = side * side;
        if (cursor >= total) {
            passActive = false;
            passes++;
            return false;
        }
        x = centerX - radius + cursor % side;
        z = centerZ - radius + cursor / side;
        cursor++;
        budgetLeft--;
        if (cursor >= total) {
            passActive = false;
            passes++;
        }
        return true;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    /** True while a full pass is still running (a just-completed pass flips this on its last column). */
    public boolean passActive() {
        return passActive;
    }

    /** Completed full passes since construction. */
    public int passes() {
        return passes;
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public void clear() {
        dirty.clear();
        dirtySet.clear();
        passActive = false;
        cursor = 0;
    }
}
