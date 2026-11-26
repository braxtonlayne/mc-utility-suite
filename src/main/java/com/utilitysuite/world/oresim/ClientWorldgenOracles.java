package com.utilitysuite.world.oresim;

import com.utilitysuite.UtilitySuiteMod;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side rebuild of the vanilla 26.3 overworld / nether generator from a known seed, for remote servers where
 * the integrated server's generator ({@link ServerWorldgenOracles}) is not available.
 * <p>
 * Feasibility (1.21.1, unchanged in 26.3): the client's {@code registryAccess()} only carries the synchronized registries (biomes,
 * dimension types, ...); noise settings, density functions, noise parameters and the multi-noise parameter lists are
 * not sent. The vanilla values are however built in code ({@code VanillaRegistries.createWorldLookup()}, the same bootstrap
 * the data generator uses), and the biome parameter presets are exposed directly as
 * {@code MultiNoiseBiomeSourceParameterList.knownPresets()} keyed by {@code ResourceKey<Biome>}, so biome ids need no
 * biome registry at all. From those a {@link RandomState} (noise router + climate sampler) and a
 * {@link NoiseBasedChunkGenerator} are built exactly as the server would for a vanilla world, and the existing
 * {@link NoiseCellHeights} / {@link ColumnHeightCache} produce surface heights and noise-stage large veins.
 * Assumes vanilla worldgen: a server with worldgen datapacks or mods differs (the profile's "uncertain" flag and the
 * observed biome layer cover that).
 * <p>
 * Threading: {@link #forSeed} is expensive the first time (registry bootstrap, about a second) and must run on the
 * world executor, never the client thread. The returned context is then read on the simulation thread only.
 */
public final class ClientWorldgenOracles {

    /** Seed-independent vanilla worldgen lookup; built once. */
    private static volatile HolderLookup.Provider vanilla;
    private static volatile boolean unavailable;
    private static final ConcurrentHashMap<String, Context> CONTEXTS = new ConcurrentHashMap<>();

    private ClientWorldgenOracles() {
    }

    /** The oracles for one seed and dimension. */
    public static final class Context {
        private final Climate.Sampler sampler;
        private final Climate.ParameterList<ResourceKey<Biome>> biomes;
        private final long zoomSeed;
        private final int minQy;
        private final int maxQy;
        private final ColumnHeightCache heights;
        private final NoiseCellHeights noise;
        private final ConcurrentHashMap<ResourceKey<Biome>, String> ids = new ConcurrentHashMap<>();

        Context(Climate.Sampler sampler, Climate.ParameterList<ResourceKey<Biome>> biomes, long zoomSeed, int minY,
                int height, NoiseCellHeights noise) {
            this.sampler = sampler;
            this.biomes = biomes;
            this.zoomSeed = zoomSeed;
            this.minQy = QuartPos.fromBlock(minY);
            this.maxQy = minQy + QuartPos.fromBlock(height) - 1;
            this.noise = noise;
            this.heights = new ColumnHeightCache(noise, 1024);
        }

        /** Biome id at a quart position, straight from the climate noise (what {@code getNoiseBiome} returns). */
        public String noiseBiome(int qx, int qy, int qz) {
            ResourceKey<Biome> key = biomes.findValue(sampler.sample(qx, Math.max(minQy, Math.min(maxQy, qy)), qz));
            return ids.computeIfAbsent(key, k -> k.identifier().toString());
        }

        public final BiomeOracle biomeOracle = new BiomeOracle() {
            @Override
            public byte allows(OreSpec spec, int x, int y, int z) {
                if (spec.biomesAllowed() == null && spec.biomesDenied().isEmpty()) {
                    return YES;
                }
                try {
                    return BiomeOracle.answer(spec, biomeAt(x, y, z));
                } catch (RuntimeException e) {
                    return UNKNOWN;
                }
            }

            @Override
            public String biomeAt(int x, int y, int z) {
                long q = QuartBiomeOracle.selectQuart(zoomSeed, x, y, z);
                return noiseBiome((int) (q >> 42), (int) ((q << 22) >> 43), (int) ((q << 43) >> 43));
            }
        };

        public final TerrainOracle terrainOracle = new TerrainOracle() {
            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                return UNKNOWN; // carvers and later features are not modelled; see NoiseCellHeights.largeVeins
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return heights.surfaceHeight(x, z);
            }

            @Override
            public boolean largeVeins(int chunkX, int chunkZ, LargeVeinSink sink) {
                try {
                    return noise.largeVeins(chunkX, chunkZ, sink);
                } catch (RuntimeException e) {
                    return false;
                }
            }
        };
    }

    /**
     * Builds (or returns the cached) context for a vanilla dimension, or null when this dimension has no multi-noise
     * vanilla generator ("minecraft:the_end" uses a different biome source) or the client lacks the vanilla bootstrap.
     * Call off the client thread.
     */
    public static Context forSeed(long seed, String dimension, int minY, int height) {
        boolean nether = "minecraft:the_nether".equals(dimension);
        if (!nether && !"minecraft:overworld".equals(dimension)) {
            return null;
        }
        String key = seed + "|" + dimension;
        Context c = CONTEXTS.get(key);
        if (c != null || unavailable) {
            return c;
        }
        try {
            HolderLookup.Provider lookup = lookup();
            ResourceKey<NoiseGeneratorSettings> settingsKey = nether ? NoiseGeneratorSettings.NETHER
                    : NoiseGeneratorSettings.OVERWORLD;
            Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(settingsKey);
            RandomState random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed,
                    settings.value());
            HolderGetter<Biome> biomeGetter = lookup.lookupOrThrow(Registries.BIOME);
            // The biome source is never asked by the height/vein code; a fixed one keeps the generator light.
            NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(
                    new FixedBiomeSource(biomeGetter.getOrThrow(nether ? Biomes.NETHER_WASTES : Biomes.PLAINS)),
                    settings);
            Climate.ParameterList<ResourceKey<Biome>> params = MultiNoiseBiomeSourceParameterList.knownPresets().get(
                    nether ? MultiNoiseBiomeSourceParameterList.Preset.NETHER
                            : MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD);
            NoiseCellHeights cells = NoiseCellHeights.create(generator, random,
                    LevelHeightAccessor.create(minY, height));
            // Uncached point sampling, like the server's own uncached biome resolver; the context is used by the
            // simulation thread only.
            c = new Context(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED), params, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed),
                    minY, height, cells);
            if (CONTEXTS.size() > 8) {
                CONTEXTS.clear();
            }
            CONTEXTS.put(key, c);
            return c;
        } catch (RuntimeException | LinkageError e) {
            unavailable = true;
            UtilitySuiteMod.LOGGER.warn("OreSim: client-side worldgen rebuild unavailable, using observed data only: {}",
                    e.toString());
            return null;
        }
    }

    /** The cached context if {@link #forSeed} already built it (cheap; safe on the client thread). */
    public static Context cached(long seed, String dimension) {
        return CONTEXTS.get(seed + "|" + dimension);
    }

    public static void clearCaches() {
        CONTEXTS.clear();
    }

    private static HolderLookup.Provider lookup() {
        HolderLookup.Provider p = vanilla;
        if (p == null) {
            synchronized (ClientWorldgenOracles.class) {
                p = vanilla;
                if (p == null) {
                    p = VanillaRegistries.createWorldLookup();
                    vanilla = p;
                }
            }
        }
        return p;
    }
}
