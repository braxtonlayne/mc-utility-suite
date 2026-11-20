package com.utilitysuite.travel.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minecraft adapter for the engine-free terrain interfaces. One reusable {@link BlockPos.MutableBlockPos}; no
 * allocation per query (block collision shapes are cached by the game). Client thread only; call {@link #bind} first.
 */
public final class LevelTerrainView implements CollisionQuery, HazardWorld {

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private Level level;

    public LevelTerrainView bind(Level level) {
        this.level = level;
        return this;
    }

    @Override
    public boolean collidesWithin(int x, int y, int z, double minY, double maxY) {
        BlockState state = level.getBlockState(pos.set(x, y, z));
        if (state.isAir()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        double top = y + shape.max(Direction.Axis.Y);
        double bottom = y + shape.min(Direction.Axis.Y);
        return top >= minY && bottom <= maxY;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        BlockState state = level.getBlockState(pos.set(x, y, z));
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public boolean lava(int x, int y, int z) {
        return level.getFluidState(pos.set(x, y, z)).is(FluidTags.LAVA);
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }
}
