package com.utilitysuite.world.oresim;

/** Answers {@code BiomeFilter} for a candidate position. Must be safe on the thread that runs the simulation. */
public interface BiomeOracle {

    byte UNKNOWN = 0;
    byte YES = 1;
    byte NO = 2;

    byte allows(OreSpec spec, int x, int y, int z);

    /** Nothing known: features restricted to some biomes are kept with reduced confidence. */
    BiomeOracle UNKNOWN_BIOMES = (spec, x, y, z) ->
            spec.biomesAllowed() == null && spec.biomesDenied().isEmpty() ? YES : UNKNOWN;
}
