package com.utilitysuite.world.seed;

/**
 * Generator settings for one world/server profile.
 *
 * @param seedKnown       false until a seed was imported or entered
 * @param mcVersion       version the terrain was generated with (predictions assume 1.21.1 vanilla)
 * @param source          where the seed came from
 * @param customWorldgen  the user flagged datapacks/mods that change ore generation (predictions become uncertain)
 */
public record GeneratorProfile(long seed, boolean seedKnown, String mcVersion, Source source, boolean customWorldgen) {

    public static final String CURRENT_VERSION = "1.21.1";

    public enum Source { NONE, INTEGRATED_SERVER, MANUAL, CHAT, SCANNED }

    public static GeneratorProfile none() {
        return new GeneratorProfile(0L, false, CURRENT_VERSION, Source.NONE, false);
    }

    /** Old-version terrain or custom generation: every prediction is flagged uncertain. */
    public boolean uncertain() {
        return customWorldgen || !CURRENT_VERSION.equals(mcVersion);
    }

    public GeneratorProfile withSeed(long newSeed, Source newSource) {
        return new GeneratorProfile(newSeed, true, mcVersion, newSource, customWorldgen);
    }
}
