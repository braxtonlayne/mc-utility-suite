package com.utilitysuite.world.atlas;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

/**
 * Engine-free store of sampled map columns, one {@link Tile} per chunk (16x16 colours + surface heights), keyed by
 * packed chunk coordinates. Written on the client thread by the atlas sampler; a tile's arrays are allocated once and
 * overwritten in place on re-sample. Regions of {@value #REGION_CHUNKS}x{@value #REGION_CHUNKS} chunks carry a
 * version counter so the renderer rebuilds a region texture only when one of its tiles changed. Capped at
 * {@code maxTiles}; the tiles farthest from the player are dropped first.
 */
public final class AtlasTileStore {

    public static final int REGION_CHUNKS = 16;
    public static final int REGION_BLOCKS = REGION_CHUNKS * 16;
    /** Height value for a column that was not loaded when sampled. */
    public static final short NO_HEIGHT = Short.MIN_VALUE;

    /** One chunk of map data. Index = (z &amp; 15) * 16 + (x &amp; 15). */
    public static final class Tile {
        public final int chunkX;
        public final int chunkZ;
        public final int[] argb = new int[256];
        public final short[] height = new short[256];
        public long sampledTick;

        Tile(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            java.util.Arrays.fill(height, NO_HEIGHT);
        }
    }

    private final Long2ObjectOpenHashMap<Tile> tiles = new Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap regionVersion =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private int maxTiles;
    private int version;

    public AtlasTileStore(int maxTiles) {
        this.maxTiles = Math.max(64, maxTiles);
    }

    public static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    public static int regionOf(int chunk) {
        return Math.floorDiv(chunk, REGION_CHUNKS);
    }

    public void setMaxTiles(int max) {
        maxTiles = Math.max(64, max);
    }

    public int maxTiles() {
        return maxTiles;
    }

    public int size() {
        return tiles.size();
    }

    /** Bumped on every tile write; cheap "did anything change" check for the 3D mesh. */
    public int version() {
        return version;
    }

    public Tile get(int chunkX, int chunkZ) {
        return tiles.get(key(chunkX, chunkZ));
    }

    /** The tile for writing (created on first use). Call {@link #markWritten} after filling it. */
    public Tile obtain(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        Tile t = tiles.get(k);
        if (t == null) {
            t = new Tile(chunkX, chunkZ);
            tiles.put(k, t);
        }
        return t;
    }

    public void markWritten(Tile t, long tick) {
        t.sampledTick = tick;
        version++;
        regionVersion.addTo(key(regionOf(t.chunkX), regionOf(t.chunkZ)), 1);
    }

    public int regionVersion(int regionX, int regionZ) {
        return regionVersion.get(key(regionX, regionZ));
    }

    /** Colour at a block column, or {@code fallback} when not sampled. */
    public int colorAt(int bx, int bz, int fallback) {
        Tile t = tiles.get(key(bx >> 4, bz >> 4));
        return t == null ? fallback : t.argb[(bz & 15) * 16 + (bx & 15)];
    }

    /** Surface height at a block column, or {@link #NO_HEIGHT}. */
    public int heightAt(int bx, int bz) {
        Tile t = tiles.get(key(bx >> 4, bz >> 4));
        return t == null ? NO_HEIGHT : t.height[(bz & 15) * 16 + (bx & 15)];
    }

    /** Drops tiles farthest from the given chunk until the cap holds. Rare (only when the cap is exceeded). */
    public int evictBeyondCap(int centerChunkX, int centerChunkZ) {
        int over = tiles.size() - maxTiles;
        if (over <= 0) {
            return 0;
        }
        // Find a distance threshold: drop everything beyond the radius that keeps about maxTiles * 0.9 tiles.
        int keep = maxTiles * 9 / 10;
        int[] d = new int[tiles.size()];
        int n = 0;
        for (Tile t : tiles.values()) {
            d[n++] = Math.max(Math.abs(t.chunkX - centerChunkX), Math.abs(t.chunkZ - centerChunkZ));
        }
        java.util.Arrays.sort(d, 0, n);
        int limit = d[Math.max(0, Math.min(n - 1, keep))];
        int removed = 0;
        ObjectIterator<Long2ObjectMap.Entry<Tile>> it = tiles.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Tile t = it.next().getValue();
            if (Math.max(Math.abs(t.chunkX - centerChunkX), Math.abs(t.chunkZ - centerChunkZ)) >= limit) {
                regionVersion.addTo(key(regionOf(t.chunkX), regionOf(t.chunkZ)), 1);
                it.remove();
                removed++;
            }
        }
        version++;
        return removed;
    }

    public void clear() {
        tiles.clear();
        regionVersion.clear();
        version++;
    }

    /**
     * Copies one region's colours into {@code out} (REGION_BLOCKS x REGION_BLOCKS, row-major, 0xAARRGGBB);
     * unsampled columns get {@code empty}. Client thread (the map is not thread safe); a plain copy of pre-sampled data,
     * no world access, so the renderer budgets a couple of regions per frame.
     */
    public void composeRegion(int regionX, int regionZ, int[] out, int empty) {
        int baseCx = regionX * REGION_CHUNKS;
        int baseCz = regionZ * REGION_CHUNKS;
        for (int cz = 0; cz < REGION_CHUNKS; cz++) {
            for (int cx = 0; cx < REGION_CHUNKS; cx++) {
                Tile t = tiles.get(key(baseCx + cx, baseCz + cz));
                for (int lz = 0; lz < 16; lz++) {
                    int row = (cz * 16 + lz) * REGION_BLOCKS + cx * 16;
                    if (t == null) {
                        java.util.Arrays.fill(out, row, row + 16, empty);
                    } else {
                        System.arraycopy(t.argb, lz * 16, out, row, 16);
                    }
                }
            }
        }
    }
}
