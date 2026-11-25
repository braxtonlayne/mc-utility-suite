package com.utilitysuite.world.oresim;

/** Answers {@code BiomeFilter} for a candidate position. Must be safe on the thread that runs the simulation. */
public interface BiomeOracle {

    byte UNKNOWN = 0;
    byte YES = 1;
    byte NO = 2;

    byte allows(OreSpec spec, int x, int y, int z);

    /**
     * The biome id ({@code minecraft:plains}) vanilla's {@code LevelReader.getBiome(pos)} returns at the block, or null
     * when this oracle does not know it.
     */
    default String biomeAt(int x, int y, int z) {
        return null;
    }

    /** {@code BiomeFilter} answer from a biome id lookup (shared by the id-based oracles). */
    static byte answer(OreSpec spec, String biomeId) {
        if (spec.biomesAllowed() == null && spec.biomesDenied().isEmpty()) {
            return YES;
        }
        if (biomeId == null) {
            return UNKNOWN;
        }
        return spec.biomeAllows(biomeId) ? YES : NO;
    }

    /** Asks {@code first}; only where it answers UNKNOWN asks {@code second}. */
    static BiomeOracle layered(BiomeOracle first, BiomeOracle second) {
        return new BiomeOracle() {
            @Override
            public byte allows(OreSpec spec, int x, int y, int z) {
                byte a = first.allows(spec, x, y, z);
                return a != UNKNOWN ? a : second.allows(spec, x, y, z);
            }

            @Override
            public String biomeAt(int x, int y, int z) {
                String id = first.biomeAt(x, y, z);
                return id != null ? id : second.biomeAt(x, y, z);
            }
        };
    }

    /** Nothing known: features restricted to some biomes are kept with reduced confidence. */
    BiomeOracle UNKNOWN_BIOMES = (spec, x, y, z) ->
            spec.biomesAllowed() == null && spec.biomesDenied().isEmpty() ? YES : UNKNOWN;
}
