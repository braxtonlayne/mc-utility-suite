package com.utilitysuite.world.fortress;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Engine-free approximation of Nether Fortress spawn volumes from client data. Vanilla spawns the fortress mobs
 * (blazes, wither skeletons, ...) anywhere inside the bounding boxes of the fortress's pieces; the client does not
 * know the pieces, so each 16x16x16 section that holds fortress blocks (nether bricks, fences, stairs) records the
 * bounding box of those blocks, and touching sections (26-neighbourhood) are grouped into clusters. A cluster with at
 * least a minimum brick count is treated as a fortress (smaller ones are player builds) and its section boxes are the
 * approximate piece boxes.
 * <p>
 * Storage is parallel primitive arrays plus a key-to-slot map; removal swaps the last slot in. Clustering reuses its
 * scratch arrays, so steady-state updates allocate nothing (arrays and the map grow rarely).
 */
public final class FortressSections {

    private final Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap();
    private long[] keys = new long[64];
    private long[] summaries = new long[64];
    private int size;

    // Clustering output and scratch.
    private int[] clusterOf = new int[64];
    private int[] queue = new int[64];
    private int clusterCount;
    private int[] clusterBricks = new int[16];
    private int[] clusterBox = new int[16 * 6];

    public FortressSections() {
        slotOf.defaultReturnValue(-1);
    }

    // ---- summary packing: count (16 bits) | minX minY minZ maxX maxY maxZ (4 bits each) --------------------------

    public static long summary(int count, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (count <= 0) {
            return 0L;
        }
        return ((long) Math.min(count, 0xFFFF) << 24) | ((long) minX << 20) | ((long) minY << 16)
                | ((long) minZ << 12) | ((long) maxX << 8) | ((long) maxY << 4) | maxZ;
    }

    public static int count(long s) {
        return (int) (s >>> 24) & 0xFFFF;
    }

    public static int minX(long s) {
        return (int) (s >>> 20) & 15;
    }

    public static int minY(long s) {
        return (int) (s >>> 16) & 15;
    }

    public static int minZ(long s) {
        return (int) (s >>> 12) & 15;
    }

    public static int maxX(long s) {
        return (int) (s >>> 8) & 15;
    }

    public static int maxY(long s) {
        return (int) (s >>> 4) & 15;
    }

    public static int maxZ(long s) {
        return (int) s & 15;
    }

    /** Section key from section coordinates. */
    public static long key(int sx, int sy, int sz) {
        return BlockPosPacking.pack(sx, sy, sz);
    }

    // ---- storage ---------------------------------------------------------------------------------------------------

    /** Stores (count > 0) or removes (summary 0) one section's result. */
    public void put(int sx, int sy, int sz, long summary) {
        long k = key(sx, sy, sz);
        int slot = slotOf.get(k);
        if (summary == 0L) {
            if (slot >= 0) {
                removeSlot(slot);
            }
            return;
        }
        if (slot >= 0) {
            summaries[slot] = summary;
            return;
        }
        if (size == keys.length) {
            keys = java.util.Arrays.copyOf(keys, size * 2);
            summaries = java.util.Arrays.copyOf(summaries, size * 2);
        }
        keys[size] = k;
        summaries[size] = summary;
        slotOf.put(k, size);
        size++;
    }

    private void removeSlot(int slot) {
        slotOf.remove(keys[slot]);
        int last = --size;
        if (slot != last) {
            keys[slot] = keys[last];
            summaries[slot] = summaries[last];
            slotOf.put(keys[slot], slot);
        }
    }

    /** Drops sections whose chunk is farther than {@code maxChunks} (Chebyshev) from the given chunk. */
    public void pruneOutside(int chunkX, int chunkZ, int maxChunks) {
        for (int i = size - 1; i >= 0; i--) {
            long k = keys[i];
            if (Math.abs(BlockPosPacking.x(k) - chunkX) > maxChunks || Math.abs(BlockPosPacking.z(k) - chunkZ) > maxChunks) {
                removeSlot(i);
            }
        }
    }

    public void clear() {
        slotOf.clear();
        size = 0;
        clusterCount = 0;
    }

    public int size() {
        return size;
    }

    public int sectionX(int i) {
        return BlockPosPacking.x(keys[i]);
    }

    public int sectionY(int i) {
        return BlockPosPacking.y(keys[i]);
    }

    public int sectionZ(int i) {
        return BlockPosPacking.z(keys[i]);
    }

    public long summaryAt(int i) {
        return summaries[i];
    }

    // ---- clustering ------------------------------------------------------------------------------------------------

