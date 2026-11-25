package com.utilitysuite.world.sonar;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.render.LineSink;

/**
 * Immutable result of one sonar sample: packed block positions ordered by whole-block distance from the pulse origin,
 * with a bucket index so the per-frame pass only walks the shell the wavefront is lighting. Built off-thread by
 * {@link SurfaceExtractor}, read on the render thread.
 */
public final class PointCloud {

    public static final byte SURFACE = 0;
    public static final byte SPAWNER = 1;
    public static final byte ORE = 2;

    public static final PointCloud EMPTY = new PointCloud(0, 0, 0, new long[0], new float[0], new byte[0],
            new int[] {0, 0});

    private static final int COLOR_SURFACE = 0x40C8FF;
    private static final int COLOR_SPAWNER = 0xFF4040;
    private static final int COLOR_ORE = 0xFFD040;

    public final int originX;
    public final int originY;
    public final int originZ;
    private final long[] pos;
    private final float[] dist;
    private final byte[] kind;
    /** bucketStart[d] = first index with floor(distance) >= d; last entry = size. */
    private final int[] bucketStart;

    PointCloud(int originX, int originY, int originZ, long[] pos, float[] dist, byte[] kind, int[] bucketStart) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.pos = pos;
        this.dist = dist;
        this.kind = kind;
        this.bucketStart = bucketStart;
    }

    public int size() {
        return pos.length;
    }

    public long pos(int i) {
        return pos[i];
    }

    public float dist(int i) {
        return dist[i];
    }

    public byte kind(int i) {
        return kind[i];
    }

    /** First index whose whole-block distance is at least {@code d}. */
    public int firstAtOrBeyond(float d) {
        int b = (int) d;
        if (b <= 0) {
            return 0;
        }
        if (b >= bucketStart.length) {
            return pos.length;
        }
        return bucketStart[b];
    }

    /**
     * Per-frame emitter: a small star at every point the front has passed and that has not faded yet, plus the thin
     * ring of the front. Allocation-free; returns the number of points drawn.
     */
    public int emit(LineSink s, Wavefront wave, long nowMs, double camX, double camY, double camZ, int ringColor,
                    int ringX, int ringY, int ringZ) {
        if (!wave.active(nowMs)) {
            return 0;
        }
        float radius = wave.radius(nowMs);
        if (wave.expanding(nowMs)) {
            float ringAlpha = 1f - radius / Math.max(1f, wave.range()) * 0.7f;
            int argb = ((int) (ringAlpha * 200) << 24) | (ringColor & 0xFFFFFF);
            float cx = (float) (ringX + 0.5 - camX);
            float cy = (float) (ringY + 0.5 - camY);
            float cz = (float) (ringZ + 0.5 - camZ);
            com.utilitysuite.world.render.Shapes.ring(s, cx, cy, cz, radius, argb);
        }
        int from = firstAtOrBeyond(wave.minVisible(nowMs));
        int to = firstAtOrBeyond(radius + 1f);
        int drawn = 0;
        for (int i = from; i < to; i++) {
            float a = wave.alpha(dist[i], nowMs);
            if (a <= 0f) {
                continue;
            }
            int rgb = kind[i] == SPAWNER ? COLOR_SPAWNER : kind[i] == ORE ? COLOR_ORE : COLOR_SURFACE;
            int argb = ((int) (a * 255) << 24) | rgb;
            long p = pos[i];
            float x = (float) (BlockPosPacking.x(p) + 0.5 - camX);
            float y = (float) (BlockPosPacking.y(p) + 0.5 - camY);
            float z = (float) (BlockPosPacking.z(p) + 0.5 - camZ);
            float r = kind[i] == SURFACE ? 0.18f : 0.45f;
            s.line(x - r, y, z, x + r, y, z, argb);
            s.line(x, y - r, z, x, y + r, z, argb);
            s.line(x, y, z - r, x, y, z + r, argb);
            drawn++;
        }
        return drawn;
    }
}
