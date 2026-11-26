package com.utilitysuite.world.oresim;

import com.utilitysuite.UtilitySuiteMod;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link ColumnHeightCache.CellFiller} that reproduces {@code NoiseBasedChunkGenerator.iterateNoiseColumn} (the body
 * of {@code getBaseHeight(.., OCEAN_FLOOR_WG, ..)}) for all 16 columns of one 4 x 4 block cell with a single
 * {@link NoiseChunk}. Vanilla (26.3) builds a NoiseChunk over a 1 x height x 1 {@link DensityVolume} for every single
 * column; here one 4 x height x 4 volume serves the cell. The final density is interpolated on vanilla's absolute cell
 * grid, so a wider volume yields the same per-block values; the per-column walk is vanilla's (top down, aquifer
 * {@code computeSubstance}, default block for null, same stopping predicate), so the heights equal
 * {@code getBaseHeight} (checked against it for 640 columns, and cross-checked here for the first cells).
 * <p>
 * Needs the generator's private fluid picker; if it cannot be reached, or the generator is not exactly the vanilla
 * noise generator, it falls back to calling {@code getBaseHeight} per column (still cached by the
 * {@link ColumnHeightCache}).
 */
final class NoiseCellHeights implements ColumnHeightCache.CellFiller {

    private static final Field FLUID_PICKER;

    static {
        Field fp;
        try {
            fp = NoiseBasedChunkGenerator.class.getDeclaredField("globalFluidPicker");
            fp.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            UtilitySuiteMod.LOGGER.warn("OreSim: per-cell surface heights unavailable, using getBaseHeight per column: {}",
                    e.toString());
            fp = null;
        }
        FLUID_PICKER = fp;
    }

    /** Columns per cell side (the overworld's horizontal interpolation cell; any width gives identical values). */
    private static final int CELL = 4;

    /** Build-height bounds: the server level, or a fixed accessor for a client-side generator. */
    private final LevelHeightAccessor level;
    private final ChunkGenerator generator;
    private final RandomState random;
    // set only on the fast path
    private final NoiseGeneratorSettings settings;
    private final NoiseSettings noiseSettings;
    private final Aquifer.FluidPicker fluidPicker;
    /** The ore vein material rules (26.3: copper, then iron), in the order the material sequence tries them. */
    private final OreVeinRule[] veinRules;
    private final PositionalRandomFactory oreRandom;
    private final Predicate<BlockState> stopping = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
    /** Off when the generator internals are unreachable or a parity self-check failed. */
    private boolean fastPath;
    /** The first few cells are cross-checked against {@code getBaseHeight}; any mismatch disables the fast path. */
    private int parityChecksLeft = 8;

