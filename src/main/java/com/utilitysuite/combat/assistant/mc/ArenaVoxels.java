package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.tactics.CombatVoxels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * {@link CombatVoxels} over the live client level, for the arena read and the pearl landing simulation.
 * Client thread only; must not allocate per lookup (one reused {@code BlockPos.MutableBlockPos}).
 * <p>
 * Mapping: no level or an unloaded chunk = {@link #UNKNOWN}; below the world's minimum build height = {@link #LAVA}
 * (the void kills); at or above the maximum build height = {@link #AIR}. Lava fluid = LAVA; fire, magma, cactus,
 * sweet berry bush, lit campfire, wither rose and powder snow = {@link #HARM}; cobweb = {@link #COBWEB}; soul sand,
 * honey and slime blocks = {@link #SLOW}; any block with a collision shape = {@link #SOLID} (waterlogged stairs stay
 * solid); otherwise water fluid = {@link #WATER}, else {@link #AIR}.
 */
public final class ArenaVoxels implements CombatVoxels {

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public ArenaVoxels() {
    }

    @Override
    public int kind(int x, int y, int z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return UNKNOWN;
        }
        if (y < level.getMinY()) {
            return LAVA;
        }
        if (y > level.getMaxY()) {
            return AIR;
        }
        // getChunk(x, z, FULL, false) on the client chunk cache: null when not loaded, no allocation.
        if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return UNKNOWN;
        }
        pos.set(x, y, z);
        BlockState st = level.getBlockState(pos);
        FluidState fluid = st.getFluidState();
        if (!fluid.isEmpty() && fluid.is(FluidTags.LAVA)) {
            return LAVA;
        }
        Block b = st.getBlock();
        if (harmful(st, b)) {
            return HARM;
        }
        if (b == Blocks.COBWEB) {
            return COBWEB;
        }
        if (b == Blocks.SOUL_SAND || b == Blocks.HONEY_BLOCK || b == Blocks.SLIME_BLOCK) {
            return SLOW;
        }
        if (!st.getCollisionShape(level, pos).isEmpty()) {
            return SOLID;
        }
        if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
            return WATER;
        }
        return AIR;
    }

    private static boolean harmful(BlockState st, Block b) {
        if (b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.WITHER_ROSE || b == Blocks.POWDER_SNOW) {
            return true;
        }
        if (st.is(BlockTags.FIRE)) {
            return true;
        }
        return st.is(BlockTags.CAMPFIRES) && CampfireBlock.isLitCampfire(st);
    }
}
