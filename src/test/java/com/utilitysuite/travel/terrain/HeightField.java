package com.utilitysuite.travel.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Test world: each column is solid full blocks below its surface height (default {@code baseTop}), plus optional
 * extra solid blocks and lava blocks.
 */
final class HeightField implements CollisionQuery, HazardWorld {

    private final Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
    private final LongOpenHashSet extraSolid = new LongOpenHashSet();
    private final LongOpenHashSet lavaBlocks = new LongOpenHashSet();

    HeightField(int baseTop) {
        tops.defaultReturnValue(baseTop);
    }

    private static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static long key(int x, int y, int z) {
        return com.utilitysuite.travel.Packed.pack(x, y, z);
    }

    /** Sets the surface (first non-solid y) of every column in [x0..x1] x [z0..z1]. */
    HeightField top(int x0, int x1, int z0, int z1, int top) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                tops.put(col(x, z), top);
            }
        }
        return this;
    }

    HeightField solidAt(int x, int y, int z) {
        extraSolid.add(key(x, y, z));
        return this;
    }

    HeightField lavaAt(int x, int y, int z) {
        lavaBlocks.add(key(x, y, z));
        return this;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return y < tops.get(col(x, z)) || extraSolid.contains(key(x, y, z));
    }

    @Override
    public boolean lava(int x, int y, int z) {
        return lavaBlocks.contains(key(x, y, z));
    }

    @Override
    public boolean loaded(int x, int z) {
        return true;
    }

    @Override
    public boolean collidesWithin(int x, int y, int z, double minY, double maxY) {
        return solid(x, y, z) && y + 1.0 >= minY && y <= maxY;
    }
}
