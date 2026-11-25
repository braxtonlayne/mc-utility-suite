package com.utilitysuite.world.sonar;

import java.util.Arrays;

/**
 * Engine-free snapshot of a box of the world: one byte per block (a coarse kind, see the constants) plus, per column,
 * the lowest world y that is open to the sky. Filled on the client thread in budgeted steps, then read by one worker
 * job at a time; the arrays are reused between scans (grown, never shrunk).
 */
public final class VoxelGrid {

    /** Air (and cave air). */
    public static final byte AIR = 0;
    /** Blocks movement: stone, ores, planks... */
    public static final byte SOLID = 1;
    /** Water or lava without a solid block. */
    public static final byte FLUID = 2;
    /** Mob or trial spawner (also solid). */
    public static final byte SPAWNER = 3;
    /** Not loaded: never a surface, never part of a void, never sky. */
    public static final byte UNKNOWN = 4;
    /** Passable non-air block (grass, torches, rails): walkable like air. */
    public static final byte OPEN = 5;

    private int ox;
    private int oy;
    private int oz;
    private int sx;
    private int sy;
    private int sz;
    private byte[] cells = new byte[0];
    private int[] skyY = new int[0];

    /** Re-aims the grid at a new box (world origin = its minimum corner); contents become UNKNOWN, sky unset. */
    public void reset(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
        ox = originX;
        oy = originY;
        oz = originZ;
        sx = sizeX;
        sy = sizeY;
        sz = sizeZ;
        int n = sizeX * sizeY * sizeZ;
        if (cells.length < n) {
            cells = new byte[n];
        }
        Arrays.fill(cells, 0, n, UNKNOWN);
        int cols = sizeX * sizeZ;
        if (skyY.length < cols) {
            skyY = new int[cols];
        }
        Arrays.fill(skyY, 0, cols, Integer.MAX_VALUE);
    }

    public int originX() {
        return ox;
    }

    public int originY() {
        return oy;
    }

    public int originZ() {
        return oz;
    }

    public int sizeX() {
        return sx;
    }

    public int sizeY() {
        return sy;
    }

    public int sizeZ() {
        return sz;
    }

    public int volume() {
        return sx * sy * sz;
    }

    /** Local index; x fastest, then z, then y (so a y step is one layer). */
    public int index(int lx, int ly, int lz) {
        return (ly * sz + lz) * sx + lx;
    }

    public boolean inside(int lx, int ly, int lz) {
        return lx >= 0 && ly >= 0 && lz >= 0 && lx < sx && ly < sy && lz < sz;
    }

    public byte get(int lx, int ly, int lz) {
        return cells[index(lx, ly, lz)];
    }

    public byte at(int index) {
        return cells[index];
    }

    public void set(int lx, int ly, int lz, byte kind) {
        cells[index(lx, ly, lz)] = kind;
    }

    /** Fills a 16-high, 16 x 16 section-aligned block of cells with one kind (all-air sections). */
    public void fill(int lx0, int ly0, int lz0, int w, int h, int d, byte kind) {
        for (int y = ly0; y < ly0 + h; y++) {
            for (int z = lz0; z < lz0 + d; z++) {
                int base = index(lx0, y, z);
                Arrays.fill(cells, base, base + w, kind);
            }
        }
    }

    /** Lowest world y that is open to the sky in this column ({@link Integer#MAX_VALUE} = unknown, never sky). */
    public void setSkyY(int lx, int lz, int worldY) {
        skyY[lz * sx + lx] = worldY;
    }

    public int skyY(int lx, int lz) {
        return skyY[lz * sx + lx];
    }

    /** Air-like: air or a passable block. */
    public static boolean open(byte kind) {
        return kind == AIR || kind == OPEN;
    }

    /** A block a surface point can sit on. */
    public static boolean solid(byte kind) {
        return kind == SOLID || kind == SPAWNER;
    }
}