    private NoiseCellHeights(ChunkGenerator generator, RandomState random, LevelHeightAccessor level,
            NoiseGeneratorSettings settings, Aquifer.FluidPicker fluidPicker) {
        this.level = level;
        this.generator = generator;
        this.random = random;
        this.settings = settings;
        this.noiseSettings = settings == null ? null : settings.noiseSettings().clampToHeightAccessor(level);
        this.fluidPicker = fluidPicker;
        this.fastPath = settings != null;
        OreVeinRule[] veins = new OreVeinRule[0];
        PositionalRandomFactory ore = null;
        if (settings != null) {
            List<OreVeinRule> found = new ArrayList<>(2);
            collectVeinRules(settings.materialRule().value(), found, 0);
            veins = found.toArray(new OreVeinRule[0]);
            if (veins.length > 0) {
                // the factory OreVeinRule.compile asks the rule context for (RandomState's "minecraft:ore")
                ore = random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("ore"));
            }
        }
        this.veinRules = veins;
        this.oreRandom = ore;
    }

    /**
     * Collects the unconditional ore vein rules of the dimension's material rule (top-level sequence entries, through
     * registry holders). Vanilla 26.3 overworld: [bedrock_floor, copper_ore_vein, iron_ore_vein, surface, underground].
     */
    private static void collectVeinRules(MaterialRule rule, List<OreVeinRule> out, int depth) {
        if (depth > 8) {
            return;
        }
        if (rule instanceof MaterialRule.HolderHolder(var holder)) {
            collectVeinRules(holder.value(), out, depth + 1);
        } else if (rule instanceof SequenceRule(List<MaterialRule> sequence)) {
            for (MaterialRule r : sequence) {
                collectVeinRules(r, out, depth + 1);
            }
        } else if (rule instanceof OreVeinRule vein) {
            out.add(vein);
        }
    }

    static ColumnHeightCache.CellFiller create(ServerLevel level) {
        return create(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(), level);
    }

    /**
     * Same as {@link #create(ServerLevel)} for a generator that is not attached to a level: the client-side rebuild of
     * the vanilla generator from a known seed ({@link ClientWorldgenOracles}).
     */
    static NoiseCellHeights create(ChunkGenerator gen, RandomState random, LevelHeightAccessor level) {
        if (gen.getClass() == NoiseBasedChunkGenerator.class && FLUID_PICKER != null) {
            try {
                NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) gen;
                Supplier<?> sup = (Supplier<?>) FLUID_PICKER.get(noise);
                return new NoiseCellHeights(gen, random, level, noise.generatorSettings().value(),
                        (Aquifer.FluidPicker) sup.get());
            } catch (ReflectiveOperationException | RuntimeException e) {
                UtilitySuiteMod.LOGGER.warn("OreSim: falling back to getBaseHeight per column: {}", e.toString());
            }
        }
        return new NoiseCellHeights(gen, random, level, null, null);
    }

    @Override
    public void fillCell(int x, int z, ColumnHeightCache sink) {
        if (!fastPath) {
            sink.store(x, z, generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random));
            return;
        }
        NoiseSettings ns = noiseSettings;
        int x0 = Math.floorDiv(x, CELL) * CELL;
        int z0 = Math.floorDiv(z, CELL) * CELL;
        int fallback = level.getMinY();
        if (ns.height() <= 0) {
            for (int dx = 0; dx < CELL; dx++) {
                for (int dz = 0; dz < CELL; dz++) {
                    sink.store(x0 + dx, z0 + dz, fallback);
                }
            }
            return;
        }
        int requested = Integer.MIN_VALUE;
        DensityVolume volume = new DensityVolume(CELL, ns.height(), CELL, x0, ns.minY(), z0);
        BlockState defaultBlock = settings.defaultBlock();
        try (NoiseChunk chunk = new NoiseChunk(random, null, settings, fluidPicker, Blender.empty(), volume)) {
            Aquifer aquifer = chunk.aquifer();
            DensitySampler.Bound finalDensity = chunk.cachingSamplers().get(settings.noiseRouter().finalDensity());
            try (ScopedDensityBuffer density = finalDensity.sampleVolume(volume)) {
                for (int dx = 0; dx < CELL; dx++) {
                    int bx = x0 + dx;
                    for (int dz = 0; dz < CELL; dz++) {
                        int bz = z0 + dz;
                        int height = fallback;
                        for (int iy = volume.sizeY() - 1; iy >= 0; iy--) {
                            int by = volume.blockY(iy);
                            BlockState state = aquifer.computeSubstance(bx, by, bz,
                                    density.get(volume.indexUnchecked(dx, iy, dz)));
                            if (stopping.test(state == null ? defaultBlock : state)) {
                                height = by + 1;
                                break;
                            }
                        }
                        sink.store(bx, bz, height);
                        if (bx == x && bz == z) {
                            requested = height;
                        }
                    }
                }
            }
        }
        if (parityChecksLeft > 0) {
            parityChecksLeft--;
            int expected = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
            if (expected != requested) {
                fastPath = false;
                UtilitySuiteMod.LOGGER.warn("OreSim: per-cell surface height {} != getBaseHeight {} at {},{}; "
                        + "using getBaseHeight per column", requested, expected, x, z);
                for (int dx = 0; dx < CELL; dx++) {
                    for (int dz = 0; dz < CELL; dz++) {
                        sink.store(x0 + dx, z0 + dz, generator.getBaseHeight(x0 + dx, z0 + dz,
                                Heightmap.Types.OCEAN_FLOOR_WG, level, random));
                    }
                }
            }
        }
    }

    /**
     * Replays where vanilla 26.3 puts large-vein ore for the vein Y range of one chunk and reports the vein ore blocks
     * ({@code copper_ore}, {@code deepslate_iron_ore}; raw ore blocks and filler are not reported).
     * <p>
     * 26.3 moved large veins from the noise fill ({@code OreVeinifier} in 1.21.1) into the material rules
     * ({@link OreVeinRule}, applied by {@code MaterialSystem.buildSurface} to every solid block after the noise fill):
     * a block is solid when the aquifer returns null (default block) for its final density, then the vein rules run
     * in sequence order with prefilled density/richness, point-sampled filler gap and the {@code "minecraft:ore"}
     * positional random, exactly as {@code OreVeinRule.compile} evaluates them. Differences to the real chunk: no
     * structure beardifier, the bedrock floor gradient (first in the material sequence, Y -64..-60) is not applied
     * before the iron rule, and no later carvers, which remove some blocks; all left to verification like for
     * decoration ores. About 16 x 16 x 111 samples, once per simulated chunk (simulation thread).
     */
    boolean largeVeins(int chunkX, int chunkZ, TerrainOracle.LargeVeinSink sink) {
        if (!fastPath || veinRules.length == 0) {
            return false;
        }
        NoiseSettings ns = noiseSettings;
        int veinMinY = Integer.MAX_VALUE;
        int veinMaxY = Integer.MIN_VALUE;
        for (OreVeinRule.VeinType t : OreVeinRule.VeinType.values()) {
            veinMinY = Math.min(veinMinY, t.minY);
            veinMaxY = Math.max(veinMaxY, t.maxY);
        }
        int yLow = Math.max(veinMinY, ns.minY());
        int yHigh = Math.min(veinMaxY, ns.minY() + ns.height() - 1); // ns is already clamped to the level
        if (yHigh < yLow) {
            return true;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        DensityVolume volume = new DensityVolume(16, yHigh - yLow + 1, 16, minX, yLow, minZ);
        BlockState copper = Blocks.COPPER_ORE.defaultBlockState();
        BlockState defaultBlock = settings.defaultBlock();
        int rules = veinRules.length;
        try (NoiseChunk chunk = new NoiseChunk(random, null, settings, fluidPicker, Blender.empty(), volume)) {
            Aquifer aquifer = chunk.aquifer();
            DensitySamplerSet samplers = chunk.cachingSamplers();
            ScopedDensityBuffer finalDensity = samplers.get(settings.noiseRouter().finalDensity()).sampleVolume(volume);
            ScopedDensityBuffer[] veinDensity = new ScopedDensityBuffer[rules];
            ScopedDensityBuffer[] richness = new ScopedDensityBuffer[rules];
            DensitySampler.Bound[] gap = new DensitySampler.Bound[rules];
            try {
                for (int r = 0; r < rules; r++) {
                    veinDensity[r] = samplers.get(veinRules[r].density()).sampleVolume(volume);
                    richness[r] = samplers.get(veinRules[r].richness()).sampleVolume(volume);
                    gap[r] = samplers.get(veinRules[r].fillerGap());
                }
                for (int ix = 0; ix < 16; ix++) {
                    int bx = minX + ix;
                    for (int iz = 0; iz < 16; iz++) {
                        int bz = minZ + iz;
                        for (int iy = volume.sizeY() - 1; iy >= 0; iy--) {
                            int by = volume.blockY(iy);
                            int idx = volume.indexUnchecked(ix, iy, iz);
                            BlockState base = aquifer.computeSubstance(bx, by, bz, finalDensity.get(idx));
                            BlockState state = base == null ? defaultBlock : base;
                            if (state.isAir() || !state.getFluidState().isEmpty()) {
                                continue; // MaterialSystem only runs the rules on solid blocks
                            }
                            for (int r = 0; r < rules; r++) {
                                OreVeinRule rule = veinRules[r];
                                float d = veinDensity[r].get(idx);
                                if (d <= 0.0F) {
                                    continue;
                                }
                                RandomSource rnd = oreRandom.at(bx, by, bz);
                                if (rnd.nextFloat() > d) {
                                    continue;
                                }
                                float rich = richness[r].get(idx);
                                if (rnd.nextFloat() < rich && gap[r].sampleValue(bx, by, bz) < 0.0F) {
                                    if (!(rnd.nextFloat() < rule.rawOreChance())) {
                                        sink.acceptVeinOre(rule.oreBlock() == copper, bx, by, bz);
                                    }
                                }
                                break; // the rule produced ore, raw ore or filler: the sequence stops here
                            }
                        }
                    }
                }
            } finally {
                for (int r = 0; r < rules; r++) {
                    if (veinDensity[r] != null) {
                        veinDensity[r].close();
                    }
                    if (richness[r] != null) {
                        richness[r].close();
                    }
                }
                finalDensity.close();
            }
        }
        return true;
    }
}
