package com.utilitysuite.world.oresim;

import java.util.List;
import java.util.Set;

/**
 * Vanilla 1.21.1 ore features for the overworld and the nether.
 * <p>
 * Sources (read from the 1.21.1 sources in the ModDevGradle artifacts): {@code OrePlacements} (count, height range,
 * rarity), {@code OreFeatures} (vein size, air-exposure discard chance), {@code BiomeDefaultFeatures} (which biomes get
 * which ore). The step/index pairs are the positions in {@code FeatureSorter.buildFeaturesPerStep} over the vanilla
 * overworld / nether biome presets; they were dumped once from the vanilla registries
 * ({@code VanillaRegistries.createLookup()} + {@code FeatureSorter}) and are only valid for unmodified vanilla
 * world generation. Datapacks or mods that add features to a step shift these indices: such worlds are flagged
 * uncertain (non-vanilla generator settings in single-player, or a NO_MATCH from the Seed Workbench).
 */
public final class VanillaOreTables {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    /** Overworld generation bounds. */
    public static final int OVERWORLD_MIN_Y = -64;
    public static final int OVERWORLD_HEIGHT = 384;
    /** Nether generation bounds (noise settings height 128; level height 256). */
    public static final int NETHER_MIN_Y = 0;
    public static final int NETHER_GEN_DEPTH = 128;
    public static final int NETHER_HEIGHT = 256;

    private static final int UNDERGROUND_ORES = 6;
    private static final int UNDERGROUND_DECORATION = 7;

    private static final Set<String> EMERALD_BIOMES = Set.of(
            "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills", "minecraft:windswept_forest",
            "minecraft:meadow", "minecraft:grove", "minecraft:snowy_slopes", "minecraft:frozen_peaks",
            "minecraft:jagged_peaks", "minecraft:stony_peaks", "minecraft:cherry_grove");
    private static final Set<String> BADLANDS = Set.of(
            "minecraft:badlands", "minecraft:wooded_badlands", "minecraft:eroded_badlands");
    private static final Set<String> DRIPSTONE = Set.of("minecraft:dripstone_caves");
    private static final Set<String> DELTAS = Set.of("minecraft:basalt_deltas");

    private static final List<OreSpec> OVERWORLD_ORES = List.of(
            ow("minecraft:ore_coal_upper", 9, 30, 30, 0, false, 136, 319, 17, 0f, "coal", null, null),
            ow("minecraft:ore_coal_lower", 10, 20, 20, 0, true, 0, 192, 17, 0.5f, "coal", null, null),
            ow("minecraft:ore_iron_upper", 11, 90, 90, 0, true, 80, 384, 9, 0f, "iron", null, null),
            ow("minecraft:ore_iron_middle", 12, 10, 10, 0, true, -24, 56, 9, 0f, "iron", null, null),
            ow("minecraft:ore_iron_small", 13, 10, 10, 0, false, -64, 72, 4, 0f, "iron", null, null),
            ow("minecraft:ore_gold", 14, 4, 4, 0, true, -64, 32, 9, 0.5f, "gold", null, null),
            ow("minecraft:ore_gold_lower", 15, 0, 1, 0, false, -64, -48, 9, 0.5f, "gold", null, null),
            ow("minecraft:ore_redstone", 16, 4, 4, 0, false, -64, 15, 8, 0f, "redstone", null, null),
            ow("minecraft:ore_redstone_lower", 17, 8, 8, 0, true, -96, -32, 8, 0f, "redstone", null, null),
            ow("minecraft:ore_diamond", 18, 7, 7, 0, true, -144, 16, 4, 0.5f, "diamond", null, null),
            ow("minecraft:ore_diamond_medium", 19, 2, 2, 0, false, -64, -4, 8, 0.5f, "diamond", null, null),
            ow("minecraft:ore_diamond_large", 20, 1, 1, 9, true, -144, 16, 12, 0.7f, "diamond", null, null),
            ow("minecraft:ore_diamond_buried", 21, 4, 4, 0, true, -144, 16, 8, 1.0f, "diamond", null, null),
            ow("minecraft:ore_lapis", 22, 2, 2, 0, true, -32, 32, 7, 0f, "lapis", null, null),
            ow("minecraft:ore_lapis_buried", 23, 4, 4, 0, false, -64, 64, 7, 1.0f, "lapis", null, null),
            ow("minecraft:ore_copper_large", 24, 16, 16, 0, true, -16, 112, 20, 0f, "copper", DRIPSTONE, null),
            ow("minecraft:ore_copper", 25, 16, 16, 0, true, -16, 112, 10, 0f, "copper", null, DRIPSTONE),
            ow("minecraft:ore_gold_extra", 28, 50, 50, 0, false, 32, 256, 9, 0f, "gold", BADLANDS, null),
            ow("minecraft:ore_emerald", 33, 100, 100, 0, true, -16, 480, 3, 0f, "emerald", EMERALD_BIOMES, null));

