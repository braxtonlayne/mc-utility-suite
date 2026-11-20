package com.utilitysuite.jobs.schematic.mc;

import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import com.utilitysuite.jobs.schematic.world.WorldProbe;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads the client level for {@link com.utilitysuite.jobs.schematic.world.PlacementScan}. Compares against the
 * pre-resolved palette by identity (block states are interned), reuses one {@link BlockPos.MutableBlockPos}: no
 * allocation per probe. Client thread only.
 */
public final class McWorldProbe implements WorldProbe {

    private final BlockState[] palette;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;

    public McWorldProbe(BlockState[] palette) {
        this.palette = palette;
    }

    /** Binds the level to read (call each tick before scanning; cheap). */
    public McWorldProbe level(ClientLevel level) {
        this.level = level;
        return this;
    }

    @Override
    public int probe(int x, int y, int z, int paletteIndex) {
        ClientLevel l = level;
        if (l == null || y < l.getMinBuildHeight() || y >= l.getMaxBuildHeight() || !l.hasChunk(x >> 4, z >> 4)) {
            return PlacementStatus.W_UNLOADED;
        }
        BlockState state = l.getBlockState(pos.set(x, y, z));
        if (paletteIndex >= 0) {
            BlockState want = palette[paletteIndex];
            if (want != null) {
                if (state == want) {
                    return PlacementStatus.W_MATCH;
                }
                if (state.getBlock() == want.getBlock()) {
                    return PlacementStatus.W_SAME_BLOCK;
                }
            }
        }
        if (state.isAir()) {
            return PlacementStatus.W_EMPTY;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return PlacementStatus.W_FLUID;
        }
        if (state.canBeReplaced()) {
            return PlacementStatus.W_EMPTY;
        }
        return PlacementStatus.W_OTHER;
    }

    /** The raw block state at a position (reuses the internal cursor). */
    public BlockState stateAt(int x, int y, int z) {
        return level == null ? null : level.getBlockState(pos.set(x, y, z));
    }
}
