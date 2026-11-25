package com.utilitysuite.combat.boss;

import com.utilitysuite.combat.tactics.RangedBallistics;

/**
 * Bow-arc geometry for the Boss Helper's world overlay: the points of a full-draw arrow's flight from the eye to a
 * target (end crystal), using the elevation {@link RangedBallistics} solved, and a line-of-sight walk along that arc
 * through a block probe. Engine-agnostic and allocation free (caller-owned point buffer).
 */
public final class BossArc {

    /** True when the block cell stops an arrow (any collision shape). Must not allocate. */
    public interface BlockProbe {
        boolean blocks(int x, int y, int z);
    }

    /** Most points an arc keeps (one per tick of flight, the last one at the target). */
    public static final int MAX_POINTS = 64;
    /** Sample spacing for the line-of-sight walk, in blocks. */
    static final double LOS_STEP = 0.25;

    private BossArc() {
    }

    /**
     * Fills {@code out} (x, y, z triples, world coordinates) with the arc of a shot launched at {@code elevDeg} with
     * {@code speed} from (ox, oy, oz) toward (tx, tz) horizontally, one point per tick, ending exactly at the target's
     * horizontal distance. Returns the number of points (at least 2), or 0 when the arc never gets there.
     */
    public static int fill(double ox, double oy, double oz, double tx, double tz, double elevDeg, double speed,
                           double[] out) {
        double hx = tx - ox, hz = tz - oz;
        double dist = Math.sqrt(hx * hx + hz * hz);
        int max = Math.min(MAX_POINTS, out.length / 3);
        if (dist < 1.0e-6 || max < 2) {
            return 0;
        }
        double ux = hx / dist, uz = hz / dist;
        double r = Math.toRadians(elevDeg);
        double vh = Math.cos(r) * speed;
        double vy = Math.sin(r) * speed;
        double h = 0.0, y = 0.0;
        int n = 0;
        out[n * 3] = ox;
        out[n * 3 + 1] = oy;
        out[n * 3 + 2] = oz;
        n++;
        for (int tick = 1; tick <= RangedBallistics.MAX_TICKS && n < max; tick++) {
            double nh = h + vh;
            double ny = y + vy;
            if (nh >= dist || n == max - 1) {
                double f = vh > 1.0e-9 ? Math.min(1.0, (dist - h) / vh) : 0.0;
                if (nh < dist && n == max - 1) {
                    f = 1.0; // point budget spent: stop at the last full tick
                }
                double fh = h + vh * f;
                out[n * 3] = ox + ux * fh;
                out[n * 3 + 1] = oy + y + vy * f;
                out[n * 3 + 2] = oz + uz * fh;
                return n + 1;
            }
            h = nh;
            y = ny;
            out[n * 3] = ox + ux * h;
            out[n * 3 + 1] = oy + y;
            out[n * 3 + 2] = oz + uz * h;
            n++;
            vh *= RangedBallistics.AIR_DRAG;
            vy = vy * RangedBallistics.AIR_DRAG - RangedBallistics.GRAVITY;
            if (vh < 1.0e-4) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Walks the arc in {@link #LOS_STEP} samples and returns the index of the first point after which a blocking cell
     * lies (the arc is clear up to point {@code i}), or -1 when the whole arc is clear. The cell holding the first
     * point (the eye) is not tested.
     */
    public static int firstBlocked(double[] pts, int count, BlockProbe probe) {
        int lastX = floor(pts[0]), lastY = floor(pts[1]), lastZ = floor(pts[2]);
        for (int i = 0; i + 1 < count; i++) {
            double x0 = pts[i * 3], y0 = pts[i * 3 + 1], z0 = pts[i * 3 + 2];
            double dx = pts[i * 3 + 3] - x0, dy = pts[i * 3 + 4] - y0, dz = pts[i * 3 + 5] - z0;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(1, (int) Math.ceil(len / LOS_STEP));
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                int bx = floor(x0 + dx * t), by = floor(y0 + dy * t), bz = floor(z0 + dz * t);
                if (bx == lastX && by == lastY && bz == lastZ) {
                    continue;
                }
                lastX = bx;
                lastY = by;
                lastZ = bz;
                if (probe.blocks(bx, by, bz)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Straight-line trajectory of a wither skull (no gravity): end point {@code ticks} ticks ahead along its velocity,
     * capped at {@code maxLen} blocks. Writes x, y, z into {@code out[0..2]}.
     */
    public static void skullEnd(double x, double y, double z, double vx, double vy, double vz, int ticks,
                                double maxLen, double[] out) {
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double len = Math.min(maxLen, speed * ticks);
        double k = speed > 1.0e-9 ? len / speed : 0.0;
        out[0] = x + vx * k;
        out[1] = y + vy * k;
        out[2] = z + vz * k;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
