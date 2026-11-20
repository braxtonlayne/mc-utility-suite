package com.utilitysuite.world.oresim;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-player oracles that ask the integrated server's own generator, so predictions in the user's own worlds use the
 * real surface height ({@code OCEAN_FLOOR_WG}, as the ore surface test reads it) and the real biomes (through vanilla's
 * {@code BiomeManager} zoom) even for chunks that were never loaded.
 * <p>
 * Threading: called on the simulation thread. Noise sampling and biome lookups are what the server's own parallel
 * world-generation workers call concurrently (stateless density functions, thread-local R-tree cache), so they are
 * safe off the server thread. Surface heights are cached per level ({@link ColumnHeightCache}). Any failure (for
 * example the server shutting down) degrades to "unknown".
 */
public final class ServerWorldgenOracles {

    private static final ConcurrentHashMap<Holder<Biome>, String> BIOME_IDS = new ConcurrentHashMap<>();

    private ServerWorldgenOracles() {
    }

    /** One shared height cache per level (the simulation thread is the only user); replaced when the level changes. */
    private static volatile SharedHeights shared;

    private record SharedHeights(ServerLevel level, ColumnHeightCache cache, ColumnHeightCache.CellFiller filler) {
    }

    /** About 1 MB: 1024 chunks x 256 ints, enough for the simulation radius plus the 3 x 3 vein-box margin. */
    private static final int HEIGHT_CACHE_CHUNKS = 1024;

    /**
     * Surface heights come from a cache shared by every simulated chunk of the level, filled a whole noise cell
     * (4 x 4 columns) per generator setup, instead of one {@code getBaseHeight} (one full NoiseChunk build) per
     * column per simulated chunk.
     */
    public static TerrainOracle terrain(ServerLevel level) {
        SharedHeights shared = sharedFor(level);
        ColumnHeightCache cache = shared.cache;
        NoiseCellHeights noise = shared.filler instanceof NoiseCellHeights n ? n : null;
        return new TerrainOracle() {
            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                return UNKNOWN;
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return cache.surfaceHeight(x, z);
            }

            @Override
            public boolean largeVeins(int chunkX, int chunkZ, LargeVeinSink sink) {
                if (noise == null) {
                    return false;
                }
                try {
                    return noise.largeVeins(chunkX, chunkZ, sink);
                } catch (RuntimeException e) {
                    return false;
                }
            }
        };
    }

    private static SharedHeights sharedFor(ServerLevel level) {
        SharedHeights s = shared;
        if (s != null && s.level == level) {
            return s;
        }
        synchronized (ServerWorldgenOracles.class) {
            s = shared;
            if (s == null || s.level != level) {
                ColumnHeightCache.CellFiller filler = NoiseCellHeights.create(level);
                s = new SharedHeights(level, new ColumnHeightCache(filler, HEIGHT_CACHE_CHUNKS), filler);
                shared = s;
            }
            return s;
        }
    }

    /** Drop the shared cache (world unload / reconfigure) so the level can be collected. */
    public static void clearCaches() {
        shared = null;
    }

    public static BiomeOracle biomes(ServerLevel level, long zoomSeed) {
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int minQy = QuartPos.fromBlock(level.getMinBuildHeight());
        int maxQy = minQy + QuartPos.fromBlock(level.getHeight()) - 1;
        return (spec, x, y, z) -> {
            if (spec.biomesAllowed() == null && spec.biomesDenied().isEmpty()) {
                return BiomeOracle.YES;
            }
            long q = QuartBiomeOracle.selectQuart(zoomSeed, x, y, z);
            int qx = (int) (q >> 42);
            int qy = Math.max(minQy, Math.min(maxQy, (int) ((q << 22) >> 43)));
            int qz = (int) ((q << 43) >> 43);
            try {
                Holder<Biome> biome = source.getNoiseBiome(qx, qy, qz, sampler);
                String id = BIOME_IDS.computeIfAbsent(biome,
                        h -> h.unwrapKey().map(k -> k.location().toString()).orElse(""));
                return spec.biomeAllows(id) ? BiomeOracle.YES : BiomeOracle.NO;
            } catch (RuntimeException e) {
                return BiomeOracle.UNKNOWN;
            }
        };
    }
}
