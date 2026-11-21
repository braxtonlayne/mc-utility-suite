package com.utilitysuite.travel.movement;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link MovementPhysics.Voxels} over the client level with a per-tick cache: the jump search asks for the same few
 * hundred cells many times (nine key combinations, up to sixty ticks each), so each cell is read from the level once
 * per tick. A block with any collision shape counts as solid (full-cube approximation). Client thread only;
 * {@link #begin} once per tick. The cache map is reused (clear() keeps its arrays).
 */
public final class LevelVoxels implements MovementPhysics.Voxels {

    private static final byte AIR = 1;
    private static final byte SOLID = 2;
    private static final byte FULL = 3;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap(1024);
    private Level level;

    public LevelVoxels begin(Level level) {
        this.level = level;
        cache.clear();
        return this;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte v = cache.get(key);
        if (v == 0) {
            pos.set(x, y, z);
            v = read();
            cache.put(key, v);
        }
        return v >= SOLID;
    }

    @Override
    public boolean fullBlock(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte v = cache.get(key);
        if (v == 0) {
            pos.set(x, y, z);
            v = read();
            cache.put(key, v);
        }
        return v == FULL;
    }

    private byte read() {
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            return AIR;
        }
        var shape = st.getCollisionShape(level, pos);
        return shape.isEmpty() ? AIR : st.isCollisionShapeFullBlock(level, pos) ? FULL : SOLID;
    }
}
