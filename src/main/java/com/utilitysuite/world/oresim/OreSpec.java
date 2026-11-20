package com.utilitysuite.world.oresim;

import java.util.Set;

/**
 * One ore placed feature, reduced to the numbers the simulator needs. Height bounds are already resolved for the
 * dimension (vanilla {@code VerticalAnchor.resolveY}).
 *
 * @param featureId      placed feature id, e.g. {@code minecraft:ore_diamond_large}
 * @param step           {@code GenerationStep.Decoration} ordinal
 * @param index          index of the feature inside that step's global, sorted feature list ({@code FeatureSorter})
 * @param countMin       {@code CountPlacement} lower bound (equal to countMax for a constant count)
 * @param countMax       {@code CountPlacement} upper bound
 * @param rarityChance   {@code RarityFilter} chance (place on average once every N chunks), 0 when not used
 * @param trapezoid      true for {@code TrapezoidHeight} (triangle when plateau 0), false for {@code UniformHeight}
 * @param minY           resolved minimum Y (inclusive)
 * @param maxY           resolved maximum Y (inclusive)
 * @param plateau        trapezoid plateau
 * @param size           {@code OreConfiguration.size}
 * @param discardOnAir   {@code OreConfiguration.discardChanceOnAirExposure}
 * @param scattered      {@code ScatteredOreFeature} (ancient debris) instead of {@code OreFeature}
 * @param oreId          block placed into stone-like / netherrack targets
 * @param deepslateOreId block placed into deepslate-like targets, or null
 * @param biomesAllowed  biome ids that carry this feature, or null for "every biome of the dimension"
 * @param biomesDenied   biome ids that do not carry it (used when biomesAllowed is null), may be empty
 * @param resource       short resource name used by filters and the UI ("diamond", "ancient_debris", ...)
 */
public record OreSpec(String featureId, int step, int index, int countMin, int countMax, int rarityChance,
                      boolean trapezoid, int minY, int maxY, int plateau, int size, float discardOnAir,
                      boolean scattered, String oreId, String deepslateOreId, Set<String> biomesAllowed,
                      Set<String> biomesDenied, String resource) {

    public OreSpec {
        biomesDenied = biomesDenied == null ? Set.of() : Set.copyOf(biomesDenied);
        biomesAllowed = biomesAllowed == null ? null : Set.copyOf(biomesAllowed);
    }

    /** Resource name of features simulated only because they make blocks non-replaceable (dirt, gravel blobs). */
    public static final String OCCLUDER = "occluder";

    /** Simulated for fidelity only; never reported as a prediction. */
    public boolean occluder() {
        return OCCLUDER.equals(resource);
    }

    /** True when the feature's RNG stream depends on the terrain (an air-exposure roll per target block). */
    public boolean terrainDependentStream() {
        return discardOnAir > 0f && discardOnAir < 1f;
    }

    /** Whether a biome carries this feature. */
    public boolean biomeAllows(String biomeId) {
        if (biomesAllowed != null) {
            return biomesAllowed.contains(biomeId);
        }
        return !biomesDenied.contains(biomeId);
    }

    /** Block id this vein places at the given Y (deepslate below Y=0, an approximation of the gradient 0..8). */
    public String blockIdAt(int y) {
        return deepslateOreId != null && y < 0 ? deepslateOreId : oreId;
    }
}
