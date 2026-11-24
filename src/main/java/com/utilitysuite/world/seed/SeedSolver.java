package com.utilitysuite.world.seed;

import com.utilitysuite.world.oresim.WorldgenRng;
import com.utilitysuite.world.spawn.SlimeChunks;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * Pure-Java 48-bit structure-seed + 64-bit world-seed solver from client-observable clues.
 * <p>
 * Two zero-allocation inner loops recover candidate 48-bit structure seeds within seconds on a single worker thread:
 * <ol>
 *   <li><strong>End-Pillar Lifting:</strong> Uses the 16-bit {@code pillarSeed} recovered from End obsidian pillar
 *       heights ({@code s2[31:16]}) plus any structure/slime/treasure clue to search the remaining 32 bits, sieving low
 *       bits first via non-power-of-two even-bound structures and the low 4 bits of {@code pillarSeed}.</li>
 *   <li><strong>Structure Bit-Lifting + CRT Anchor:</strong> Sieves the low 18–20 bits of the seed using all
 *       even-bound non-triangular structures and slime chunks, then walks upper bits aligned modulo
 *       {@code lcm(bound, 2^(B-17))} of the strongest non-triangular anchor structure.</li>
 * </ol>
 * Each 48-bit candidate matching every clue is lifted to the unique 64-bit world seed using the client's
 * {@code BiomeManager.biomeZoomSeed} ({@link Sha256Long#liftToWorldSeed}). When 4+ clues are present and primary solve
 * finds nothing (e.g., a player-built fake pyramid), a bounded leave-one-out pass recovers the true seed anyway.
 */
public final class SeedSolver {

    public static final long MASK48 = (1L << 48) - 1;
    private static final long M = 0x5DEECE66DL;
    private static final long ADDEND = 11L;
    private static final long INV_M = 246154705703781L; // M * INV_M ≡ 1 (mod 2^48)
    /** Cap on inner-loop iterations per solve attempt (~1-1.8s on a single background thread). */
    public static final long MAX_WORK = 1_500_000_000L;
    /** Minimum estimated 48-bit entropy bits required before running expensive 64-bit SHA lifting. */
    static final double MIN_ENTROPY_BITS = 41.0;
    /** Hard cap on how many 48-bit survivors may run full 65,536-iteration SHA-256 lifting in one solve. */
    private static final int MAX_SHA_LIFTS = 1024;
    private static final int MAX_LEAVE_ONE_OUT = 10;

    public enum Status {
        FOUND_WORLD_SEED,
        FOUND_STRUCTURE_SEED,
        NEED_MORE_CLUES,
        NOT_FOUND,
        CANCELLED
    }

    public record StructureClue(String type, int chunkX, int chunkZ) {
    }

    public record ChunkClue(int chunkX, int chunkZ) {
    }

    public record Clues(long hashedSeed, boolean hasHashedSeed, int pillarSeed,
                        List<StructureClue> structures, List<ChunkClue> buriedTreasures,
                        List<ChunkClue> slimeChunks) {
        public Clues {
            structures = List.copyOf(structures);
            buriedTreasures = List.copyOf(buriedTreasures);
            slimeChunks = List.copyOf(slimeChunks);
        }

        public int totalCount() {
            return structures.size() + buriedTreasures.size() + slimeChunks.size() + (pillarSeed >= 0 ? 1 : 0);
        }
    }

    public record Readiness(boolean ready, long estimatedWork, String reason) {
    }

    public record Result(Status status, long worldSeed, long structureSeed, long candidatesTested, String detail) {
    }

    private record CompiledStructure(String type, StructureSpread.Placement placement, int chunkX, int chunkZ,
                                     int rx, int rz, long t0, int bound, int ox, int oz, int lowBitsK) {
    }

    private SeedSolver() {
    }

    /** Quickly assesses whether {@code clues} constrain both the 48-bit search and final 64-bit lift within budget. */
    public static Readiness assess(Clues clues) {
        if (clues == null) {
            return new Readiness(false, Long.MAX_VALUE, "no clues");
        }
        List<CompiledStructure> compiled = compileStructures(clues.structures());
        double entropy = estimateEntropyBits(clues, compiled);
        long pWork = clues.pillarSeed() >= 0 && clues.totalCount() >= 2
                ? estimatePillarWork(clues, compiled) : Long.MAX_VALUE;
        long sWork = compiled.size() >= 2 ? estimateStructureWork(clues, compiled) : Long.MAX_VALUE;
        long best = Math.min(pWork, sWork);
        if (best <= MAX_WORK && entropy >= MIN_ENTROPY_BITS) {
            return new Readiness(true, best, clues.pillarSeed() >= 0 && pWork <= sWork
                    ? "end pillars + structure sieve" : "structure bit sieve");
        }
        if (compiled.isEmpty() && clues.pillarSeed() < 0) {
            return new Readiness(false, best, "need structures or End pillars");
        }
        if (best <= MAX_WORK) {
            int needed = Math.max(1, (int) Math.ceil((MIN_ENTROPY_BITS - entropy) / 8.5));
            return new Readiness(false, best, "need " + needed + " more structure/treasure clue(s)");
        }
        return new Readiness(false, best, "need 1-2 more structures or slime chunks");
    }

    static double estimateEntropyBits(Clues clues, List<CompiledStructure> compiled) {
        double bits = clues.pillarSeed() >= 0 ? 16.0 : 0.0;
        for (CompiledStructure s : compiled) {
            double b2 = 2.0 * (Math.log(s.bound()) / Math.log(2.0));
            if (s.placement().triangular()) {
                b2 -= 1.25; // triangular distribution concentrates near region center
            }
            if ("pillager_outpost".equals(s.type())) {
                b2 += Math.log(5.0) / Math.log(2.0);
            }
            bits += b2;
        }
        bits += clues.buriedTreasures().size() * 6.643856; // -log2(0.01)
        bits += clues.slimeChunks().size() * 3.321928; // -log2(0.10)
        return bits;
    }

    public static Result solve(Clues clues, BooleanSupplier cancelled) {
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        Result primary = solveExact(clues, stop);
        if (primary.status() == Status.FOUND_WORLD_SEED || primary.status() == Status.FOUND_STRUCTURE_SEED
                || primary.status() == Status.CANCELLED) {
            return primary;
        }
        // Corrupted-clue fallback: if one clue was a false positive (e.g., player-built chest or altered temple),
        // retry dropping each individual clue once when enough clues remain to lift cleanly.
        if (primary.status() == Status.NOT_FOUND && clues.hasHashedSeed()) {
            int total = clues.structures().size() + clues.buriedTreasures().size() + clues.slimeChunks().size();
            if (total >= 4) {
                int attempts = 0;
                for (int i = 0; i < clues.structures().size() && attempts < MAX_LEAVE_ONE_OUT; i++) {
                    if (stop.getAsBoolean()) {
                        return new Result(Status.CANCELLED, 0L, 0L, primary.candidatesTested(), "cancelled");
                    }
                    List<StructureClue> sub = new ArrayList<>(clues.structures());
                    sub.remove(i);
                    attempts++;
                    Result r = solveExact(new Clues(clues.hashedSeed(), true, clues.pillarSeed(), sub,
                            clues.buriedTreasures(), clues.slimeChunks()), stop);
                    if (r.status() == Status.FOUND_WORLD_SEED || r.status() == Status.CANCELLED) {
                        return r;
                    }
                }
                for (int i = 0; i < clues.buriedTreasures().size() && attempts < MAX_LEAVE_ONE_OUT; i++) {
                    List<ChunkClue> sub = new ArrayList<>(clues.buriedTreasures());
                    sub.remove(i);
                    attempts++;
                    Result r = solveExact(new Clues(clues.hashedSeed(), true, clues.pillarSeed(), clues.structures(),
                            sub, clues.slimeChunks()), stop);
                    if (r.status() == Status.FOUND_WORLD_SEED || r.status() == Status.CANCELLED) {
                        return r;
                    }
                }
                for (int i = 0; i < clues.slimeChunks().size() && attempts < MAX_LEAVE_ONE_OUT; i++) {
                    List<ChunkClue> sub = new ArrayList<>(clues.slimeChunks());
                    sub.remove(i);
                    attempts++;
                    Result r = solveExact(new Clues(clues.hashedSeed(), true, clues.pillarSeed(), clues.structures(),
                            clues.buriedTreasures(), sub), stop);
                    if (r.status() == Status.FOUND_WORLD_SEED || r.status() == Status.CANCELLED) {
                        return r;
                    }
                }
            }
        }
        return primary;
    }

    private static Result solveExact(Clues clues, BooleanSupplier cancelled) {
        List<CompiledStructure> compiled = compileStructures(clues.structures());
        if (clues.hasHashedSeed() && estimateEntropyBits(clues, compiled) < MIN_ENTROPY_BITS) {
            return new Result(Status.NEED_MORE_CLUES, 0L, 0L, 0L, assess(clues).reason());
        }
        if (clues.pillarSeed() >= 0 && (!compiled.isEmpty() || !clues.buriedTreasures().isEmpty()
                || clues.slimeChunks().size() >= 2)) {
            long pWork = estimatePillarWork(clues, compiled);
            if (pWork <= MAX_WORK) {
                Result r = solveWithPillars(clues, compiled, cancelled);
                if (r.status() != Status.NOT_FOUND || r.candidatesTested() > 0) {
                    return r;
                }
            }
        }
        if (compiled.size() >= 2) {
            return solveWithStructures(clues, compiled, cancelled);
        }
        return new Result(Status.NEED_MORE_CLUES, 0L, 0L, 0L, assess(clues).reason());
    }

    // ---- Strategy 1: End Pillars (16-bit s2[31:16]) + low-bit sieve ------------------------------------------------

    private static long estimatePillarWork(Clues clues, List<CompiledStructure> compiled) {
        int sieveBits = 4; // low 4 bits of pillarSeed filter 2^4 on low20
        for (CompiledStructure s : compiled) {
            if (!s.placement().triangular() && s.lowBitsK() > 0) {
                sieveBits += 2 * Math.min(3, s.lowBitsK());
            }
        }
        sieveBits += clues.slimeChunks().size();
        // Low20 has 2^20 values; after sieveBits bits filter it, each surviving low20 runs 65,536 hi16 iterations.
        int clamped = Math.min(20, sieveBits);
        long survivors = Math.max(1L, 1L << (20 - clamped));
        return survivors * 65536L;
    }

    private static Result solveWithPillars(Clues clues, List<CompiledStructure> compiled, BooleanSupplier cancelled) {
        int pillarSeed = clues.pillarSeed() & 0xFFFF;
        int pillarLo4 = pillarSeed & 0xF;
        int mask20 = (1 << 20) - 1;
        long[] low20List = new long[1 << 16];
        int low20Count = 0;

        for (int low20 = 0; low20 < (1 << 20); low20++) {
            if ((low20 & 65535) == 0 && cancelled.getAsBoolean()) {
                return new Result(Status.CANCELLED, 0L, 0L, 0L, "cancelled");
            }
            long s0 = ((long) low20 ^ M) & mask20;
            long s1 = (s0 * M + ADDEND) & mask20;
            long s2 = (s1 * M + ADDEND) & mask20;
            if ((int) ((s2 >>> 16) & 0xFL) != pillarLo4) {
                continue;
            }
            if (!passesLowBitFilters(low20, 20, compiled, clues.slimeChunks())) {
                continue;
            }
            if (low20Count == low20List.length) {
                low20List = java.util.Arrays.copyOf(low20List, low20List.length * 2);
            }
            low20List[low20Count++] = ((long) low20) | ((s2 & 0xFFFFL) << 20);
        }

        long estimated = (long) low20Count * 65536L;
        if (estimated > MAX_WORK) {
            return new Result(Status.NEED_MORE_CLUES, 0L, 0L, 0L, "need one more clue alongside End pillars");
        }

        WorldgenRng rng = WorldgenRng.legacy();
        long tested = 0;
        long uniqueSeed48 = -1L;
        int seed48Count = 0;
        int shaLifts = 0;
        long stepS2 = 1L << 32;
        long stepS1 = (stepS2 * INV_M) & MASK48;
        long stepS0 = (stepS1 * INV_M) & MASK48;

        for (int idx = 0; idx < low20Count; idx++) {
            long packed = low20List[idx];
            long s2Lo16 = packed >>> 20;
            long s2 = ((long) pillarSeed << 16) | s2Lo16;
            long s1 = ((s2 - ADDEND) * INV_M) & MASK48;
            long s0 = ((s1 - ADDEND) * INV_M) & MASK48;

            for (int hi16 = 0; hi16 < 65536; hi16++) {
                if ((hi16 & 4095) == 0 && cancelled.getAsBoolean()) {
                    return new Result(Status.CANCELLED, 0L, 0L, tested, "cancelled");
                }
                tested++;
                long seed48 = s0 ^ M;
                if (matchesAllClues(seed48, compiled, clues, rng)) {
                    if (clues.hasHashedSeed()) {
                        if (++shaLifts > MAX_SHA_LIFTS) {
                            return new Result(Status.NEED_MORE_CLUES, 0L, 0L, tested,
                                    "too many 48-bit candidates; need 1 more clue");
                        }
                        OptionalLong full = Sha256Long.liftToWorldSeed(seed48, clues.hashedSeed(), cancelled);
                        if (full.isPresent()) {
                            return new Result(Status.FOUND_WORLD_SEED, full.getAsLong(), seed48, tested,
                                    "solved via End pillars");
                        }
                    } else {
                        uniqueSeed48 = seed48;
                        seed48Count++;
                        if (seed48Count > 1) {
                            return new Result(Status.NOT_FOUND, 0L, 0L, tested,
                                    "multiple 48-bit seeds match; missing BiomeManager hash");
                        }
                    }
                }
                s0 = (s0 + stepS0) & MASK48;
            }
        }
        if (!clues.hasHashedSeed() && seed48Count == 1) {
            return new Result(Status.FOUND_STRUCTURE_SEED, 0L, uniqueSeed48, tested, "unique 48-bit structure seed");
        }
        return new Result(Status.NOT_FOUND, 0L, 0L, tested, "no matching seed with End pillars");
    }

    // ---- Strategy 2: Overworld/Nether Structure Bit-Lifting + CRT Anchor -------------------------------------------

    private static int chooseBitWidth(List<CompiledStructure> compiled, List<ChunkClue> slimes) {
        int maxK = slimes.isEmpty() ? 0 : 1;
        for (CompiledStructure s : compiled) {
            if (!s.placement().triangular() && s.lowBitsK() > maxK) {
                maxK = s.lowBitsK();
            }
        }
        return maxK <= 0 ? 0 : 17 + Math.min(3, maxK);
    }

    private static CompiledStructure pickAnchor(List<CompiledStructure> compiled, int twoD) {
        CompiledStructure best = null;
        long bestStep = -1;
        for (CompiledStructure s : compiled) {
            if (s.placement().triangular()) {
                continue;
            }
            boolean pow2 = (s.bound() & (s.bound() - 1)) == 0;
            // Power-of-2 anchor narrows top bits to (1<<31)/bound * 2^d; non-pow2 uses lcm(bound, 2^d)
            long effectiveStep = pow2 ? (long) s.bound() * twoD : lcm(s.bound(), twoD);
            if (effectiveStep > bestStep) {
                bestStep = effectiveStep;
                best = s;
            }
        }
        return best;
    }

    private static long estimateStructureWork(Clues clues, List<CompiledStructure> compiled) {
        int b = chooseBitWidth(compiled, clues.slimeChunks());
        if (b < 18) {
            return Long.MAX_VALUE;
        }
        int twoD = 1 << (b - 17);
        CompiledStructure anchor = pickAnchor(compiled, twoD);
        if (anchor == null) {
            return Long.MAX_VALUE;
        }
        long step = (anchor.bound() & (anchor.bound() - 1)) == 0
                ? (long) anchor.bound() * twoD : lcm(anchor.bound(), twoD);
        int filterBits = 0;
        for (CompiledStructure s : compiled) {
            if (!s.placement().triangular() && s.lowBitsK() > 0) {
                filterBits += 2 * Math.min(b - 17, s.lowBitsK());
            }
        }
        filterBits += clues.slimeChunks().size();
        int clamped = Math.min(b, filterBits);
        long estSurvivors = Math.max(1L, 1L << (b - clamped));
        long inner = (1L << 31) / Math.max(1L, step);
        return estSurvivors * inner;
    }

    private static Result solveWithStructures(Clues clues, List<CompiledStructure> compiled, BooleanSupplier cancelled) {
        int b = chooseBitWidth(compiled, clues.slimeChunks());
        if (b < 18) {
            return new Result(Status.NEED_MORE_CLUES, 0L, 0L, 0L, "need even-bound structures or slime chunks");
        }
        int d = b - 17;
        int twoD = 1 << d;
        CompiledStructure anchor = pickAnchor(compiled, twoD);
        if (anchor == null) {
            return new Result(Status.NEED_MORE_CLUES, 0L, 0L, 0L, "need at least one linear-spread structure");
        }

        long maskB = (1L << b) - 1;
        int sizeB = 1 << b;
        int[] survivors = new int[4096];
        int survivorCount = 0;

        for (int low = 0; low < sizeB; low++) {
            if ((low & 65535) == 0 && cancelled.getAsBoolean()) {
                return new Result(Status.CANCELLED, 0L, 0L, 0L, "cancelled");
            }
            if (!passesLowBitFilters(low, b, compiled, clues.slimeChunks())) {
                continue;
            }
            if (survivorCount == survivors.length) {
                survivors = java.util.Arrays.copyOf(survivors, survivors.length * 2);
            }
            survivors[survivorCount++] = low;
        }

        if (survivorCount == 0) {
            return new Result(Status.NOT_FOUND, 0L, 0L, 0L, "contradictory low bits in clues");
        }

        int bound = anchor.bound();
        boolean pow2Anchor = (bound & (bound - 1)) == 0;
        long inner = pow2Anchor ? ((1L << 31) / bound) / twoD : (1L << 31) / lcm(bound, twoD);
        if ((long) survivorCount * inner > MAX_WORK) {
            return new Result(Status.NEED_MORE_CLUES, 0L, 0L, 0L,
                    "need 1-2 more structures (" + survivorCount + " low-bit candidates)");
        }

        WorldgenRng rng = WorldgenRng.legacy();
        long tested = 0;
        long uniqueSeed48 = -1L;
        int seed48Count = 0;
        int shaLifts = 0;
        int ox = anchor.ox();
        int oz = anchor.oz();
        long anchorT0 = anchor.t0();

        for (int idx = 0; idx < survivorCount; idx++) {
            long lowB = survivors[idx] & maskB;
            long s0Low = ((lowB + anchorT0) ^ M) & maskB;
            long s1Low = (s0Low * M + ADDEND) & maskB;
            long s1Low17 = s1Low & 0x1FFFFL;
            int mod2d = (int) ((s1Low >>> 17) & (twoD - 1));

            long startX;
            long endX;
            int stepX;
            if (pow2Anchor) {
                int shift = 31 - Integer.numberOfTrailingZeros(bound);
                long lo = (long) ox << shift;
                endX = (long) (ox + 1) << shift;
                int diff = (mod2d - (int) (lo & (twoD - 1))) & (twoD - 1);
                startX = lo + diff;
                stepX = twoD;
            } else {
                int g = gcd(bound, twoD);
                if (ox % g != mod2d % g) {
                    continue;
                }
                long x = ox;
                int maskD = twoD - 1;
                while ((x & maskD) != mod2d) {
                    x += bound;
                }
                startX = x;
                endX = 1L << 31;
                stepX = (bound / g) * twoD;
            }

            long s1 = (startX << 17) | s1Low17;
            long s2 = (s1 * M + ADDEND) & MASK48;
            long stepS1 = (long) stepX << 17;
            long stepS2 = (stepS1 * M) & MASK48;
            int zShift = pow2Anchor ? 31 - Integer.numberOfTrailingZeros(bound) : 0;

            for (long x = startX; x < endX; x += stepX) {
                if ((tested & 65535L) == 0 && cancelled.getAsBoolean()) {
                    return new Result(Status.CANCELLED, 0L, 0L, tested, "cancelled");
                }
                tested++;
                int bits2 = (int) (s2 >>> 17);
                boolean zOk = pow2Anchor ? (bits2 >>> zShift) == oz : (bits2 % bound) == oz;
                if (zOk) {
                    long s0 = ((s1 - ADDEND) * INV_M) & MASK48;
                    long seed48 = ((s0 ^ M) - anchorT0) & MASK48;
                    if (matchesAllClues(seed48, compiled, clues, rng)) {
                        if (clues.hasHashedSeed()) {
                            if (++shaLifts > MAX_SHA_LIFTS) {
                                return new Result(Status.NEED_MORE_CLUES, 0L, 0L, tested,
                                        "too many 48-bit candidates; need 1 more clue");
                            }
                            OptionalLong full = Sha256Long.liftToWorldSeed(seed48, clues.hashedSeed(), cancelled);
                            if (full.isPresent()) {
                                return new Result(Status.FOUND_WORLD_SEED, full.getAsLong(), seed48, tested,
                                        "solved via structures + hashed seed");
                            }
                        } else {
                            uniqueSeed48 = seed48;
                            seed48Count++;
                            if (seed48Count > 1) {
                                return new Result(Status.NOT_FOUND, 0L, 0L, tested,
                                        "multiple 48-bit seeds match; missing BiomeManager hash");
                            }
                        }
                    }
                }
                s1 = (s1 + stepS1) & MASK48;
                s2 = (s2 + stepS2) & MASK48;
            }
        }
        if (!clues.hasHashedSeed() && seed48Count == 1) {
            return new Result(Status.FOUND_STRUCTURE_SEED, 0L, uniqueSeed48, tested, "unique 48-bit structure seed");
        }
        return new Result(Status.NOT_FOUND, 0L, 0L, tested, "no matching seed");
    }

    // ---- Shared bit-filtering & exact verification helpers ---------------------------------------------------------

    private static boolean passesLowBitFilters(long lowBits, int availableBits, List<CompiledStructure> compiled,
                                               List<ChunkClue> slimes) {
        for (int i = 0; i < compiled.size(); i++) {
            CompiledStructure s = compiled.get(i);
            if (s.placement().triangular() || s.lowBitsK() <= 0) {
                continue;
            }
            int k = Math.min(availableBits - 17, s.lowBitsK());
            if (k <= 0) {
                continue;
            }
            long mask = (1L << (17 + k)) - 1;
            int maskK = (1 << k) - 1;
            long s0 = ((lowBits + s.t0()) ^ M) & mask;
            long s1 = (s0 * M + ADDEND) & mask;
            if (((int) (s1 >>> 17) & maskK) != (s.ox() & maskK)) {
                return false;
            }
            long s2 = (s1 * M + ADDEND) & mask;
            if (((int) (s2 >>> 17) & maskK) != (s.oz() & maskK)) {
                return false;
            }
        }
        for (int i = 0; i < slimes.size(); i++) {
            ChunkClue sc = slimes.get(i);
            long mask18 = (1L << 18) - 1;
            long raw = (SlimeChunks.randomSeed(lowBits, sc.chunkX(), sc.chunkZ()) ^ M) & mask18;
            long s1 = (raw * M + ADDEND) & mask18;
            // Slime chunk needs nextInt(10) == 0 -> bits % 10 == 0 -> bit 0 of bits must be 0
            if (((s1 >>> 17) & 1L) != 0L) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAllClues(long seed48, List<CompiledStructure> compiled, Clues clues, WorldgenRng rng) {
        for (int i = 0; i < compiled.size(); i++) {
            CompiledStructure s = compiled.get(i);
            if ("pillager_outpost".equals(s.type())) {
                if (!StructureSpread.canStartOutpost(seed48, s.chunkX(), s.chunkZ(), rng)) {
                    return false;
                }
            } else if (!StructureSpread.canStartIn(seed48, s.placement(), s.chunkX(), s.chunkZ(), rng)) {
                return false;
            }
        }
        for (int i = 0; i < clues.buriedTreasures().size(); i++) {
            ChunkClue bt = clues.buriedTreasures().get(i);
            if (!StructureSpread.isBuriedTreasureChunk(seed48, bt.chunkX(), bt.chunkZ(), rng)) {
                return false;
            }
        }
        for (int i = 0; i < clues.slimeChunks().size(); i++) {
            ChunkClue sc = clues.slimeChunks().get(i);
            if (!SlimeChunks.isSlimeChunk(seed48, sc.chunkX(), sc.chunkZ())) {
                return false;
            }
        }
        if (clues.pillarSeed() >= 0 && EndPillars.pillarSeedOf(seed48, rng) != (clues.pillarSeed() & 0xFFFF)) {
            return false;
        }
        return true;
    }

    private static List<CompiledStructure> compileStructures(List<StructureClue> raw) {
        List<CompiledStructure> out = new ArrayList<>(raw.size());
        for (StructureClue c : raw) {
            StructureSpread.Placement p = StructureSpread.PLACEMENTS.get(c.type());
            if (p == null) {
                continue;
            }
            int rx = Math.floorDiv(c.chunkX(), p.spacing());
            int rz = Math.floorDiv(c.chunkZ(), p.spacing());
            int ox = c.chunkX() - rx * p.spacing();
            int oz = c.chunkZ() - rz * p.spacing();
            int bound = p.spacing() - p.separation();
            if (ox < 0 || ox >= bound || oz < 0 || oz >= bound) {
                continue; // structurally impossible offset inside the region
            }
            long t0 = (long) rx * 341873128712L + (long) rz * 132897987541L + (long) p.salt();
            int tz = (bound & (bound - 1)) == 0 ? 0 : Integer.numberOfTrailingZeros(bound);
            out.add(new CompiledStructure(c.type(), p, c.chunkX(), c.chunkZ(), rx, rz, t0, bound, ox, oz, tz));
        }
        return out;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    private static long lcm(int a, int b) {
        return ((long) a / gcd(a, b)) * b;
    }
}
