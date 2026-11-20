package com.utilitysuite.world.oresim;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;

/**
 * Immutable predicted ore blocks of one chunk (all selected specs). Positions are packed like
 * {@code BlockPos.asLong()}. Published from the simulation thread to the client thread.
 */
public final class ChunkOreSnapshot {

    public final int chunkX;
    public final int chunkZ;
    /** Generation of the simulator configuration that produced it (stale snapshots are dropped). */
    public final int generation;
    public final boolean uncertainChunk;
    private final OreSpec[] specs;
    private final long[] positions;
    private final byte[] specIndex;
    private final byte[] vein;
    private final float[] confidence;

    private ChunkOreSnapshot(int chunkX, int chunkZ, int generation, boolean uncertainChunk, OreSpec[] specs,
                             long[] positions, byte[] specIndex, byte[] vein, float[] confidence) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.generation = generation;
        this.uncertainChunk = uncertainChunk;
        this.specs = specs;
        this.positions = positions;
        this.specIndex = specIndex;
        this.vein = vein;
        this.confidence = confidence;
    }

    public int size() {
        return positions.length;
    }

    public long pos(int i) {
        return positions[i];
    }

    public OreSpec spec(int i) {
        return specs[specIndex[i]];
    }

    public int veinOrdinal(int i) {
        return vein[i] & 0xFF;
    }

    public float confidence(int i) {
        return confidence[i];
    }

    public String blockId(int i) {
        return spec(i).blockIdAt(BlockPosPacking.y(positions[i]));
    }

    /** Cluster (vein) key used by the knowledge ledger for downranking. */
    public long clusterKey(int i) {
        OreSpec s = spec(i);
        return clusterKey(chunkX, chunkZ, s.step() * 256 + s.index(), veinOrdinal(i));
    }

    /** 22-bit chunk X | 22-bit chunk Z | 12-bit feature key (step*256+index) | 8-bit vein ordinal. */
    public static long clusterKey(int chunkX, int chunkZ, int featureKey, int veinOrdinal) {
        return ((long) chunkX & 0x3FFFFFL) << 42 | ((long) chunkZ & 0x3FFFFFL) << 20
                | ((long) featureKey & 0xFFFL) << 8 | (veinOrdinal & 0xFFL);
    }

    /** Collects simulator output; dedupes positions (the first vein to claim a block wins, like vanilla). */
    public static final class Builder implements OreSink {
        private final int chunkX;
        private final int chunkZ;
        private final int generation;
        private final boolean uncertainChunk;
        private final OreSpec[] specs;
        private final LongArrayList positions = new LongArrayList();
        private final ByteArrayList specIndex = new ByteArrayList();
        private final ByteArrayList vein = new ByteArrayList();
        private final FloatArrayList confidence = new FloatArrayList();
        private final LongOpenHashSet seen = new LongOpenHashSet();

        public Builder(int chunkX, int chunkZ, int generation, boolean uncertainChunk, OreSpec[] specs) {
            if (specs.length > 127) {
                throw new IllegalArgumentException("too many specs");
            }
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.generation = generation;
            this.uncertainChunk = uncertainChunk;
            this.specs = specs;
        }

        @Override
        public void accept(int spec, int x, int y, int z, int veinOrdinal, float conf) {
            long p = BlockPosPacking.pack(x, y, z);
            if (!seen.add(p)) {
                return;
            }
            positions.add(p);
            specIndex.add((byte) spec);
            vein.add((byte) veinOrdinal);
            confidence.add(conf);
        }

        public ChunkOreSnapshot build() {
            return new ChunkOreSnapshot(chunkX, chunkZ, generation, uncertainChunk, specs, positions.toLongArray(),
                    specIndex.toByteArray(), vein.toByteArray(), confidence.toFloatArray());
        }
    }
}