    private static final List<OreSpec> NETHER_ORES = List.of(
            nether("minecraft:ore_gold_deltas", 13, 20, false, 10, 117, 10, 0f, false, "minecraft:nether_gold_ore",
                    "gold", DELTAS, null),
            nether("minecraft:ore_quartz_deltas", 14, 32, false, 10, 117, 14, 0f, false, "minecraft:nether_quartz_ore",
                    "quartz", DELTAS, null),
            nether("minecraft:ore_gold_nether", 19, 10, false, 10, 117, 10, 0f, false, "minecraft:nether_gold_ore",
                    "gold", null, DELTAS),
            nether("minecraft:ore_quartz_nether", 20, 16, false, 10, 117, 14, 0f, false, "minecraft:nether_quartz_ore",
                    "quartz", null, DELTAS),
            nether("minecraft:ore_ancient_debris_large", 21, 1, true, 8, 24, 3, 1.0f, true, "minecraft:ancient_debris",
                    "ancient_debris", null, null),
            nether("minecraft:ore_debris_small", 22, 1, false, 8, 119, 2, 1.0f, true, "minecraft:ancient_debris",
                    "ancient_debris", null, null));

    /**
     * Dirt and gravel blobs run before the ores in the same step and replace base stone, which later ore veins then
     * skip without consuming randomness. Simulated (not reported) when a selected ore has a terrain-dependent stream.
     */
    private static final List<OreSpec> OVERWORLD_OCCLUDERS = List.of(
            new OreSpec("minecraft:ore_dirt", UNDERGROUND_ORES, 0, 7, 7, 0, false, 0, 160, 0, 33, 0f, false,
                    "minecraft:dirt", null, null, null, OreSpec.OCCLUDER),
            new OreSpec("minecraft:ore_gravel", UNDERGROUND_ORES, 1, 14, 14, 0, false, -64, 319, 0, 33, 0f, false,
                    "minecraft:gravel", null, null, null, OreSpec.OCCLUDER));

    private VanillaOreTables() {
    }

    /**
     * The specs to simulate for the selected resources, sorted in vanilla order (step, index), with the dirt/gravel
     * occluders prepended when they matter.
     */
    public static OreSpec[] select(String dimension, java.util.Collection<String> resources) {
        java.util.List<OreSpec> out = new java.util.ArrayList<>();
        boolean dependent = false;
        for (OreSpec s : forDimension(dimension)) {
            if (resources.contains(s.resource())) {
                out.add(s);
                dependent |= s.terrainDependentStream();
            }
        }
        if (dependent && OVERWORLD.equals(dimension)) {
            out.addAll(OVERWORLD_OCCLUDERS);
        }
        out.sort(java.util.Comparator.comparingInt(OreSpec::step).thenComparingInt(OreSpec::index));
        return out.toArray(new OreSpec[0]);
    }

    public static List<OreSpec> forDimension(String dimension) {
        if (OVERWORLD.equals(dimension)) {
            return OVERWORLD_ORES;
        }
        if (NETHER.equals(dimension)) {
            return NETHER_ORES;
        }
        return List.of();
    }

    public static int minY(String dimension) {
        return NETHER.equals(dimension) ? NETHER_MIN_Y : OVERWORLD_MIN_Y;
    }

    /** Level height (build limit), used for {@code isOutsideBuildHeight}. */
    public static int height(String dimension) {
        return NETHER.equals(dimension) ? NETHER_HEIGHT : OVERWORLD_HEIGHT;
    }

    /** Every resource name the tables know, for the UI filter. */
    public static List<String> resources() {
        return List.of("diamond", "ancient_debris", "emerald", "gold", "iron", "redstone", "lapis", "coal", "copper",
                "quartz");
    }

    private static OreSpec ow(String id, int index, int countMin, int countMax, int rarity, boolean trapezoid,
                              int minY, int maxY, int size, float discard, String resource, Set<String> allowed,
                              Set<String> denied) {
        String ore = "minecraft:" + resource + "_ore";
        String deep = "minecraft:deepslate_" + resource + "_ore";
        return new OreSpec(id, UNDERGROUND_ORES, index, countMin, countMax, rarity, trapezoid, minY, maxY, 0, size,
                discard, false, ore, deep, allowed, denied, resource);
    }

    private static OreSpec nether(String id, int index, int count, boolean trapezoid, int minY, int maxY, int size,
                                  float discard, boolean scattered, String ore, String resource, Set<String> allowed,
                                  Set<String> denied) {
        return new OreSpec(id, UNDERGROUND_DECORATION, index, count, count, 0, trapezoid, minY, maxY, 0, size, discard,
                scattered, ore, null, allowed, denied, resource);
    }
}
