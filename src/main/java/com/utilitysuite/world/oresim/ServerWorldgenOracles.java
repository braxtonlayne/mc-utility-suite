package com.utilitysuite.world.oresim;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-player oracles that ask the integrated server's own generator, so predictions in the user's own worlds use the
 * real surface height ({@code OCEAN_FLOOR_WG}, as the ore surface test reads it) and the real biomes (through vanilla's
 * {@code BiomeManager} zoom) even for chunks that were never loaded.
 * <p>
 * Threading: called on the simulation thread. Noise sampling and biome lookups are what the server's own parallel
 * world-generation workers call concurrently (stateless density functions, thread-local R-tree cache), so they are
 * safe off the server thread. Answers are cached per oracle (one oracle per simulated chunk). Any failure (for
 * example the server shutting down) degrades to "unknown".
 */
public final class ServerWorldgenOracles {

    private static final ConcurrentHashMap<Holder<Biome>, String> BIOME_IDS = new ConcurrentHashMap<>();

    private ServerWorldgenOracles() {
    }

    public static TerrainOracle terrain(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        return new TerrainOracle() {
            private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();

            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                return UNKNOWN;
            }

            @Override
            public int surfaceHeight(int x, int z) {
                long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
                if (cache.containsKey(key)) {
                    return cache.get(key);
                }
                int h;
                try {
                    h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                } catch (RuntimeException e) {
                    h = UNKNOWN_HEIGHT;
                }
                cache.put(key, h);
                return h;
            }
        };
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
