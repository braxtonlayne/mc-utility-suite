package com.utilitysuite.world.sonar;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Copies a section-aligned box of the loaded world into a {@link VoxelGrid}, a few chunk sections per client tick
 * (budgeted, allocation-free: states are classified straight into the reused byte grid). All-air sections are filled
 * in one go; unloaded chunks stay {@link VoxelGrid#UNKNOWN}. Client thread only; the grid must not be read by a worker
 * until {@link #step} reports completion.
 */
final class GridCapture {

    private final VoxelGrid grid;
    private ClientLevel level;
    private int chunkX0;
    private int chunkZ0;
    private int chunksX;
    private int sectionY0;
    private int sectionsY;
    private int next;
    private int total;

    GridCapture(VoxelGrid grid) {
        this.grid = grid;
    }

    /** True while a capture is in progress. */
    boolean running() {
        return level != null;
    }

    void cancel() {
        level = null;
    }

    /**
     * Starts capturing chunks within {@code chunkRadius} of the centre chunk and section rows {@code minSectionY}..
     * {@code maxSectionY} (clamped to the level).
     */
    void begin(ClientLevel lvl, int centerChunkX, int centerChunkZ, int chunkRadius, int minSectionY,
               int maxSectionY) {
        int lo = Math.max(lvl.getMinSection(), minSectionY);
        int hi = Math.min(lvl.getMaxSection() - 1, maxSectionY);
        if (hi < lo) {
            hi = lo;
        }
        level = lvl;
        chunkX0 = centerChunkX - chunkRadius;
        chunkZ0 = centerChunkZ - chunkRadius;
        chunksX = chunkRadius * 2 + 1;
        sectionY0 = lo;
        sectionsY = hi - lo + 1;
        next = 0;
        total = chunksX * chunksX * sectionsY;
        grid.reset(chunkX0 << 4, sectionY0 << 4, chunkZ0 << 4, chunksX << 4, sectionsY << 4, chunksX << 4);
    }

    /** Copies up to {@code budget} sections; returns true once the whole box is in the grid. */
    boolean step(int budget) {
        if (level == null) {
            return false;
        }
        int end = Math.min(total, next + budget);
        for (; next < end; next++) {
            int chunk = next / sectionsY;
            int sy = next % sectionsY;
            int cx = chunkX0 + chunk % chunksX;
            int cz = chunkZ0 + chunk / chunksX;
            LevelChunk c = level.getChunkSource().getChunk(cx, cz, false);
            if (c == null) {
                continue; // stays UNKNOWN
            }
            int lx0 = (chunk % chunksX) << 4;
            int lz0 = (chunk / chunksX) << 4;
            int ly0 = sy << 4;
            if (sy == 0) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        grid.setSkyY(lx0 + x, lz0 + z, c.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1);
                    }
                }
            }
            int index = level.getSectionIndexFromSectionY(sectionY0 + sy);
            LevelChunkSection[] sections = c.getSections();
            if (index < 0 || index >= sections.length) {
                continue;
            }
            LevelChunkSection s = sections[index];
            if (s == null || s.hasOnlyAir()) {
                grid.fill(lx0, ly0, lz0, 16, 16, 16, VoxelGrid.AIR);
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        grid.set(lx0 + x, ly0 + y, lz0 + z, classify(s.getBlockState(x, y, z)));
                    }
                }
            }
        }
        if (next >= total) {
            level = null;
            return true;
        }
        return false;
    }

    static byte classify(BlockState s) {
        if (s.isAir()) {
            return VoxelGrid.AIR;
        }
        Block b = s.getBlock();
        if (b == Blocks.SPAWNER || b == Blocks.TRIAL_SPAWNER) {
            return VoxelGrid.SPAWNER;
        }
        if (s.blocksMotion()) {
            return VoxelGrid.SOLID;
        }
        if (!s.getFluidState().isEmpty()) {
            return VoxelGrid.FLUID;
        }
        return VoxelGrid.OPEN;
    }
}
