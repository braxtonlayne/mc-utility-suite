package com.utilitysuite.world.minimap;

import java.util.Arrays;

/**
 * Engine-agnostic ring buffer of minimap texels. The texture is {@code size x size} texels and wraps (GL_REPEAT), so
 * world texel {@code (tx, tz)} always lives at ring slot {@code (floorMod(tx, size), floorMod(tz, size))}. Moving the
 * player never copies pixels: each slot remembers which world texel it holds, so a slot is stale exactly when its
 * stored coordinate differs from the one the current centre expects there. Re-centring is therefore free; stale slots
 * are found by walking a precomputed centre-out spiral and refreshed under a per-tick budget.
 * <p>
 * Allocation free after construction. Client thread only.
 */
public final class TexelRing {

    /** Samples one world texel and returns its 0xAARRGGBB colour. */
    @FunctionalInterface
    public interface Sampler {
        int sample(int texX, int texZ);
    }

    /** Receives every texel write (ring slot coordinates). */
    @FunctionalInterface
    public interface PixelSink {
        void set(int x, int y, int argb);
    }

    private static final int EMPTY = Integer.MIN_VALUE;

    private final int size;
    private final int mask;
    private final int[] storedX;
    private final int[] storedZ;
    /** Centre-out offsets, packed as (dx + 32768) << 16 | (dz + 32768), within radius size/2 - 1. */
    private final int[] spiral;
    private int cursor;
    private int refreshCursor;
    private int centerX = EMPTY;
    private int centerZ = EMPTY;

    /** @param size power of two */
    public TexelRing(int size) {
        if (Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("size must be a power of two");
        }
        this.size = size;
        this.mask = size - 1;
        this.storedX = new int[size * size];
        this.storedZ = new int[size * size];
        this.spiral = buildSpiral(size / 2 - 1);
        clear();
    }

    public int size() {
        return size;
    }

    public int spiralLength() {
        return spiral.length;
    }

    /** Ring slot of a world texel coordinate along one axis. */
    public static int slot(int tex, int size) {
        return Math.floorMod(tex, size);
    }

    /** Linear ring index of a world texel. */
    public int index(int texX, int texZ) {
        return (texZ & mask) * size + (texX & mask);
    }

    /** Forgets every texel (zoom, style or dimension change). */
    public void clear() {
        Arrays.fill(storedX, EMPTY);
        Arrays.fill(storedZ, EMPTY);
        cursor = 0;
        refreshCursor = 0;
    }

    /** True if the slot currently holds world texel (texX, texZ). */
    public boolean holds(int texX, int texZ) {
        int i = index(texX, texZ);
        return storedX[i] == texX && storedZ[i] == texZ;
    }

    /**
     * One budgeted update step.
     *
     * @param cx         centre world texel x
     * @param cz         centre world texel z
     * @param maxUpdates at most this many {@link Sampler#sample} calls
     * @param maxChecks  at most this many spiral slots examined
     * @param refresh    extra re-samples of already current texels once nothing is stale (catches block changes)
     * @return number of samples taken
     */
    public int update(int cx, int cz, int maxUpdates, int maxChecks, int refresh, Sampler sampler, PixelSink sink) {
        if (cx != centerX || cz != centerZ) {
            centerX = cx;
            centerZ = cz;
            cursor = 0; // new edge texels appear; restart from the centre (current texels are a cheap compare)
        }
        int updates = 0;
        int checks = 0;
        int n = spiral.length;
        while (cursor < n && checks < maxChecks) {
            int packed = spiral[cursor];
            int tx = cx + ((packed >>> 16) - 32768);
            int tz = cz + ((packed & 0xFFFF) - 32768);
            int i = index(tx, tz);
            checks++;
            if (storedX[i] != tx || storedZ[i] != tz) {
                if (updates >= maxUpdates) {
                    break; // resume here next tick
                }
                write(i, tx, tz, sampler.sample(tx, tz), sink);
                updates++;
            }
            cursor++;
        }
        if (cursor >= n) {
            // Everything current: slow round-robin refresh.
            for (int k = 0; k < refresh && updates < maxUpdates; k++) {
                if (refreshCursor >= n) {
                    refreshCursor = 0;
                }
                int packed = spiral[refreshCursor++];
                int tx = cx + ((packed >>> 16) - 32768);
                int tz = cz + ((packed & 0xFFFF) - 32768);
                write(index(tx, tz), tx, tz, sampler.sample(tx, tz), sink);
                updates++;
            }
        }
        return updates;
    }

    private void write(int i, int tx, int tz, int argb, PixelSink sink) {
        storedX[i] = tx;
        storedZ[i] = tz;
        sink.set(i & mask, i / size, argb);
    }

    /** True once every texel within the spiral radius matches the current centre. */
    public boolean settled() {
        return cursor >= spiral.length;
    }

    static int[] buildSpiral(int radius) {
        int count = 0;
        int r2 = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= r2) {
                    count++;
                }
            }
        }
        long[] keyed = new long[count];
        int k = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= r2) {
                    keyed[k++] = (long) d2 << 32 | ((dx + 32768) << 16 | (dz + 32768)) & 0xFFFFFFFFL;
                }
            }
        }
        Arrays.sort(keyed);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = (int) keyed[i];
        }
        return out;
    }
}
