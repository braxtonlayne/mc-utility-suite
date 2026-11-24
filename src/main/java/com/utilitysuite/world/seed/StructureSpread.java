package com.utilitysuite.world.seed;

import com.utilitysuite.world.oresim.WorldgenRng;

import java.util.Map;

/**
 * Vanilla 1.21.1 {@code RandomSpreadStructurePlacement.getPotentialStructureChunk} for the common structure sets
 * (values from {@code StructureSets}). A structure can only start in the potential chunk of its region, so an observed
 * structure elsewhere refutes a candidate seed. Biome checks and frequency reduction are not modelled (they can only
 * remove structures, never move them), so a match is necessary, not sufficient.
 */
public final class StructureSpread {

    public record Placement(int spacing, int separation, int salt, boolean triangular) {
    }

    public static final Map<String, Placement> PLACEMENTS = Map.ofEntries(
            Map.entry("village", new Placement(34, 8, 10387312, false)),
            Map.entry("desert_pyramid", new Placement(32, 8, 14357617, false)),
            Map.entry("igloo", new Placement(32, 8, 14357618, false)),
            Map.entry("jungle_temple", new Placement(32, 8, 14357619, false)),
            Map.entry("swamp_hut", new Placement(32, 8, 14357620, false)),
            Map.entry("pillager_outpost", new Placement(32, 8, 165745296, false)),
            Map.entry("ancient_city", new Placement(24, 8, 20083232, false)),
            Map.entry("ocean_monument", new Placement(32, 5, 10387313, true)),
            Map.entry("end_city", new Placement(20, 11, 10387313, true)),
            Map.entry("woodland_mansion", new Placement(80, 20, 10387319, true)),
            Map.entry("ruined_portal", new Placement(40, 15, 34222645, false)),
            Map.entry("shipwreck", new Placement(24, 4, 165745295, false)),
            Map.entry("ocean_ruin", new Placement(20, 8, 14357621, false)),
            Map.entry("nether_complex", new Placement(27, 4, 30084232, false)),
            Map.entry("trail_ruins", new Placement(34, 8, 83469867, false)),
            Map.entry("trial_chambers", new Placement(34, 12, 94251327, false)));

    private StructureSpread() {
    }

    /** Returns the potential start chunk of the region containing {@code (chunkX, chunkZ)} as {x, z}. */
    public static int[] potentialChunk(long seed, Placement p, int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX, p.spacing());
        int rz = Math.floorDiv(chunkZ, p.spacing());
        WorldgenRng r = WorldgenRng.legacy();
        r.setLargeFeatureWithSalt(seed, rx, rz, p.salt());
        int bound = p.spacing() - p.separation();
        int ox = p.triangular() ? (r.nextInt(bound) + r.nextInt(bound)) / 2 : r.nextInt(bound);
        int oz = p.triangular() ? (r.nextInt(bound) + r.nextInt(bound)) / 2 : r.nextInt(bound);
        return new int[]{rx * p.spacing() + ox, rz * p.spacing() + oz};
    }

    /** True when a structure of this set could start in the chunk for the seed. */
    public static boolean canStartIn(long seed, Placement p, int chunkX, int chunkZ) {
        return canStartIn(seed, p, chunkX, chunkZ, WorldgenRng.legacy());
    }

    /** Allocation-free check reusing {@code rng} ({@link WorldgenRng#legacy()}). */
    public static boolean canStartIn(long seed, Placement p, int chunkX, int chunkZ, WorldgenRng rng) {
        int rx = Math.floorDiv(chunkX, p.spacing());
        int rz = Math.floorDiv(chunkZ, p.spacing());
        rng.setLargeFeatureWithSalt(seed, rx, rz, p.salt());
        int bound = p.spacing() - p.separation();
        int ox = p.triangular() ? (rng.nextInt(bound) + rng.nextInt(bound)) / 2 : rng.nextInt(bound);
        if (rx * p.spacing() + ox != chunkX) {
            return false;
        }
        int oz = p.triangular() ? (rng.nextInt(bound) + rng.nextInt(bound)) / 2 : rng.nextInt(bound);
        return rz * p.spacing() + oz == chunkZ;
    }

    /** Pillager outpost check including vanilla {@code legacyPillagerOutpostReducer} (0.2 probability per 16-chunk sector). */
    public static boolean canStartOutpost(long seed, int chunkX, int chunkZ, WorldgenRng rng) {
        Placement p = PLACEMENTS.get("pillager_outpost");
        if (p == null || !canStartIn(seed, p, chunkX, chunkZ, rng)) {
            return false;
        }
        int sx = chunkX >> 4;
        int sz = chunkZ >> 4;
        rng.setSeed((long) (sx ^ (sz << 4)) ^ seed);
        rng.nextInt(Integer.MAX_VALUE); // consume one raw 32-bit int matching WorldgenRandom.nextInt()
        return rng.nextInt(5) == 0;
    }

    /**
     * Vanilla 1.21.1 {@code BURIED_TREASURES} frequency reducer check ({@code LEGACY_TYPE_2}, salt 10387320, prob 0.01f).
     * Note {@code StructurePlacement.isStructureChunk} passes chunk coordinates directly into the reducer.
     */
    public static boolean isBuriedTreasureChunk(long seed, int chunkX, int chunkZ, WorldgenRng rng) {
        rng.setLargeFeatureWithSalt(seed, chunkX, chunkZ, 10387320);
        return rng.nextFloat() < 0.01F;
    }
}
