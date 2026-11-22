package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.travel.Packed;

/**
 * Engine-free "Ghost Line" samples: where a player-sized ghost stands about a second ahead of the player, and the
 * polyline (route trail or jump arc) leading to it. Computed once per client tick into preallocated arrays; the
 * renderer interpolates the ghost between the previous and the current tick with the partial tick.
 * <ul>
 *   <li>{@link #updateRoute}: the ghost sits {@code max(speed x leadTicks, MIN_LEAD)} blocks (horizontal arc length)
 *       along the route polyline that starts at the player and runs through the remaining cells (cell centres), clamped
 *       to the route's end.</li>
 *   <li>{@link #updateJump}: the ghost sits at the predicted landing; the arc is a parabola from the player to it whose
 *       apex is {@link #ARC_RISE} above the chord's midpoint.</li>
 * </ul>
 * Both go inactive when the ghost would be closer than {@link #MIN_SHOW} to the player. Nothing is allocated.
 */
public final class GhostLine {

    public static final int NONE = 0;
    public static final int ROUTE = 1;
    public static final int JUMP = 2;
    public static final int MAX_TRAIL = 64;
    public static final double MIN_LEAD = 3.0;
    public static final double MIN_SHOW = 1.5;
    public static final int ARC_SEGMENTS = 16;
    public static final double ARC_RISE = 1.0;
    /** Jump quality of the predicted landing: lands cleanly. */
    public static final int GOOD = 0;
    /** Lands, but with fall damage or a small miss of the intended target. */
    public static final int MARGINAL = 1;
    /** Misses: no survivable landing, or a large miss of the intended target. */
    public static final int MISS = 2;
    /** Target miss (blocks) above which a jump counts as {@link #MISS}. */
    public static final double MISS_LIMIT = 0.5;

    private final float[] trail = new float[MAX_TRAIL * 3];
    private int trailCount;
    private int mode = NONE;
    private float gx, gy, gz;
    private float pgx, pgy, pgz;
    private int quality = GOOD;

    public int mode() {
        return mode;
    }

    public boolean active() {
        return mode != NONE;
    }

    /** Number of points in {@link #trailAt}. */
    public int trailCount() {
        return trailCount;
    }

    public float trailX(int i) {
        return trail[i * 3];
    }

    public float trailY(int i) {
        return trail[i * 3 + 1];
    }

    public float trailZ(int i) {
        return trail[i * 3 + 2];
    }

    public float ghostX() {
        return gx;
    }

    public float ghostY() {
        return gy;
    }

    public float ghostZ() {
        return gz;
    }

    public void clear() {
        mode = NONE;
        trailCount = 0;
        quality = GOOD;
    }

    /** Ghost position between the previous and the current tick (out must hold 3). */
    public void interpolate(float t, float[] out) {
        out[0] = pgx + (gx - pgx) * t;
        out[1] = pgy + (gy - pgy) * t;
        out[2] = pgz + (gz - pgz) * t;
    }

    /**
     * Places the ghost along a route. {@code cells} are packed feet cells ({@link Packed}), {@code index} the first
     * cell still ahead; {@code speed} the player's horizontal speed in blocks per tick.
     */
    public void updateRoute(long[] cells, int count, int index, double px, double py, double pz, double speed,
                            int leadTicks) {
        double target = Math.max(MIN_LEAD, speed * leadTicks);
        trailCount = 0;
        addTrail((float) px, (float) py, (float) pz);
        double cx = px;
        double cy = py;
        double cz = pz;
        double walked = 0.0;
        double ox = px;
        double oy = py;
        double oz = pz;
        boolean placed = false;
        for (int i = Math.max(0, index); i < count && trailCount < MAX_TRAIL - 1; i++) {
            long c = cells[i];
            double nx = Packed.x(c) + 0.5;
            double ny = Packed.y(c);
            double nz = Packed.z(c) + 0.5;
            double dx = nx - cx;
            double dz = nz - cz;
            double seg = Math.sqrt(dx * dx + dz * dz);
            if (seg < 0.3) {
                continue; // the cell the body is standing on
            }
            if (walked + seg >= target) {
                double t = (target - walked) / seg;
                ox = cx + dx * t;
                oy = cy + (ny - cy) * t;
                oz = cz + dz * t;
                addTrail((float) ox, (float) oy, (float) oz);
                placed = true;
                break;
            }
            walked += seg;
            cx = nx;
            cy = ny;
            cz = nz;
            addTrail((float) cx, (float) cy, (float) cz);
            ox = cx;
            oy = cy;
            oz = cz;
        }
        publish(ROUTE, (float) ox, (float) oy, (float) oz, px, py, pz);
    }

