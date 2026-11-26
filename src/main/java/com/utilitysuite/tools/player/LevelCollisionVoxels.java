package com.utilitysuite.tools.player;

import com.utilitysuite.combat.tactics.CombatVoxels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link CombatVoxels} over the live client level as a thrown projectile sees it: a cell with any collision shape is
 * {@link #SOLID}, water without one is {@link #WATER}, everything else (fire, cobweb, plants, lava) is {@link #AIR},
 * since a pearl flies through those. No level or an unloaded chunk is {@link #UNKNOWN}. Client thread only; one reused
 * {@code BlockPos.MutableBlockPos}, no allocation per lookup.
 */
final class LevelCollisionVoxels implements CombatVoxels {

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    @Override
    public int kind(int x, int y, int z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return UNKNOWN;
        }
        if (y < level.getMinY() || y > level.getMaxY()) { // getMaxY is inclusive
            return AIR;
        }
        pos.set(x, y, z);
        BlockState st = level.getBlockState(pos);
        if (!st.getCollisionShape(level, pos).isEmpty()) {
            return SOLID;
        }
        return st.getFluidState().is(FluidTags.WATER) ? WATER : AIR;
    }
}
