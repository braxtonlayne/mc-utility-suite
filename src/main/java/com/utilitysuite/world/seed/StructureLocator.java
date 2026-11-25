package com.utilitysuite.world.seed;

import com.utilitysuite.world.oresim.WorldgenRng;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Engine-free structure start candidates from the world seed: for every random-spread structure set of the current
 * dimension (spacing / separation / salt table in {@link StructureSpread#PLACEMENTS}) the potential start chunk of each
 * region is computed in a square spiral around the player, nearest regions first. Biomes are not known without a biome
 * oracle, so every result is only a <em>candidate</em>: vanilla starts the structure there only when the biome (and
 * terrain) allows it. Pillager outposts also apply the vanilla legacy frequency reducer, which needs no biome.
 * Runs on the world executor; allocations are fine here (results are an immutable list).
 */
public final class StructureLocator {

    public enum Dim { OVERWORLD, NETHER, END }

    /** One candidate start chunk; the block position is the chunk centre. */
    public record Candidate(String type, int chunkX, int chunkZ, long distSq) {
        public int blockX() {
            return (chunkX << 4) + 8;
        }

        public int blockZ() {
            return (chunkZ << 4) + 8;
        }

        public int distance() {
            return (int) Math.round(Math.sqrt((double) distSq));
        }
    }

    /** Structure sets searched per dimension (vanilla 1.21.1 placements; the ruined portal set covers both). */
    public static final List<String> OVERWORLD_TYPES = List.of("village", "pillager_outpost", "ruined_portal",
            "shipwreck", "ocean_ruin", "ocean_monument", "woodland_mansion", "desert_pyramid", "jungle_temple",
            "swamp_hut", "igloo", "ancient_city", "trail_ruins", "trial_chambers");
    public static final List<String> NETHER_TYPES = List.of("nether_complex", "ruined_portal");
    public static final List<String> END_TYPES = List.of("end_city");

    private StructureLocator() {
    }

    public static List<String> typesFor(Dim dim) {
        return switch (dim) {
            case OVERWORLD -> OVERWORLD_TYPES;
            case NETHER -> NETHER_TYPES;
            case END -> END_TYPES;
        };
    }

    /** Human label for a structure set id ("pillager_outpost" -> "Pillager outpost"). */
    public static String label(String type) {
        if ("nether_complex".equals(type)) {
            return "Fortress / bastion";
        }
        String s = type.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Square spiral offsets ring by ring: (0,0), then each ring {@code r} clockwise from its corner (-r,-r), 8r cells
     * per ring. Returned packed as {dx0, dz0, dx1, dz1, ...} for rings 0..{@code rings}.
     */
    public static int[] spiral(int rings) {
        int side = 2 * rings + 1;
        int[] out = new int[side * side * 2];
        int n = 0;
        out[n++] = 0;
        out[n++] = 0;
        for (int r = 1; r <= rings; r++) {
            for (int x = -r; x <= r; x++) { // top edge
                out[n++] = x;
                out[n++] = -r;
            }
            for (int z = -r + 1; z <= r; z++) { // right edge
                out[n++] = r;
                out[n++] = z;
            }
            for (int x = r - 1; x >= -r; x--) { // bottom edge
                out[n++] = x;
                out[n++] = r;
            }
            for (int z = r - 1; z >= -r + 1; z--) { // left edge
                out[n++] = -r;
                out[n++] = z;
            }
        }
        return out;
    }

    /** Potential start chunk of region {@code (regionX, regionZ)}, packed {@code x << 32 | z}. Reuses {@code rng}. */
    public static long potentialChunk(long seed, StructureSpread.Placement p, int regionX, int regionZ, WorldgenRng rng) {
        rng.setLargeFeatureWithSalt(seed, regionX, regionZ, p.salt());
        int bound = p.spacing() - p.separation();
        int ox = p.triangular() ? (rng.nextInt(bound) + rng.nextInt(bound)) / 2 : rng.nextInt(bound);
        int oz = p.triangular() ? (rng.nextInt(bound) + rng.nextInt(bound)) / 2 : rng.nextInt(bound);
        int cx = regionX * p.spacing() + ox;
        int cz = regionZ * p.spacing() + oz;
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }

    /**
     * Nearest candidates of every structure set of {@code dim} within {@code radiusBlocks} of the block position: at
     * most {@code perType} per set, at most {@code maxTotal} overall, nearest first.
     */
    public static List<Candidate> nearest(long seed, Dim dim, int blockX, int blockZ, int radiusBlocks, int perType,
                                          int maxTotal) {
        List<Candidate> all = new ArrayList<>();
        WorldgenRng rng = WorldgenRng.legacy();
        WorldgenRng reducer = WorldgenRng.legacy();
        long r2 = (long) radiusBlocks * radiusBlocks;
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        for (String type : typesFor(dim)) {
            StructureSpread.Placement p = StructureSpread.PLACEMENTS.get(type);
            if (p == null) {
                continue;
            }
            int rx = Math.floorDiv(chunkX, p.spacing());
            int rz = Math.floorDiv(chunkZ, p.spacing());
            int rings = (radiusBlocks >> 4) / p.spacing() + 1;
            int[] sp = spiral(rings);
            List<Candidate> mine = new ArrayList<>();
            for (int i = 0; i < sp.length; i += 2) {
                long packed = potentialChunk(seed, p, rx + sp[i], rz + sp[i + 1], rng);
                int cx = unpackX(packed);
                int cz = unpackZ(packed);
                long dx = (long) (cx << 4) + 8 - blockX;
                long dz = (long) (cz << 4) + 8 - blockZ;
                long d2 = dx * dx + dz * dz;
                if (d2 > r2) {
                    continue;
                }
                if ("pillager_outpost".equals(type) && !StructureSpread.canStartOutpost(seed, cx, cz, reducer)) {
                    continue;
                }
                mine.add(new Candidate(type, cx, cz, d2));
            }
            mine.sort(Comparator.comparingLong(Candidate::distSq));
            all.addAll(mine.subList(0, Math.min(perType, mine.size())));
        }
        all.sort(Comparator.comparingLong(Candidate::distSq));
        return List.copyOf(all.subList(0, Math.min(maxTotal, all.size())));
    }
}
