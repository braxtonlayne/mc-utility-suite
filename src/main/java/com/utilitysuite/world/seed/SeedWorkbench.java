package com.utilitysuite.world.seed;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.oresim.OreSimulator;
import com.utilitysuite.world.oresim.OreSpec;
import com.utilitysuite.world.oresim.TerrainOracle;
import com.utilitysuite.world.oresim.BiomeOracle;
import com.utilitysuite.world.oresim.VanillaOreTables;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a candidate seed against features observed in a world the user owns or analyses and reports
 * MATCH / AMBIGUOUS / NO_MATCH with the evidence counts. Pure logic; game-dependent checks (biomes need the
 * world-gen registries) plug in as {@link Checker}s.
 */
public final class SeedWorkbench {

    public enum Verdict { MATCH, AMBIGUOUS, NO_MATCH }

    public enum Outcome { MATCH, MISMATCH, UNKNOWN }

    /** One observed fact about the world. */
    public sealed interface Evidence {
        /** Biome at a block position (from loaded chunks). */
        record Biome(int x, int y, int z, String biomeId) implements Evidence {
        }

        /** A structure start seen in a chunk (user-entered or detected), {@code type} keys {@link StructureSpread}. */
        record Structure(String type, int chunkX, int chunkZ) implements Evidence {
        }

        /** An ore block seen in the world. */
        record Ore(String dimension, int x, int y, int z, String blockId) implements Evidence {
        }
    }

    /** Decides one piece of evidence for a candidate seed. */
    @FunctionalInterface
    public interface Checker {
        Outcome check(long seed, Evidence evidence);
    }

    public record Report(long seed, Verdict verdict, int matches, int mismatches, int unknown, int strongMismatches,
                         List<String> notes) {
        public Report {
            notes = List.copyOf(notes);
        }
    }

    /** Minimum decided evidence for MATCH. */
    private int minEvidence = 4;
    /** Fraction of decided evidence that may mismatch before NO_MATCH (old chunks, approximations). */
    private double maxMismatchRatio = 0.25;

    private final List<Checker> checkers = new ArrayList<>();

    public SeedWorkbench() {
        checkers.add(SeedWorkbench::checkStructure);
        checkers.add(new OreChecker());
    }

    public SeedWorkbench withPolicy(int minEvidence, double maxMismatchRatio) {
        this.minEvidence = Math.max(1, minEvidence);
        this.maxMismatchRatio = maxMismatchRatio;
        return this;
    }

    public void addChecker(Checker checker) {
        checkers.add(checker);
    }

    public Report compare(long seed, List<Evidence> evidence) {
        int match = 0;
        int mismatch = 0;
        int unknown = 0;
        int strong = 0;
        List<String> notes = new ArrayList<>();
        for (Evidence e : evidence) {
            Outcome o = Outcome.UNKNOWN;
            for (Checker c : checkers) {
                o = c.check(seed, e);
                if (o != Outcome.UNKNOWN) {
                    break;
                }
            }
            switch (o) {
                case MATCH -> match++;
                case MISMATCH -> {
                    mismatch++;
                    if (e instanceof Evidence.Structure) {
                        strong++;
                    }
                    if (notes.size() < 8) {
                        notes.add("mismatch: " + e);
                    }
                }
                case UNKNOWN -> unknown++;
            }
        }
        return new Report(seed, verdict(match, mismatch, strong), match, mismatch, unknown, strong, notes);
    }

    /** The verdict rule, exposed for tests. */
    public Verdict verdict(int matches, int mismatches, int strongMismatches) {
        int decided = matches + mismatches;
        // A structure in a chunk where the seed cannot start one is decisive, unless it is an isolated outlier among
        // many matches (a mistyped chunk coordinate).
        if (strongMismatches > 0 && matches < 3 * strongMismatches) {
            return Verdict.NO_MATCH;
        }
        if (decided == 0) {
            return Verdict.AMBIGUOUS;
        }
        double ratio = (double) mismatches / decided;
        if (ratio > maxMismatchRatio && decided >= 2) {
            return Verdict.NO_MATCH;
        }
        if (mismatches == 0 && matches >= minEvidence) {
            return Verdict.MATCH;
        }
        if (ratio <= maxMismatchRatio / 4 && matches >= minEvidence * 2) {
            return Verdict.MATCH;
        }
        return Verdict.AMBIGUOUS;
    }

    static Outcome checkStructure(long seed, Evidence e) {
        if (!(e instanceof Evidence.Structure s)) {
            return Outcome.UNKNOWN;
        }
        StructureSpread.Placement p = StructureSpread.PLACEMENTS.get(s.type());
        if (p == null) {
            return Outcome.UNKNOWN;
        }
        return StructureSpread.canStartIn(seed, p, s.chunkX(), s.chunkZ()) ? Outcome.MATCH : Outcome.MISMATCH;
    }

    /**
     * Ore evidence: simulates the chunk holding the observed ore (and its neighbours, since veins spill over) with
     * the candidate seed. MATCH when a prediction lies within one block of the sighting. A miss only refutes the seed
     * when every feature that can place that ore has a terrain-independent random stream (no air-exposure roll, no
     * biome restriction) and the sighting is deep enough that the surface test cannot have skipped a vein; otherwise
     * the outcome is UNKNOWN.
     */
    static final class OreChecker implements Checker {
        private final OreSimulator simulator = new OreSimulator();

        @Override
        public Outcome check(long seed, Evidence e) {
            if (!(e instanceof Evidence.Ore ore)) {
                return Outcome.UNKNOWN;
            }
            List<OreSpec> all = VanillaOreTables.forDimension(ore.dimension());
            List<OreSpec> usable = new ArrayList<>();
            boolean refutable = ore.y() < VanillaOreTables.minY(ore.dimension()) + 100;
            for (OreSpec s : all) {
                if (ore.blockId().equals(s.oreId()) || ore.blockId().equals(s.deepslateOreId())) {
                    usable.add(s);
                    if (s.terrainDependentStream() || s.biomesAllowed() != null || !s.biomesDenied().isEmpty()) {
                        refutable = false;
                    }
                }
            }
            if (usable.isEmpty()) {
                return Outcome.UNKNOWN;
            }
            OreSpec[] specs = usable.toArray(new OreSpec[0]);
            int cx = ore.x() >> 4;
            int cz = ore.z() >> 4;
            int minY = VanillaOreTables.minY(ore.dimension());
            int height = VanillaOreTables.height(ore.dimension());
            boolean[] hit = new boolean[1];
            for (int dx = -1; dx <= 1 && !hit[0]; dx++) {
                for (int dz = -1; dz <= 1 && !hit[0]; dz++) {
                    simulator.simulateChunk(seed, cx + dx, cz + dz, specs, minY, height,
                            TerrainOracle.UNKNOWN_TERRAIN, BiomeOracle.UNKNOWN_BIOMES, 1f,
                            (si, x, y, z, v, c) -> {
                                if (Math.abs(x - ore.x()) <= 1 && Math.abs(y - ore.y()) <= 1
                                        && Math.abs(z - ore.z()) <= 1) {
                                    hit[0] = true;
                                }
                            });
                }
            }
            return hit[0] ? Outcome.MATCH : refutable ? Outcome.MISMATCH : Outcome.UNKNOWN;
        }
    }

    /** Helper for callers holding packed positions. */
    public static Evidence.Ore ore(String dimension, long packedPos, String blockId) {
        return new Evidence.Ore(dimension, BlockPosPacking.x(packedPos), BlockPosPacking.y(packedPos),
                BlockPosPacking.z(packedPos), blockId);
    }
}
