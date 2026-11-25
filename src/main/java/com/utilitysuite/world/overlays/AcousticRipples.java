package com.utilitysuite.world.overlays;

import com.utilitysuite.tools.packetlab.AcousticRadar;

/**
 * Preallocated pool of animated echo ripples for acoustic pings: each ping becomes {@link #RINGS} concentric rings that
 * expand from the sound origin with an ease-out curve and fade over about one second, colour-coded by the
 * {@link AcousticRadar} category. Also holds the screen-edge cue math for off-screen sources. Pure logic: no engine
 * types, no allocation after construction (the pool overwrites its oldest ripple when full).
 */
public final class AcousticRipples {

    public static final int RINGS = 3;
    public static final long STAGGER_MS = 150L;
    public static final long RING_LIFE_MS = 700L;
    /** Last ring ends at {@code (RINGS - 1) * STAGGER_MS + RING_LIFE_MS} = 1000 ms. */
    public static final long TOTAL_MS = (RINGS - 1) * STAGGER_MS + RING_LIFE_MS;
    public static final int SEGMENTS = 32;

    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = (Math.PI * 2.0 * i) / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    /** Receives one ring to draw. */
    @FunctionalInterface
    public interface RingVisitor {
        void ring(double x, double y, double z, float radius, int argb);
    }

    private final int capacity;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final long[] starts;
    private final int[] cats;
    private int head;
    private int size;
    private long radarSeq;
    private float maxRadius = 4.0f;

    private final AcousticRadar.Visitor ingestVisitor = this::onPing;

    public AcousticRipples(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        xs = new double[capacity];
        ys = new double[capacity];
        zs = new double[capacity];
        starts = new long[capacity];
        cats = new int[capacity];
    }

    public void setMaxRadius(float r) {
        maxRadius = Math.max(0.5f, r);
    }

    public float maxRadius() {
        return maxRadius;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return size;
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    /** Adds a ripple starting at {@code startMs}; overwrites the oldest when full. */
    public void spawn(double x, double y, double z, long startMs, int category) {
        int i = head;
        xs[i] = x;
        ys[i] = y;
        zs[i] = z;
        starts[i] = startMs;
        cats[i] = category;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    /** Pulls pings recorded since the last call (positional pings only; global bearings have no origin). */
    public void ingest(AcousticRadar radar) {
        if (radar != null) {
            radarSeq = radar.forEachSince(radarSeq, ingestVisitor);
        }
    }

    private void onPing(boolean global, double x, double y, double z, double dx, double dz, long ts, int cat) {
        if (!global) {
            spawn(x, y, z, ts, cat);
        }
    }

    /** Number of ripples still animating at {@code nowMs}. */
    public int activeCount(long nowMs) {
        int n = 0;
        for (int k = 0; k < size; k++) {
            long age = nowMs - starts[idx(k)];
            if (age >= 0 && age <= TOTAL_MS) {
                n++;
            }
        }
        return n;
    }

    /** Visits every visible ring at {@code nowMs}. Allocates nothing. @return rings visited */
    public int forEachRing(long nowMs, RingVisitor v) {
        int drawn = 0;
        for (int k = 0; k < size; k++) {
            int i = idx(k);
            long age = nowMs - starts[i];
            if (age < 0 || age > TOTAL_MS) {
                continue;
            }
            int rgb = AcousticRadar.categoryColor(cats[i]) & 0x00FFFFFF;
            for (int r = 0; r < RINGS; r++) {
                float t = ringProgress(age, r);
                if (t < 0f) {
                    continue;
                }
                int alpha = ringAlpha(t);
                if (alpha <= 0) {
                    continue;
                }
                v.ring(xs[i], ys[i], zs[i], ringRadius(t, maxRadius), (alpha << 24) | rgb);
                drawn++;
            }
        }
        return drawn;
    }

    private int idx(int k) {
        return (head - size + k + capacity) % capacity;
    }

    /** Progress of ring {@code ring} in {@code [0, 1]}, or -1 when it has not started or has finished. */
    public static float ringProgress(long ageMs, int ring) {
        long local = ageMs - ring * STAGGER_MS;
        if (local < 0 || local > RING_LIFE_MS) {
            return -1f;
        }
        return (float) local / (float) RING_LIFE_MS;
    }

    /** Ease-out cubic expansion. */
    public static float ringRadius(float t, float maxRadius) {
        float u = 1f - t;
        return maxRadius * (1f - u * u * u);
    }

    /** Alpha 0..255 fading out as the ring grows. */
    public static int ringAlpha(float t) {
        float u = 1f - t;
        return Math.max(0, Math.min(255, (int) (230f * u * u)));
    }

    public static float cos(int segment) {
        return COS[segment];
    }

    public static float sin(int segment) {
        return SIN[segment];
    }

    // ---- screen-edge cues ------------------------------------------------------------------------------------------

    /**
     * Yaw of the source relative to the camera in degrees ({@code -180..180}, 0 = straight ahead, positive = to the
     * right), using Minecraft's yaw convention (yaw 0 faces +Z, 90 faces -X).
     */
    public static float relativeYaw(float cameraYawDeg, double dx, double dz) {
        float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = target - cameraYawDeg;
        rel %= 360f;
        if (rel >= 180f) {
            rel -= 360f;
        } else if (rel < -180f) {
            rel += 360f;
        }
        return rel;
    }

    /** True when the source lies outside the horizontal field of view. */
    public static boolean offScreen(float relYawDeg, float horizontalFovDeg) {
        return Math.abs(relYawDeg) > horizontalFovDeg * 0.5f;
    }

    /** Horizontal FOV from Minecraft's vertical FOV and the window aspect ratio. */
    public static float horizontalFov(float verticalFovDeg, float aspect) {
        double v = Math.toRadians(verticalFovDeg) * 0.5;
        return (float) Math.toDegrees(2.0 * Math.atan(Math.tan(v) * aspect));
    }

    /**
     * Places an edge cue for a source at {@code relYawDeg} on the border of a {@code w x h} screen inset by
     * {@code margin}: 0 degrees points to the top edge, +90 the right edge, 180 the bottom. Writes x, y and the arrow
     * angle (radians, screen space, 0 = up) into {@code out[0..2]}.
     */
    public static void edgePoint(float relYawDeg, float w, float h, float margin, float[] out) {
        double a = Math.toRadians(relYawDeg);
        float sx = (float) Math.sin(a);
        float sy = (float) -Math.cos(a);
        float hw = Math.max(1f, w * 0.5f - margin);
        float hh = Math.max(1f, h * 0.5f - margin);
        float kx = Math.abs(sx) > 1e-6f ? hw / Math.abs(sx) : Float.MAX_VALUE;
        float ky = Math.abs(sy) > 1e-6f ? hh / Math.abs(sy) : Float.MAX_VALUE;
        float k = Math.min(kx, ky);
        out[0] = w * 0.5f + sx * k;
        out[1] = h * 0.5f + sy * k;
        out[2] = (float) a;
    }
}
