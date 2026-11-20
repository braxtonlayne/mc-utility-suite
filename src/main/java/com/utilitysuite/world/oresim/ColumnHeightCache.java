package com.utilitysuite.world.oresim;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/**
 * Bounded cache of generation-time surface heights, stored as one primitive {@code int[256]} per chunk (index
 * {@code (x & 15) * 16 + (z & 15)}) in an access-ordered map keyed by packed chunk position. Heights are produced a
 * whole noise cell at a time by a {@link CellFiller} (one expensive generator setup serves every column of the cell),
 * so each column is computed at most once while its chunk stays cached, across every simulated chunk that asks.
 * <p>
 * Allocation: after warm-up a lookup allocates nothing; evicted chunk arrays are recycled for new chunks. Not
 * thread-safe by itself; {@link #surfaceHeight} is {@code synchronized} so a stray caller cannot corrupt it (the
 * simulation runs on one executor thread, so the lock is uncontended).
 */
public final class ColumnHeightCache {

    /** Produces the heights of all columns of one generator noise cell. */
    public interface CellFiller {
        /**
         * Compute every column of the cell containing block column (x, z) and hand each to
         * {@link ColumnHeightCache#store}. Must store at least column (x, z); {@link TerrainOracle#UNKNOWN_HEIGHT} on
         * failure.
         */
        void fillCell(int x, int z, ColumnHeightCache sink);
    }

    static final int NOT_COMPUTED = Integer.MIN_VALUE + 1;

    private final CellFiller filler;
    private final int capacity;
    private final Long2ObjectLinkedOpenHashMap<int[]> chunks;
    private int[] spare;
    private long lastKey = Long.MIN_VALUE;
    private int[] lastArray;
    private long cellFills;

    public ColumnHeightCache(CellFiller filler, int capacityChunks) {
        this.filler = filler;
        this.capacity = Math.max(1, capacityChunks);
        this.chunks = new Long2ObjectLinkedOpenHashMap<>(this.capacity + 1);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** {@code OCEAN_FLOOR_WG}-style surface height of column (x, z), computing its cell on a miss. */
    public synchronized int surfaceHeight(int x, int z) {
        int[] arr = arrayFor(x >> 4, z >> 4);
        int idx = ((x & 15) << 4) | (z & 15);
        int h = arr[idx];
        if (h == NOT_COMPUTED) {
            cellFills++;
            try {
                filler.fillCell(x, z, this);
            } catch (RuntimeException e) {
                // degrade to unknown below
            }
            // the filler may have evicted/recycled arrays through store(): re-resolve
            arr = arrayFor(x >> 4, z >> 4);
            h = arr[idx];
            if (h == NOT_COMPUTED) {
                h = TerrainOracle.UNKNOWN_HEIGHT;
                arr[idx] = h;
            }
        }
        return h;
    }

    /** Called by the {@link CellFiller} (under this cache's lock) for each computed column. */
    public void store(int x, int z, int height) {
        arrayFor(x >> 4, z >> 4)[((x & 15) << 4) | (z & 15)] = height;
    }

    private int[] arrayFor(int cx, int cz) {
        long key = chunkKey(cx, cz);
        if (key == lastKey && lastArray != null) {
            return lastArray;
        }
        int[] arr = chunks.getAndMoveToLast(key);
        if (arr == null) {
            if (chunks.size() >= capacity) {
                spare = chunks.removeFirst();
            }
            arr = spare != null ? spare : new int[256];
            spare = null;
            java.util.Arrays.fill(arr, NOT_COMPUTED);
            chunks.put(key, arr);
        }
        lastKey = key;
        lastArray = arr;
        return arr;
    }

    /** Number of cell fills so far (diagnostics and tests). */
    public synchronized long cellFills() {
        return cellFills;
    }

    public synchronized int cachedChunks() {
        return chunks.size();
    }
}