    /** Places the ghost at the predicted landing with a parabolic arc from the player. */
    public void updateJump(double px, double py, double pz, double lx, double ly, double lz) {
        trailCount = 0;
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            double t = (double) i / ARC_SEGMENTS;
            double y = py + (ly - py) * t + 4.0 * ARC_RISE * t * (1.0 - t);
            addTrail((float) (px + (lx - px) * t), (float) y, (float) (pz + (lz - pz) * t));
        }
        publish(JUMP, (float) lx, (float) ly, (float) lz, px, py, pz);
    }

    private void addTrail(float x, float y, float z) {
        int o = trailCount * 3;
        trail[o] = x;
        trail[o + 1] = y;
        trail[o + 2] = z;
        trailCount++;
    }

    private void publish(int newMode, float x, float y, float z, double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        if (dx * dx + dy * dy + dz * dz < MIN_SHOW * MIN_SHOW) {
            clear();
            return;
        }
        if (mode == NONE) {
            pgx = x;
            pgy = y;
            pgz = z;
        } else {
            pgx = gx;
            pgy = gy;
            pgz = gz;
        }
        gx = x;
        gy = y;
        gz = z;
        mode = newMode;
    }

    // ---- jump quality -----------------------------------------------------------------------------------------------

    /** {@link #GOOD}, {@link #MARGINAL} or {@link #MISS} of the current jump prediction (GOOD for a route). */
    public int quality() {
        return quality;
    }

    public void setQuality(int q) {
        quality = q < GOOD ? GOOD : Math.min(q, MISS);
    }

    /**
     * Quality of a predicted landing: {@link #MISS} when nothing survivable is reached or the intended target is missed
     * by more than {@link #MISS_LIMIT}; {@link #MARGINAL} with fall damage or any smaller miss; else {@link #GOOD}.
     */
    public static int quality(boolean lethal, float landDamage, double missDistance) {
        if (lethal || missDistance > MISS_LIMIT) {
            return MISS;
        }
        if (landDamage > 0f || missDistance > 1.0e-3) {
            return MARGINAL;
        }
        return GOOD;
    }

    // ---- drawing (through the Shell's world kit) --------------------------------------------------------------------

    /** Ghost outline half width and height (player-sized). */
    public static final float HALF_WIDTH = 0.3f;
    public static final float HEIGHT = 1.8f;
    /** The path floats this far above the feet line. */
    public static final float PATH_LIFT = 0.1f;
    /** Dash length in blocks; the dash period is twice this. */
    public static final float DASH = 0.3f;
    /** Fade bands per path at most. */
    public static final int MAX_BANDS = 24;
    /** Alpha at the far end of the path relative to the near end. */
    public static final float FAR_ALPHA = 0.3f;
    /** Alpha of the continuous base under the dashes, relative to the opacity setting. */
    public static final float BASE_ALPHA = 0.25f;
    /** Size of the landing mark in blocks. */
    public static final float MARK_SIZE = 0.8f;

    /** Token of the landing mark for a jump quality. */
    public static HoloToken qualityToken(int quality) {
        return quality == MISS ? HoloToken.DANGER : quality == MARGINAL ? HoloToken.WARN : HoloToken.ACCENT;
    }

    /**
     * Draws one frame of the ghost through {@code w}, all coordinates camera-relative: the path ({@code n} points in
     * {@code path}, 0 for none) as a faint continuous base plus dashes that fade with distance along it, band by band
     * ({@code band} is scratch of {@code (MAX_TRAIL + 2) * 3}); then, for a jump, a ground mark at the landing
     * ({@code gx, gy, gz}) in the quality colour, or, for a route, the player-sized ghost outline there. Allocation
     * free.
     */
    public static void draw(IHoloWorld w, float[] path, int n, float[] band, float opacity, boolean jump, int quality,
                            float gx, float gy, float gz) {
        if (n >= 2) {
            w.polyline(path, n, 0f, HoloToken.ACCENT, opacity * BASE_ALPHA);
            float total = length(path, n);
            if (total > 1.0e-3f) {
                float bl = bandLength(total, DASH * 2f, MAX_BANDS);
                for (float s0 = 0f; s0 < total; s0 += bl) {
                    float s1 = Math.min(total, s0 + bl);
                    int c = band(path, n, s0, s1, band);
                    if (c >= 2) {
                        float t = (s0 + s1) * 0.5f / total;
                        float alpha = opacity * (1f - (1f - FAR_ALPHA) * t);
                        w.polyline(band, c, DASH, HoloToken.ACCENT, alpha);
                    }
                }
            }
        }
        if (jump) {
            w.groundMark(gx, gy, gz, MARK_SIZE, qualityToken(quality), Math.max(0.6f, opacity));
        } else {
            w.boxEdges(gx - HALF_WIDTH, gy, gz - HALF_WIDTH, gx + HALF_WIDTH, gy + HEIGHT, gz + HALF_WIDTH,
                    HoloToken.LINE_STRONG, opacity);
        }
    }

    // ---- path bands (rendering helpers, engine-free) ----------------------------------------------------------------

    /** Arc length of the polyline of {@code n} xyz points in {@code path}. */
    public static float length(float[] path, int n) {
        float total = 0f;
        for (int i = 1; i < n; i++) {
            total += segLen(path, i);
        }
        return total;
    }

    private static float segLen(float[] p, int i) {
        int a = (i - 1) * 3;
        int b = i * 3;
        float dx = p[b] - p[a];
        float dy = p[b + 1] - p[a + 1];
        float dz = p[b + 2] - p[a + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Writes the part of the polyline between arc lengths {@code s0} and {@code s1} into {@code out} (xyz triples from
     * index 0): the interpolated start, the inner points, the interpolated end. Returns the point count (0 when the
     * range is empty). {@code out} must hold {@code (n + 2) * 3} floats. Allocation free.
     */
    public static int band(float[] path, int n, float s0, float s1, float[] out) {
        if (n < 2 || s1 <= s0) {
            return 0;
        }
        int count = 0;
        float s = 0f;
        for (int i = 1; i < n; i++) {
            float len = segLen(path, i);
            float e = s + len;
            if (len > 1.0e-6f && e > s0 && s < s1) {
                int a = (i - 1) * 3;
                int b = i * 3;
                if (count == 0) {
                    float f = Math.max(0f, (s0 - s) / len);
                    count = put(out, count, path, a, b, f);
                }
                if (e <= s1) {
                    count = put(out, count, path, a, b, 1f);
                } else {
                    count = put(out, count, path, a, b, (s1 - s) / len);
                    break;
                }
            }
            s = e;
        }
        return count >= 2 ? count : 0;
    }

    private static int put(float[] out, int count, float[] p, int a, int b, float f) {
        int o = count * 3;
        out[o] = p[a] + (p[b] - p[a]) * f;
        out[o + 1] = p[a + 1] + (p[b + 1] - p[a + 1]) * f;
        out[o + 2] = p[a + 2] + (p[b + 2] - p[a + 2]) * f;
        return count + 1;
    }

    /**
     * Length of the fade bands a path of {@code total} blocks is cut into: a whole number of dash periods (so a
     * dashed polyline drawn band by band keeps one continuous dash pattern), with at most {@code maxBands} bands.
     */
    public static float bandLength(float total, float dashPeriod, int maxBands) {
        if (dashPeriod <= 0f) {
            return Math.max(total / Math.max(1, maxBands), 1.0e-3f);
        }
        int periods = (int) Math.ceil(total / (dashPeriod * Math.max(1, maxBands)));
        return dashPeriod * Math.max(1, periods);
    }
}