    /** Groups touching sections; read the result with {@link #clusterOf}, {@link #clusterBricks}, {@link #clusterBox}. */
    public void cluster() {
        if (clusterOf.length < size) {
            clusterOf = new int[keys.length];
            queue = new int[keys.length];
        }
        java.util.Arrays.fill(clusterOf, 0, size, -1);
        clusterCount = 0;
        for (int start = 0; start < size; start++) {
            if (clusterOf[start] >= 0) {
                continue;
            }
            int c = clusterCount++;
            if (clusterBricks.length <= c) {
                clusterBricks = java.util.Arrays.copyOf(clusterBricks, c * 2);
                clusterBox = java.util.Arrays.copyOf(clusterBox, c * 12);
            }
            int bricks = 0;
            int x0 = Integer.MAX_VALUE;
            int y0 = Integer.MAX_VALUE;
            int z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int y1 = Integer.MIN_VALUE;
            int z1 = Integer.MIN_VALUE;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            clusterOf[start] = c;
            while (head < tail) {
                int i = queue[head++];
                long s = summaries[i];
                int sx = sectionX(i);
                int sy = sectionY(i);
                int sz = sectionZ(i);
                bricks += count(s);
                x0 = Math.min(x0, (sx << 4) + minX(s));
                y0 = Math.min(y0, (sy << 4) + minY(s));
                z0 = Math.min(z0, (sz << 4) + minZ(s));
                x1 = Math.max(x1, (sx << 4) + maxX(s) + 1);
                y1 = Math.max(y1, (sy << 4) + maxY(s) + 1);
                z1 = Math.max(z1, (sz << 4) + maxZ(s) + 1);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if ((dx | dy | dz) == 0) {
                                continue;
                            }
                            int j = slotOf.get(key(sx + dx, sy + dy, sz + dz));
                            if (j >= 0 && clusterOf[j] < 0) {
                                clusterOf[j] = c;
                                queue[tail++] = j;
                            }
                        }
                    }
                }
            }
            clusterBricks[c] = bricks;
            int b = c * 6;
            clusterBox[b] = x0;
            clusterBox[b + 1] = y0;
            clusterBox[b + 2] = z0;
            clusterBox[b + 3] = x1;
            clusterBox[b + 4] = y1;
            clusterBox[b + 5] = z1;
        }
    }

    public int clusterCount() {
        return clusterCount;
    }

    public int clusterOf(int sectionSlot) {
        return clusterOf[sectionSlot];
    }

    public int clusterBricks(int c) {
        return clusterBricks[c];
    }

    /** Cluster box component: 0..2 = min x/y/z (inclusive), 3..5 = max x/y/z (exclusive), block coordinates. */
    public int clusterBox(int c, int component) {
        return clusterBox[c * 6 + component];
    }

    // ---- per-section boxes (block coordinates, max exclusive) -----------------------------------------------------

    public int boxMinX(int i) {
        return (sectionX(i) << 4) + minX(summaries[i]);
    }

    public int boxMinY(int i) {
        return (sectionY(i) << 4) + minY(summaries[i]);
    }

    public int boxMinZ(int i) {
        return (sectionZ(i) << 4) + minZ(summaries[i]);
    }

    public int boxMaxX(int i) {
        return (sectionX(i) << 4) + maxX(summaries[i]) + 1;
    }

    public int boxMaxY(int i) {
        return (sectionY(i) << 4) + maxY(summaries[i]) + 1;
    }

    public int boxMaxZ(int i) {
        return (sectionZ(i) << 4) + maxZ(summaries[i]) + 1;
    }

    // ---- spawn rule ------------------------------------------------------------------------------------------------

    public static final int SPAWN_NONE = 0;
    /** Blazes (any light, 2 blocks of room). */
    public static final int SPAWN_BLAZE = 1;
    /** Wither skeletons too (3 blocks of room, block light at or below the Nether limit of 7). */
    public static final int SPAWN_WITHER = 2;
    /** Nether {@code monster_spawn_light_level}: wither skeletons / skeletons need block light at or below this. */
    public static final int NETHER_MONSTER_LIGHT = 7;

    /**
     * Fortress spawn floor class for a floor block inside a piece box.
     *
     * @param floorValid the floor accepts a spawn on top
     * @param emptyAbove consecutive empty spawn blocks directly above the floor (0..3)
     * @param blockLight block light at the feet
     * @param maxLight   highest light for wither skeletons (vanilla {@link #NETHER_MONSTER_LIGHT})
     */
    public static int spawnClass(boolean floorValid, int emptyAbove, int blockLight, int maxLight) {
        if (!floorValid || emptyAbove < 2) {
            return SPAWN_NONE;
        }
        return emptyAbove >= 3 && blockLight <= maxLight ? SPAWN_WITHER : SPAWN_BLAZE;
    }
}
