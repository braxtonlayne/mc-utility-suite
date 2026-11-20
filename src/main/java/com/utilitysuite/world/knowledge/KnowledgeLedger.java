package com.utilitysuite.world.knowledge;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-world record of what verification established: positions where a prediction turned out wrong (verified misses,
 * with a miss count per prediction cluster so the whole vein is downranked), and blocks confirmed by observation.
 * Persisted compactly as packed longs ({@link #write} / {@link #read}). Thread-safe (synchronized; reads are cheap).
 */
public final class KnowledgeLedger {

    private static final int MAGIC = 0x55534B4C; // "USKL"
    private static final int VERSION = 1;
    /** Observed-block cap per dimension (oldest are not tracked; compact by design). */
    public static final int MAX_OBSERVED_PER_DIMENSION = 200_000;

    private static final class Dim {
        final LongOpenHashSet misses = new LongOpenHashSet();
        final Long2IntOpenHashMap clusterMisses = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap chunkMisses = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap chunkHits = new Long2IntOpenHashMap();
        final Long2ObjectOpenHashMap<String> observed = new Long2ObjectOpenHashMap<>();
    }

    private final Map<String, Dim> dims = new HashMap<>();
    private final Map<String, String> interned = new HashMap<>();
    private boolean dirty;

    /** Clusters with at least this many misses are suppressed entirely. */
    private int suppressAfterMisses = 2;
    /** Confidence multiplier per miss below the suppression threshold. */
    private float downrankPerMiss = 0.35f;

    public synchronized void setPolicy(int suppressAfterMisses, float downrankPerMiss) {
        this.suppressAfterMisses = Math.max(1, suppressAfterMisses);
        this.downrankPerMiss = Math.max(0f, Math.min(1f, downrankPerMiss));
    }

    private Dim dim(String dimension) {
        return dims.computeIfAbsent(dimension, d -> new Dim());
    }

    private static long chunkOf(long pos) {
        return BlockPosPacking.chunk(BlockPosPacking.x(pos) >> 4, BlockPosPacking.z(pos) >> 4);
    }

    /** Records a miss. Returns false when the position was already a miss or was confirmed by observation. */
    public synchronized boolean recordMiss(String dimension, long pos, long clusterKey) {
        Dim d = dim(dimension);
        if (d.observed.containsKey(pos) || !d.misses.add(pos)) {
            return false;
        }
        d.clusterMisses.addTo(clusterKey, 1);
        d.chunkMisses.addTo(chunkOf(pos), 1);
        dirty = true;
        return true;
    }

    /** Records a confirmed block. A confirmation also clears an earlier miss at that position. */
    public synchronized void recordObserved(String dimension, long pos, String blockId) {
        Dim d = dim(dimension);
        String id = interned.computeIfAbsent(blockId, k -> k);
        String prev = d.observed.get(pos);
        if (id.equals(prev)) {
            return;
        }
        if (prev == null && d.observed.size() >= MAX_OBSERVED_PER_DIMENSION) {
            return;
        }
        d.observed.put(pos, id);
        if (prev == null) {
            d.chunkHits.addTo(chunkOf(pos), 1);
        }
        d.misses.remove(pos);
        dirty = true;
    }

    public synchronized boolean isMiss(String dimension, long pos) {
        Dim d = dims.get(dimension);
        return d != null && d.misses.contains(pos);
    }

    public synchronized String observedAt(String dimension, long pos) {
        Dim d = dims.get(dimension);
        return d == null ? null : d.observed.get(pos);
    }

    public synchronized int clusterMisses(String dimension, long clusterKey) {
        Dim d = dims.get(dimension);
        return d == null ? 0 : d.clusterMisses.get(clusterKey);
    }

    /** Confidence multiplier for a prediction cluster: 1 untouched, smaller per miss, 0 when suppressed. */
    public synchronized float clusterFactor(String dimension, long clusterKey) {
        int misses = clusterMisses(dimension, clusterKey);
        if (misses <= 0) {
            return 1f;
        }
        if (misses >= suppressAfterMisses) {
            return 0f;
        }
        float f = 1f;
        for (int i = 0; i < misses; i++) {
            f *= downrankPerMiss;
        }
        return f;
    }

    /** A chunk whose predictions keep failing (several misses, no hit) is probably old-version or custom terrain. */
    public synchronized boolean isChunkSuspect(String dimension, int chunkX, int chunkZ) {
        Dim d = dims.get(dimension);
        if (d == null) {
            return false;
        }
        long c = BlockPosPacking.chunk(chunkX, chunkZ);
        return d.chunkMisses.get(c) >= 4 && d.chunkHits.get(c) == 0;
    }

    /** Receives observed blocks. */
    @FunctionalInterface
    public interface ObservedVisitor {
        void visit(long pos, String blockId);
    }

    /** Visits observed positions matching any of the ids within {@code radius} blocks of {@code origin}. */
    public synchronized void forEachObserved(String dimension, java.util.Set<String> ids, long origin, int radius,
                                             ObservedVisitor visitor) {
        Dim d = dims.get(dimension);
        if (d == null) {
            return;
        }
        long r2 = (long) radius * radius;
        for (Long2ObjectMap.Entry<String> e : d.observed.long2ObjectEntrySet()) {
            if (ids.contains(e.getValue()) && BlockPosPacking.distSq(e.getLongKey(), origin) <= r2) {
                visitor.visit(e.getLongKey(), e.getValue());
            }
        }
    }

    public synchronized int missCount(String dimension) {
        Dim d = dims.get(dimension);
        return d == null ? 0 : d.misses.size();
    }

    public synchronized int observedCount(String dimension) {
        Dim d = dims.get(dimension);
        return d == null ? 0 : d.observed.size();
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void clearDirty() {
        dirty = false;
    }

    public synchronized void clear() {
        dims.clear();
        dirty = true;
    }

    // ---- persistence ------------------------------------------------------------------------------------------------

    /** Binary, compact: per dimension packed miss positions, (cluster, count) pairs, and observed (pos, id index). */
    public synchronized void write(DataOutput out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(dims.size());
        for (Map.Entry<String, Dim> e : dims.entrySet()) {
            Dim d = e.getValue();
            out.writeUTF(e.getKey());
            out.writeInt(d.misses.size());
            for (LongIterator it = d.misses.iterator(); it.hasNext(); ) {
                out.writeLong(it.nextLong());
            }
            out.writeInt(d.clusterMisses.size());
            for (Long2IntOpenHashMap.Entry c : d.clusterMisses.long2IntEntrySet()) {
                out.writeLong(c.getLongKey());
                out.writeInt(c.getIntValue());
            }
            Object2IntOpenHashMap<String> table = new Object2IntOpenHashMap<>();
            List<String> strings = new ArrayList<>();
            for (String id : d.observed.values()) {
                if (!table.containsKey(id)) {
                    table.put(id, strings.size());
                    strings.add(id);
                }
            }
            out.writeInt(strings.size());
            for (String s : strings) {
                out.writeUTF(s);
            }
            out.writeInt(d.observed.size());
            for (Long2ObjectMap.Entry<String> o : d.observed.long2ObjectEntrySet()) {
                out.writeLong(o.getLongKey());
                out.writeShort(table.getInt(o.getValue()));
            }
        }
    }

    /** Replaces the contents with the data written by {@link #write}. */
    public synchronized void read(DataInput in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a knowledge ledger");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported ledger version " + version);
        }
        dims.clear();
        int nd = in.readInt();
        for (int i = 0; i < nd; i++) {
            String name = in.readUTF();
            Dim d = dim(name);
            int nm = in.readInt();
            for (int j = 0; j < nm; j++) {
                long p = in.readLong();
                d.misses.add(p);
                d.chunkMisses.addTo(chunkOf(p), 1);
            }
            int nc = in.readInt();
            for (int j = 0; j < nc; j++) {
                d.clusterMisses.put(in.readLong(), in.readInt());
            }
            int ns = in.readInt();
            String[] strings = new String[ns];
            for (int j = 0; j < ns; j++) {
                strings[j] = interned.computeIfAbsent(in.readUTF(), k -> k);
            }
            int no = in.readInt();
            for (int j = 0; j < no; j++) {
                long p = in.readLong();
                d.observed.put(p, strings[in.readUnsignedShort()]);
                d.chunkHits.addTo(chunkOf(p), 1);
            }
        }
        dirty = false;
    }
}
