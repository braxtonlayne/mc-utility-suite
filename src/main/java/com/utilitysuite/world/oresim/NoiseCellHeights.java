package com.utilitysuite.world.oresim;

import com.utilitysuite.UtilitySuiteMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link ColumnHeightCache.CellFiller} that reproduces {@code NoiseBasedChunkGenerator.iterateNoiseColumn} (the body
 * of {@code getBaseHeight(.., OCEAN_FLOOR_WG, ..)}) for all columns of one noise cell with a single {@link NoiseChunk}.
 * Vanilla builds a whole NoiseChunk (a wrapped copy of the density-function graph, caches and an aquifer) for every
 * single column; here one build serves the cell's 16 columns. The per-column walk is vanilla's: same cell origin, same
 * corner slices (filled once by {@code advanceCellX}), same top-down {@code selectCellYZ}/{@code updateFor*} sequence,
 * same stopping predicate, so the heights are identical to {@code getBaseHeight}. Interpolation is only stopped after
 * the last column, because stopping switches the chunk to direct (non-interpolated) sampling.
 * <p>
 * Needs two non-public members (the generator's fluid picker and {@code NoiseChunk.getInterpolatedState}); if they
 * cannot be reached, or the generator is not exactly the vanilla noise generator, it falls back to calling
 * {@code getBaseHeight} per column (still cached by the {@link ColumnHeightCache}).
 */
final class NoiseCellHeights implements ColumnHeightCache.CellFiller {

    private static final MethodHandle INTERPOLATED_STATE;
    private static final Field FLUID_PICKER;
    /** {@code DensityFunctions.BeardifierMarker.INSTANCE} (a protected enum): what vanilla passes for "no structures". */
    private static final DensityFunctions.BeardifierOrMarker BEARDIFIER_MARKER;

    static {
        MethodHandle mh = null;
        Field fp = null;
        DensityFunctions.BeardifierOrMarker marker = null;
        try {
            Method m = NoiseChunk.class.getDeclaredMethod("getInterpolatedState");
            m.setAccessible(true);
            mh = MethodHandles.lookup().unreflect(m);
            fp = NoiseBasedChunkGenerator.class.getDeclaredField("globalFluidPicker");
            fp.setAccessible(true);
            marker = (DensityFunctions.BeardifierOrMarker) Class.forName(
                    "net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker").getEnumConstants()[0];
        } catch (ReflectiveOperationException | RuntimeException e) {
            UtilitySuiteMod.LOGGER.warn("OreSim: per-cell surface heights unavailable, using getBaseHeight per column: {}",
                    e.toString());
            mh = null;
            fp = null;
            marker = null;
        }
        INTERPOLATED_STATE = mh;
        FLUID_PICKER = fp;
        BEARDIFIER_MARKER = marker;
    }

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState random;
    // set only on the fast path
    private final NoiseGeneratorSettings settings;
    private final NoiseSettings noiseSettings;
    private final Aquifer.FluidPicker fluidPicker;
    private final Predicate<BlockState> stopping = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
    /** Off when the generator internals are unreachable or a parity self-check failed. */
    private boolean fastPath;
    /** The first few cells are cross-checked against {@code getBaseHeight}; any mismatch disables the fast path. */
    private int parityChecksLeft = 8;

    private NoiseCellHeights(ServerLevel level, NoiseGeneratorSettings settings, Aquifer.FluidPicker fluidPicker) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.random = level.getChunkSource().randomState();
        this.settings = settings;
        this.noiseSettings = settings == null ? null : settings.noiseSettings().clampToHeightAccessor(level);
        this.fluidPicker = fluidPicker;
        this.fastPath = settings != null;
    }

    static ColumnHeightCache.CellFiller create(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        if (gen.getClass() == NoiseBasedChunkGenerator.class && INTERPOLATED_STATE != null && FLUID_PICKER != null
                && BEARDIFIER_MARKER != null) {
            try {
                NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) gen;
                Supplier<?> sup = (Supplier<?>) FLUID_PICKER.get(noise);
                return new NoiseCellHeights(level, noise.generatorSettings().value(), (Aquifer.FluidPicker) sup.get());
            } catch (ReflectiveOperationException | RuntimeException e) {
                UtilitySuiteMod.LOGGER.warn("OreSim: falling back to getBaseHeight per column: {}", e.toString());
            }
        }
        return new NoiseCellHeights(level, null, null);
    }

    @Override
    public void fillCell(int x, int z, ColumnHeightCache sink) {
        if (!fastPath) {
            sink.store(x, z, generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random));
            return;
        }
        NoiseSettings ns = noiseSettings;
        int cellHeight = ns.getCellHeight();
        int minY = ns.minY();
        int cellMinY = Mth.floorDiv(minY, cellHeight);
        int cellCountY = Mth.floorDiv(ns.height(), cellHeight);
        int cellWidth = ns.getCellWidth();
        int x0 = Math.floorDiv(x, cellWidth) * cellWidth;
        int z0 = Math.floorDiv(z, cellWidth) * cellWidth;
        int fallback = level.getMinBuildHeight();
        if (cellCountY <= 0) {
            for (int dx = 0; dx < cellWidth; dx++) {
                for (int dz = 0; dz < cellWidth; dz++) {
                    sink.store(x0 + dx, z0 + dz, fallback);
                }
            }
            return;
        }
        NoiseChunk chunk = new NoiseChunk(1, random, x0, z0, ns, BEARDIFIER_MARKER, settings,
                fluidPicker, Blender.empty());
        chunk.initializeForFirstCellX();
        chunk.advanceCellX(0);
        BlockState defaultBlock = settings.defaultBlock();
        int requested = Integer.MIN_VALUE;
        for (int dx = 0; dx < cellWidth; dx++) {
            int bx = x0 + dx;
            double fx = (double) dx / (double) cellWidth;
            for (int dz = 0; dz < cellWidth; dz++) {
                int bz = z0 + dz;
                double fz = (double) dz / (double) cellWidth;
                int height = fallback;
                search:
                for (int cy = cellCountY - 1; cy >= 0; cy--) {
                    chunk.selectCellYZ(cy, 0);
                    for (int iy = cellHeight - 1; iy >= 0; iy--) {
                        int by = (cellMinY + cy) * cellHeight + iy;
                        chunk.updateForY(by, (double) iy / (double) cellHeight);
                        chunk.updateForX(bx, fx);
                        chunk.updateForZ(bz, fz);
                        BlockState state = interpolatedState(chunk);
                        if (stopping.test(state == null ? defaultBlock : state)) {
                            height = by + 1;
                            break search;
                        }
                    }
                }
                sink.store(bx, bz, height);
                if (bx == x && bz == z) {
                    requested = height;
                }
            }
        }
        chunk.stopInterpolation();
        if (parityChecksLeft > 0) {
            parityChecksLeft--;
            int expected = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
            if (expected != requested) {
                fastPath = false;
                UtilitySuiteMod.LOGGER.warn("OreSim: per-cell surface height {} != getBaseHeight {} at {},{}; "
                        + "using getBaseHeight per column", requested, expected, x, z);
                for (int dx = 0; dx < cellWidth; dx++) {
                    for (int dz = 0; dz < cellWidth; dz++) {
                        sink.store(x0 + dx, z0 + dz, generator.getBaseHeight(x0 + dx, z0 + dz,
                                Heightmap.Types.OCEAN_FLOOR_WG, level, random));
                    }
                }
            }
        }
    }

    /** Vein Y bounds of both {@code OreVeinifier.VeinType}s (iron -60..-8, copper 0..50). */
    private static final int VEIN_MIN_Y = -60;
    private static final int VEIN_MAX_Y = 50;

    /**
     * Replays vanilla's noise fill ({@code NoiseBasedChunkGenerator.doFill}) for the vein Y range of one chunk with a
     * single NoiseChunk and reports the vein ore blocks the material rule produces ({@code OreVeinifier} runs inside
     * {@code getInterpolatedState}, after the aquifer, exactly as in vanilla). Differences to the real chunk: no
     * structure beardifier (rare at these depths) and no later carvers, which remove some blocks; both are left to
     * verification like for decoration ores. About 16 x 16 x 112 interpolated samples, once per simulated chunk.
     */
    boolean largeVeins(int chunkX, int chunkZ, TerrainOracle.LargeVeinSink sink) {
        if (!fastPath || !settings.oreVeinsEnabled()) {
            return false;
        }
        NoiseSettings ns = noiseSettings;
        int cellHeight = ns.getCellHeight();
        int cellWidth = ns.getCellWidth();
        if (cellWidth <= 0 || 16 % cellWidth != 0) {
            return false;
        }
        int cellMinY = Mth.floorDiv(ns.minY(), cellHeight);
        int cellCountY = Mth.floorDiv(ns.height(), cellHeight);
        int cyLow = Math.max(0, Mth.floorDiv(VEIN_MIN_Y, cellHeight) - cellMinY);
        int cyHigh = Math.min(cellCountY - 1, Mth.floorDiv(VEIN_MAX_Y, cellHeight) - cellMinY);
        if (cyHigh < cyLow) {
            return true;
        }
        int cells = 16 / cellWidth;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        BlockState copper = Blocks.COPPER_ORE.defaultBlockState();
        BlockState iron = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
        NoiseChunk chunk = new NoiseChunk(cells, random, minX, minZ, ns, BEARDIFIER_MARKER, settings, fluidPicker,
                Blender.empty());
        chunk.initializeForFirstCellX();
        for (int cx = 0; cx < cells; cx++) {
            chunk.advanceCellX(cx);
            for (int cz = 0; cz < cells; cz++) {
                for (int cy = cyHigh; cy >= cyLow; cy--) {
                    chunk.selectCellYZ(cy, cz);
                    for (int iy = cellHeight - 1; iy >= 0; iy--) {
                        int by = (cellMinY + cy) * cellHeight + iy;
                        chunk.updateForY(by, (double) iy / (double) cellHeight);
                        if (by < VEIN_MIN_Y || by > VEIN_MAX_Y) {
                            continue;
                        }
                        for (int ix = 0; ix < cellWidth; ix++) {
                            int bx = minX + cx * cellWidth + ix;
                            chunk.updateForX(bx, (double) ix / (double) cellWidth);
                            for (int iz = 0; iz < cellWidth; iz++) {
                                int bz = minZ + cz * cellWidth + iz;
                                chunk.updateForZ(bz, (double) iz / (double) cellWidth);
                                BlockState state = interpolatedState(chunk);
                                if (state == copper) {
                                    sink.acceptVeinOre(true, bx, by, bz);
                                } else if (state == iron) {
                                    sink.acceptVeinOre(false, bx, by, bz);
                                }
                            }
                        }
                    }
                }
            }
            chunk.swapSlices();
        }
        chunk.stopInterpolation();
        return true;
    }

    private static BlockState interpolatedState(NoiseChunk chunk) {
        try {
            return (BlockState) INTERPOLATED_STATE.invokeExact(chunk);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
