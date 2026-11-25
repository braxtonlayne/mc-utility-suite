package com.utilitysuite.world.stealth;

/**
 * Engine-free, budgeted cursor over the 16x16x16 sections of a box around a centre section: a pass visits every
 * section of {@code [cx - r, cx + r] x [cy - ry, cy + ry] x [cz - r, cz + r]} once, a few per tick. Used by the
 * overlays that look for rare blocks (sculk sensors, fortress bricks) with a palette pre-check. Allocation-free.
 */
public final class SectionSweep {

    private int cx;
    private int cy;
    private int cz;
    private int r;
    private int ry;
    private int cursor;
    private int total;
    private int x;
    private int y;
    private int z;

    /** Starts a new pass around section (cx, cy, cz). */
    public void restart(int cx, int cy, int cz, int r, int ry) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.r = r;
        this.ry = ry;
        this.cursor = 0;
        this.total = (2 * r + 1) * (2 * r + 1) * (2 * ry + 1);
    }

    /** Next section of the pass; false when the pass is done (then {@link #done()} is true). */
    public boolean next() {
        if (cursor >= total) {
            return false;
        }
        int side = 2 * r + 1;
        int i = cursor++;
        x = cx - r + i % side;
        z = cz - r + (i / side) % side;
        y = cy - ry + i / (side * side);
        return true;
    }

    public boolean done() {
        return cursor >= total;
    }

    public int total() {
        return total;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public int centerX() {
        return cx;
    }

    public int centerY() {
        return cy;
    }

    public int centerZ() {
        return cz;
    }
}
