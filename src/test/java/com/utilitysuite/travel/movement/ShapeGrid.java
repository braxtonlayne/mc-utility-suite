package com.utilitysuite.travel.movement;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * Hand-built shaped voxel grid for engine-free tests: cubes plus the vanilla 1.21.1 collision boxes of the blocks the
 * parkour model cares about (fence post and arms, wall, pane, bottom slab, top trapdoor, any custom box, including one
 * that rises above its cell). Lookups are allocation free.
 */
final class ShapeGrid implements MovementPhysics.Voxels {

    private static final float S = 1f / 16f;

    private final Long2ObjectOpenHashMap<float[]> cells = new Long2ObjectOpenHashMap<>();
    private final Long2ByteOpenHashMap surfaces = new Long2ByteOpenHashMap();
    private final Long2FloatOpenHashMap slip = new Long2FloatOpenHashMap();

    ShapeGrid() {
        slip.defaultReturnValue((float) MovementPhysics.DEFAULT_SLIPPERINESS);
    }

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** Sets the cell's boxes (block-local, 6 floats each); null or empty clears it. */
    ShapeGrid boxes(int x, int y, int z, float... b) {
        if (b == null || b.length == 0) {
            cells.remove(key(x, y, z));
        } else {
            cells.put(key(x, y, z), b.clone());
        }
        return this;
    }

    ShapeGrid cube(int x, int y, int z) {
        return boxes(x, y, z, 0, 0, 0, 1, 1, 1);
    }

    ShapeGrid fill(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    cube(x, y, z);
                }
            }
        }
        return this;
    }

    /** Lone fence post: 0.25 wide (6..10 sixteenths), 1.5 high. */
    ShapeGrid fencePost(int x, int y, int z) {
        return boxes(x, y, z, 6 * S, 0, 6 * S, 10 * S, 1.5f, 10 * S);
    }

    /** Fence with arms toward the given sides (north = -Z, south = +Z, west = -X, east = +X). */
    ShapeGrid fence(int x, int y, int z, boolean north, boolean south, boolean west, boolean east) {
        return cross(x, y, z, 6 * S, 6 * S, 1.5f, true, north, south, west, east);
    }

    /** Wall post alone: 0.5 wide (4..12), 1.5 high. */
    ShapeGrid wallPost(int x, int y, int z) {
        return boxes(x, y, z, 4 * S, 0, 4 * S, 12 * S, 1.5f, 12 * S);
    }

    /** Wall running straight along Z with no post: the 0.375 wide side (5..11), 1.5 high. */
    ShapeGrid wallAlongZ(int x, int y, int z) {
        return cross(x, y, z, 4 * S, 5 * S, 1.5f, false, true, true, false, false);
    }

    /** Lone pane / iron bars: 0.125 wide (7..9), 1.0 high. */
    ShapeGrid pane(int x, int y, int z) {
        return boxes(x, y, z, 7 * S, 0, 7 * S, 9 * S, 1f, 9 * S);
    }

    /** Pane running along Z (connected north and south). */
    ShapeGrid paneAlongZ(int x, int y, int z) {
        return cross(x, y, z, 7 * S, 7 * S, 1f, true, true, true, false, false);
    }

    ShapeGrid slabBottom(int x, int y, int z) {
        return boxes(x, y, z, 0, 0, 0, 1, 0.5f, 1);
    }

    /** Closed trapdoor in the top half of its cell (13..16 sixteenths): a ceiling at y + 0.8125. */
    ShapeGrid trapdoorTop(int x, int y, int z) {
        return boxes(x, y, z, 0, 13 * S, 0, 1, 1, 1);
    }

    ShapeGrid surface(int x, int y, int z, byte s) {
        surfaces.put(key(x, y, z), s);
        return this;
    }

    ShapeGrid slipperiness(int x, int y, int z, float f) {
        slip.put(key(x, y, z), f);
        return this;
    }

    private ShapeGrid cross(int x, int y, int z, float post0, float arm0, float h, boolean post, boolean north,
                            boolean south, boolean west, boolean east) {
        int n = (post ? 1 : 0) + (north ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0) + (east ? 1 : 0);
        float[] b = new float[n * 6];
        int o = 0;
        float post1 = 1f - post0;
        float arm1 = 1f - arm0;
        if (post) {
            o = put(b, o, post0, 0, post0, post1, h, post1);
        }
        if (north) {
            o = put(b, o, arm0, 0, 0, arm1, h, arm1);
        }
        if (south) {
            o = put(b, o, arm0, 0, arm0, arm1, h, 1);
        }
        if (west) {
            o = put(b, o, 0, 0, arm0, arm1, h, arm1);
        }
        if (east) {
            put(b, o, arm0, 0, arm0, 1, h, arm1);
        }
        cells.put(key(x, y, z), b);
        return this;
    }

    private static int put(float[] b, int o, float a, float c, float d, float e, float f, float g) {
        b[o] = a;
        b[o + 1] = c;
        b[o + 2] = d;
        b[o + 3] = e;
        b[o + 4] = f;
        b[o + 5] = g;
        return o + 6;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return cells.containsKey(key(x, y, z));
    }

    @Override
    public boolean fullBlock(int x, int y, int z) {
        float[] b = cells.get(key(x, y, z));
        return b != null && b.length == 6 && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1
                && b[5] == 1;
    }

    @Override
    public boolean shaped() {
        return true;
    }

    @Override
    public int boxCount(int x, int y, int z) {
        float[] b = cells.get(key(x, y, z));
        return b == null ? 0 : b.length / 6;
    }

    @Override
    public void box(int x, int y, int z, int i, float[] out, int off) {
        System.arraycopy(cells.get(key(x, y, z)), i * 6, out, off, 6);
    }

    @Override
    public byte surface(int x, int y, int z) {
        long k = key(x, y, z);
        if (surfaces.containsKey(k)) {
            return surfaces.get(k);
        }
        return cells.containsKey(k) ? MovementPhysics.SURFACE_NORMAL : MovementPhysics.SURFACE_NONE;
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        return slip.get(key(x, y, z));
    }
}
