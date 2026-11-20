package com.utilitysuite.world.oresim;

/**
 * Chunk-scoped re-implementation of vanilla 1.21.1 ore decoration (pure Java, no game classes).
 * <p>
 * Mirrors, in order: {@code ChunkGenerator.applyBiomeDecoration} (decoration seed from the chunk's minimum block
 * corner, one feature seed per placed feature from its step and index), the placement modifiers
 * ({@code RarityFilter} / {@code CountPlacement} → {@code InSquarePlacement} → {@code HeightRangePlacement} →
 * {@code BiomeFilter}), evaluated element by element exactly like vanilla's lazy stream so the feature of attempt
 * {@code n} consumes randomness before attempt {@code n+1} picks its position, and {@code OreFeature} /
 * {@code ScatteredOreFeature} (vein shape, overlap pruning, per-block air-exposure roll).
 * <p>
 * Not thread-safe; one instance per worker thread. Allocation-free after warm-up.
 */
public final class OreSimulator {

    /** {@code Mth.SIN}: vanilla's OreFeature uses the table-based sine for sphere radii. */
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
        }
    }

    /** Confidence multipliers. */
    static final float BIOME_UNKNOWN_FACTOR = 0.6f;
    static final float STREAM_DRIFT_FACTOR = 0.5f;
    static final float AIR_UNKNOWN_FACTOR = 0.7f;

    private final WorldgenRng rng = WorldgenRng.decoration();
    private double[] spheres = new double[64 * 4];
    private int[] stamp = new int[8192];
    private int stampGen;

    static float mthSin(float value) {
        return SIN[(int) (value * 10430.378F) & 65535];
    }

    static int ceil(float value) {
        int i = (int) value;
        return value > (float) i ? i + 1 : i;
    }

    static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    /**
     * Blocks this run already turned into ore (or dirt/gravel occluders). Vanilla's target rule rejects them for later
     * veins, without consuming randomness, so they are reported as {@link TerrainOracle#OTHER}.
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet placed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private byte classify(TerrainOracle terrain, OreSpec spec, int x, int y, int z) {
        if (!placed.isEmpty() && placed.contains(com.utilitysuite.world.BlockPosPacking.pack(x, y, z))) {
            return TerrainOracle.OTHER;
        }
        return terrain.classify(spec, x, y, z);
    }

    private void emit(OreSink sink, int specIndex, OreSpec spec, int x, int y, int z, int vein, float conf) {
        placed.add(com.utilitysuite.world.BlockPosPacking.pack(x, y, z));
        if (!spec.occluder()) {
            sink.accept(specIndex, x, y, z, vein, conf);
        }
    }

    /** Forgets blocks placed by earlier calls (done automatically per {@link #simulateChunk}). */
    public void resetPlaced() {
        placed.clear();
    }

    /**
     * Simulates every spec for one chunk. Specs must be sorted by (step, index), the order vanilla runs them in,
     * because earlier veins change what later veins may replace.
     *
     * @param minY           dimension minimum build Y
     * @param height         dimension build height
     * @param baseConfidence confidence for an exact, fully known prediction (lowered for uncertain chunks)
     */
    public void simulateChunk(long worldSeed, int chunkX, int chunkZ, OreSpec[] specs, int minY, int height,
                              TerrainOracle terrain, BiomeOracle biomes, float baseConfidence, OreSink sink) {
        int ox = chunkX << 4;
        int oz = chunkZ << 4;
        placed.clear();
        for (int s = 0; s < specs.length; s++) {
            long decorationSeed = rng.setDecorationSeed(worldSeed, ox, oz);
            simulateFeature(decorationSeed, s, specs[s], ox, minY, oz, minY, height, terrain, biomes, baseConfidence,
                    sink);
        }
    }

    /** Simulates one placed feature; exposed for tests that compare a single feature against vanilla. */
    public void simulateFeature(long decorationSeed, int specIndex, OreSpec spec, int originX, int originY,
                                int originZ, int minY, int height, TerrainOracle terrain, BiomeOracle biomes,
                                float baseConfidence, OreSink sink) {
        WorldgenRng r = rng;
        r.setFeatureSeed(decorationSeed, spec.index(), spec.step());
        int count;
        if (spec.rarityChance() > 0) {
            count = r.nextFloat() < 1.0F / (float) spec.rarityChance() ? 1 : 0;
        } else if (spec.countMin() != spec.countMax()) {
            count = r.betweenInclusive(spec.countMin(), spec.countMax());
        } else {
            count = spec.countMin();
        }
        boolean drift = false;
        for (int v = 0; v < count; v++) {
            int x = r.nextInt(16) + originX;
            int z = r.nextInt(16) + originZ;
            int y = sampleHeight(r, spec);
            byte biome = biomes.allows(spec, x, y, z);
            if (biome == BiomeOracle.NO) {
                continue;
            }
            float conf = baseConfidence;
            if (biome == BiomeOracle.UNKNOWN) {
                conf *= BIOME_UNKNOWN_FACTOR;
                // Whether the vein ran (and consumed randomness) is unknown, so later attempts may be out of step.
                drift = true;
            }
            if (drift) {
                conf *= STREAM_DRIFT_FACTOR;
            }
            int result = spec.scattered()
                    ? placeScattered(r, specIndex, spec, x, y, z, v, conf, terrain, sink)
                    : placeVein(r, specIndex, spec, x, y, z, v, conf, minY, height, terrain, sink);
            if (result == VEIN_STREAM_UNCERTAIN) {
                drift = true;
            }
        }
    }

    static int sampleHeight(WorldgenRng r, OreSpec spec) {
        int min = spec.minY();
        int max = spec.maxY();
        if (min > max) {
            return min;
        }
        if (!spec.trapezoid()) {
            return r.betweenInclusive(min, max);
        }
        int range = max - min;
        if (spec.plateau() >= range) {
            return r.betweenInclusive(min, max);
        }
        int l = (range - spec.plateau()) / 2;
        int i1 = range - l;
        return min + r.betweenInclusive(0, i1) + r.betweenInclusive(0, l);
    }

    static final int VEIN_EXACT = 0;
    static final int VEIN_STREAM_UNCERTAIN = 1;

    /** {@code OreFeature.place} + {@code doPlace}. Returns whether the random stream may have diverged. */
    private int placeVein(WorldgenRng r, int specIndex, OreSpec spec, int ox, int oy, int oz, int vein, float conf,
                          int minY, int height, TerrainOracle terrain, OreSink sink) {
        int size = spec.size();
        float angle = r.nextFloat() * (float) Math.PI;
        float f1 = (float) size / 8.0F;
        int i = ceil(((float) size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double minX = (double) ox + Math.sin((double) angle) * (double) f1;
        double maxX = (double) ox - Math.sin((double) angle) * (double) f1;
        double minZ = (double) oz + Math.cos((double) angle) * (double) f1;
        double maxZ = (double) oz - Math.cos((double) angle) * (double) f1;
        double minYd = (double) (oy + r.nextInt(3) - 2);
        double maxYd = (double) (oy + r.nextInt(3) - 2);
        int bx = ox - ceil(f1) - i;
        int by = oy - 2 - i;
        int bz = oz - ceil(f1) - i;
        int width = 2 * (ceil(f1) + i);
        int boxHeight = 2 * (2 + i);

        // Surface check: the vein only runs when its box bottom is at or below the surface somewhere in the box.
        boolean uncertain = false;
        int topY = minY + height - 1;
        if (by > topY) {
            return VEIN_EXACT; // above any possible surface: vanilla skips it without further randomness
        }
        boolean passes = false;
        boolean unknownColumn = false;
        for (int cx = bx; cx <= bx + width && !passes; cx++) {
            for (int cz = bz; cz <= bz + width; cz++) {
                int h = terrain.surfaceHeight(cx, cz);
                if (h == TerrainOracle.UNKNOWN_HEIGHT) {
                    unknownColumn = true;
                } else if (by <= h) {
                    passes = true;
                    break;
                }
            }
        }
        if (!passes) {
            if (!unknownColumn) {
                return VEIN_EXACT;
            }
            // Unknown surface: assume it runs (true for anything below a typical surface), flag deep-surface guesses.
            if (by > minY + 100) {
                uncertain = true;
                conf *= STREAM_DRIFT_FACTOR;
            }
        }

        double[] sp = spheres;
        if (sp.length < size * 4) {
            sp = spheres = new double[size * 4];
        }
        for (int k = 0; k < size; k++) {
            float f = (float) k / (float) size;
            double cx = minX + (double) f * (maxX - minX);
            double cy = minYd + (double) f * (maxYd - minYd);
            double cz = minZ + (double) f * (maxZ - minZ);
            double d3 = r.nextDouble() * (double) size / 16.0;
            double radius = ((double) (mthSin((float) Math.PI * f) + 1.0F) * d3 + 1.0) / 2.0;
            sp[k * 4] = cx;
            sp[k * 4 + 1] = cy;
            sp[k * 4 + 2] = cz;
            sp[k * 4 + 3] = radius;
        }
        for (int a = 0; a < size - 1; a++) {
            if (!(sp[a * 4 + 3] <= 0.0)) {
                for (int b = a + 1; b < size; b++) {
                    if (!(sp[b * 4 + 3] <= 0.0)) {
                        double dx = sp[a * 4] - sp[b * 4];
                        double dy = sp[a * 4 + 1] - sp[b * 4 + 1];
                        double dz = sp[a * 4 + 2] - sp[b * 4 + 2];
                        double dr = sp[a * 4 + 3] - sp[b * 4 + 3];
                        if (dr * dr > dx * dx + dy * dy + dz * dz) {
                            if (dr > 0.0) {
                                sp[b * 4 + 3] = -1.0;
                            } else {
                                sp[a * 4 + 3] = -1.0;
                            }
                        }
                    }
                }
            }
        }

        int gen = nextStampGeneration();
        float discard = spec.discardOnAir();
        for (int k = 0; k < size; k++) {
            double rad = sp[k * 4 + 3];
            if (rad < 0.0) {
                continue;
            }
            double cx = sp[k * 4];
            double cy = sp[k * 4 + 1];
            double cz = sp[k * 4 + 2];
            int x0 = Math.max(floor(cx - rad), bx);
            int y0 = Math.max(floor(cy - rad), by);
            int z0 = Math.max(floor(cz - rad), bz);
            int x1 = Math.max(floor(cx + rad), x0);
            int y1 = Math.max(floor(cy + rad), y0);
            int z1 = Math.max(floor(cz + rad), z0);
            for (int x = x0; x <= x1; x++) {
                double ddx = ((double) x + 0.5 - cx) / rad;
                if (!(ddx * ddx < 1.0)) {
                    continue;
                }
                for (int y = y0; y <= y1; y++) {
                    double ddy = ((double) y + 0.5 - cy) / rad;
                    if (!(ddx * ddx + ddy * ddy < 1.0)) {
                        continue;
                    }
                    for (int z = z0; z <= z1; z++) {
                        double ddz = ((double) z + 0.5 - cz) / rad;
                        if (!(ddx * ddx + ddy * ddy + ddz * ddz < 1.0) || y < minY || y >= minY + height) {
                            continue;
                        }
                        int idx = x - bx + (y - by) * width + (z - bz) * width * boxHeight;
                        if (!markOnce(idx, gen)) {
                            continue;
                        }
                        byte state = classify(terrain, spec, x, y, z);
                        if (state == TerrainOracle.AIR || state == TerrainOracle.OTHER) {
                            continue; // not a target: no placement, no randomness
                        }
                        float blockConf = conf;
                        if (state == TerrainOracle.UNKNOWN && spec.terrainDependentStream()) {
                            uncertain = true;
                        }
                        boolean skipAirCheck;
                        if (discard <= 0.0F) {
                            skipAirCheck = true;
                        } else if (discard >= 1.0F) {
                            skipAirCheck = false;
                        } else {
                            skipAirCheck = r.nextFloat() >= discard;
                        }
                        if (!skipAirCheck) {
                            byte air = adjacentAir(terrain, spec, x, y, z);
                            if (air == TerrainOracle.AIR) {
                                continue; // discarded on air exposure
                            }
                            if (air == TerrainOracle.UNKNOWN) {
                                blockConf *= AIR_UNKNOWN_FACTOR;
                            }
                        }
                        emit(sink, specIndex, spec, x, y, z, vein, blockConf);
                    }
                }
            }
        }
        return uncertain ? VEIN_STREAM_UNCERTAIN : VEIN_EXACT;
    }

    /** {@code ScatteredOreFeature.place}. Its stream never depends on terrain (discard chance is 0 or 1). */
    private int placeScattered(WorldgenRng r, int specIndex, OreSpec spec, int ox, int oy, int oz, int vein,
                               float conf, TerrainOracle terrain, OreSink sink) {
        int n = r.nextInt(spec.size() + 1);
        float discard = spec.discardOnAir();
        boolean uncertain = false;
        for (int j = 0; j < n; j++) {
            int mag = Math.min(j, 7);
            int x = ox + Math.round((r.nextFloat() - r.nextFloat()) * (float) mag);
            int y = oy + Math.round((r.nextFloat() - r.nextFloat()) * (float) mag);
            int z = oz + Math.round((r.nextFloat() - r.nextFloat()) * (float) mag);
            byte state = classify(terrain, spec, x, y, z);
            if (state == TerrainOracle.AIR || state == TerrainOracle.OTHER) {
                continue;
            }
            float blockConf = conf;
            boolean skipAirCheck;
            if (discard <= 0.0F) {
                skipAirCheck = true;
            } else if (discard >= 1.0F) {
                skipAirCheck = false;
            } else {
                skipAirCheck = r.nextFloat() >= discard;
                if (state == TerrainOracle.UNKNOWN) {
                    uncertain = true;
                }
            }
            if (!skipAirCheck) {
                byte air = adjacentAir(terrain, spec, x, y, z);
                if (air == TerrainOracle.AIR) {
                    continue;
                }
                if (air == TerrainOracle.UNKNOWN) {
                    blockConf *= AIR_UNKNOWN_FACTOR;
                }
            }
            emit(sink, specIndex, spec, x, y, z, vein, blockConf);
        }
        return uncertain ? VEIN_STREAM_UNCERTAIN : VEIN_EXACT;
    }

    /** AIR if any of the six neighbours is air, UNKNOWN if none is known air but some are unknown, else OTHER. */
    private byte adjacentAir(TerrainOracle t, OreSpec spec, int x, int y, int z) {
        boolean unknown = false;
        for (int d = 0; d < 6; d++) {
            int nx = x + (d == 4 ? -1 : d == 5 ? 1 : 0);
            int ny = y + (d == 0 ? -1 : d == 1 ? 1 : 0);
            int nz = z + (d == 2 ? -1 : d == 3 ? 1 : 0);
            byte s = classify(t, spec, nx, ny, nz);
            if (s == TerrainOracle.AIR) {
                return TerrainOracle.AIR;
            }
            if (s == TerrainOracle.UNKNOWN) {
                unknown = true;
            }
        }
        return unknown ? TerrainOracle.UNKNOWN : TerrainOracle.OTHER;
    }

    private int nextStampGeneration() {
        if (++stampGen == Integer.MAX_VALUE) {
            java.util.Arrays.fill(stamp, 0);
            stampGen = 1;
        }
        return stampGen;
    }

    /** BitSet replacement: true the first time {@code idx} is seen in this vein. */
    private boolean markOnce(int idx, int gen) {
        int[] st = stamp;
        if (idx >= st.length) {
            st = stamp = java.util.Arrays.copyOf(st, Math.max(idx + 1, st.length * 2));
        }
        if (st[idx] == gen) {
            return false;
        }
        st[idx] = gen;
        return true;
    }
}
