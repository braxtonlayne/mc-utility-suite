package com.utilitysuite.travel.movement;

/**
 * The set of body-centre positions that stand on one landing top (design 2026-11-22, section 5.1): the union of the
 * collision boxes whose top face is at the landing height, each grown by the body half-width (0.3), read through the
 * {@link MovementPhysics.Voxels} box queries so a fence post, a wall, a pane and a full block each get their real top.
 * <ul>
 *   <li>{@link #margin}: Chebyshev distance from a point to the region's edge (negative = off the region). The body
 *       footprint is an axis-aligned square, so the square metric is what "how far can the centre move" means.</li>
 *   <li>{@link #computeStop}: where the body comes to rest from a ground state with given keys and friction.</li>
 *   <li>{@link #wide}: the region contains a 1.6 x 1.6 square around the stop point (in body-centre space: the top
 *       reaches 0.5 + 0.3 past the body centre in every direction). Everything else is narrow.</li>
 * </ul>
 * Engine-free and allocation free after construction: the rectangles live in a fixed primitive array.
 */
public final class SupportRegion {

    public static final double HALF_WIDTH = MovementPhysics.HALF_WIDTH;
    /** Half side of the square a wide landing contains around the stop point. */
    public static final double WIDE_HALF = 0.8;
    /** Margin the stop point needs for the body to count as stable on the top. */
    public static final double STABLE_MARGIN = 0.05;
    /** Horizontal speed below which the body counts as at rest. */
    public static final double STABLE_SPEED = 0.03;
    /** Margins are resolved up to this (a platform deeper than this is "wide enough" anyway). */
    public static final double MARGIN_CAP = 2.0;
    /** Ticks the stop point is rolled with keys held (with no key the slide ends well before). */
    public static final int STOP_HORIZON = 20;
    /** Ticks a no-key slide is rolled at most (ice needs a while to stop). */
    public static final int SLIDE_HORIZON = 120;
    /** Cells scanned around the landing point on each horizontal axis. */
    public static final int SCAN = 2;
    /** Most cells scanned per axis by the two-point {@link #build}. */
    public static final int MAX_SPAN = 14;
    public static final int MAX_RECTS = 48;
    private static final double TOP_EPS = 1.0e-3;
    private static final double EPS = 1.0e-6;

    /** minX, minZ, maxX, maxZ per rectangle (body-centre space, already grown). */
    private final double[] rects = new double[4 * MAX_RECTS];
    private int count;
    private double top = Double.NaN;
    private final float[] box = new float[6];
    private final double[] xs = new double[2 * MAX_RECTS + 2];
    private final double[] zs = new double[2 * MAX_RECTS + 2];
    private double stopX, stopZ;
    private int stopTicks;

    public void clear() {
        count = 0;
        top = Double.NaN;
    }

    /**
     * Adds one top box in world coordinates (not grown; it is grown by the body half-width here). For tests and for
     * callers that already know the boxes.
     */
    public void addTopBox(double minX, double minZ, double maxX, double maxZ) {
        if (count > 0) {
            // merge with the previous rectangle when it is the same x span and touches it along z (a column of tops)
            int p = 4 * (count - 1);
            if (Math.abs(rects[p] - (minX - HALF_WIDTH)) < EPS && Math.abs(rects[p + 2] - (maxX + HALF_WIDTH)) < EPS
                    && minZ - HALF_WIDTH <= rects[p + 3] + EPS && maxZ + HALF_WIDTH >= rects[p + 1] - EPS) {
                rects[p + 1] = Math.min(rects[p + 1], minZ - HALF_WIDTH);
                rects[p + 3] = Math.max(rects[p + 3], maxZ + HALF_WIDTH);
                return;
            }
        }
        if (count >= MAX_RECTS) {
            return;
        }
        int o = 4 * count++;
        rects[o] = minX - HALF_WIDTH;
        rects[o + 1] = minZ - HALF_WIDTH;
        rects[o + 2] = maxX + HALF_WIDTH;
        rects[o + 3] = maxZ + HALF_WIDTH;
    }

    public void setTop(double topY) {
        top = topY;
    }

    /**
     * Reads the region of the top at height {@code topY} around ({@code x}, {@code z}): every box within
     * {@link #SCAN} cells whose top face is at {@code topY}, unless something sits right on it in the same column.
     *
     * @return true when at least one top box was found
     */
    public boolean build(MovementPhysics.Voxels w, double x, double topY, double z) {
        if (!build(w, x, z, x, z, topY)) {
            return false;
        }
        keepConnected(x, z);
        return count > 0;
    }

    private final boolean[] reached = new boolean[MAX_RECTS];
    private final int[] stack = new int[MAX_RECTS];

    /**
     * Drops the rectangles not connected to the one holding ({@code x}, {@code z}): a top of the same height across a
     * gap is another top, not part of this one. J36 (2026-11-23): the strip in front of a pillar sat inside the scan
     * window of the landing strip behind it, and a neo that bounced off the pillar back onto the takeoff strip rolled
     * as a landing.
     */
    private void keepConnected(double x, double z) {
        int seed = -1;
        for (int i = 0, o = 0; i < count; i++, o += 4) {
            reached[i] = false;
            if (seed < 0 && x >= rects[o] - EPS && x <= rects[o + 2] + EPS && z >= rects[o + 1] - EPS
                    && z <= rects[o + 3] + EPS) {
                seed = i;
            }
        }
        if (seed < 0) {
            return; // the point is off every rectangle: keep the scan as it is
        }
        int sp = 0;
        stack[sp++] = seed;
        reached[seed] = true;
        while (sp > 0) {
            int a = 4 * stack[--sp];
            for (int j = 0, o = 0; j < count; j++, o += 4) {
                // grown rectangles of touching tops overlap by the body width: a shared edge or corner connects
                if (!reached[j] && rects[o] <= rects[a + 2] + EPS && rects[o + 2] >= rects[a] - EPS
                        && rects[o + 1] <= rects[a + 3] + EPS && rects[o + 3] >= rects[a + 1] - EPS) {
                    reached[j] = true;
                    stack[sp++] = j;
                }
            }
        }
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (reached[i]) {
                if (n != i) {
                    System.arraycopy(rects, 4 * i, rects, 4 * n, 4);
                }
                n++;
            }
        }
        count = n;
    }

    /**
     * Reads the region over the rectangle spanned by two points (a touchdown point and its stop point), plus
     * {@link #SCAN} cells around it, at most {@link #MAX_SPAN} cells per axis. Adjacent tops in a column merge into
     * one rectangle, so a long strip or a platform stays a handful of rectangles.
     */
    public boolean build(MovementPhysics.Voxels w, double xa, double za, double xb, double zb, double topY) {
        clear();
        top = topY;
        int x0 = MovementPhysics.floor(Math.min(xa, xb)) - SCAN;
        int x1 = Math.min(MovementPhysics.floor(Math.max(xa, xb)) + SCAN, x0 + MAX_SPAN - 1);
        int z0 = MovementPhysics.floor(Math.min(za, zb)) - SCAN;
        int z1 = Math.min(MovementPhysics.floor(Math.max(za, zb)) + SCAN, z0 + MAX_SPAN - 1);
        int yHi = MovementPhysics.floor(topY - TOP_EPS);
        int above = MovementPhysics.floor(topY + TOP_EPS);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                if (covered(w, bx, above, bz, topY)) {
                    continue;
                }
                for (int by = yHi - 1; by <= yHi; by++) {
                    int n = w.boxCount(bx, by, bz);
                    for (int i = 0; i < n; i++) {
                        w.box(bx, by, bz, i, box, 0);
                        if (Math.abs(by + box[4] - topY) <= TOP_EPS) {
                            addTopBox(bx + box[0], bz + box[2], bx + box[3], bz + box[5]);
                        }
                    }
                }
            }
        }
        return count > 0;
    }

    /** A box starts at or below the top in the cell right above it (a block standing on the top). */
    private boolean covered(MovementPhysics.Voxels w, int bx, int by, int bz, double topY) {
        int n = w.boxCount(bx, by, bz);
        for (int i = 0; i < n; i++) {
            w.box(bx, by, bz, i, box, 0);
            if (by + box[1] <= topY + TOP_EPS && by + box[4] > topY + TOP_EPS) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest top face of the block in a cell (feet height when standing on it), or NaN when it has no boxes.
     * A fence post at y gives y + 1.5, a full block y + 1, a bottom slab y + 0.5.
     */
    public double topOf(MovementPhysics.Voxels w, int x, int y, int z) {
        int n = w.boxCount(x, y, z);
        double best = Double.NaN;
        for (int i = 0; i < n; i++) {
            w.box(x, y, z, i, box, 0);
            double t = y + box[4];
            if (!(t <= best + TOP_EPS)) {
                best = t;
                cellMinX = x + box[0];
                cellMinZ = z + box[2];
                cellMaxX = x + box[3];
                cellMaxZ = z + box[5];
            } else if (Math.abs(t - best) <= TOP_EPS) {
                cellMinX = Math.min(cellMinX, x + box[0]);
                cellMinZ = Math.min(cellMinZ, z + box[2]);
                cellMaxX = Math.max(cellMaxX, x + box[3]);
                cellMaxZ = Math.max(cellMaxZ, z + box[5]);
            }
        }
        return best;
    }

    private double cellMinX, cellMinZ, cellMaxX, cellMaxZ;

    /**
     * Extent of the top face found by the last {@link #topOf} (that one block's own top, world coordinates),
     * component 0..3: minX, minZ, maxX, maxZ.
     */
    public double cellTopBound(int component) {
        return switch (component) {
            case 0 -> cellMinX;
            case 1 -> cellMinZ;
            case 2 -> cellMaxX;
            default -> cellMaxZ;
        };
    }

    public int count() {
        return count;
    }

    public double top() {
        return top;
    }

    public boolean contains(double px, double pz) {
        for (int i = 0, o = 0; i < count; i++, o += 4) {
            if (px >= rects[o] - EPS && px <= rects[o + 2] + EPS && pz >= rects[o + 1] - EPS
                    && pz <= rects[o + 3] + EPS) {
                return true;
            }
        }
        return false;
    }

    /** True when the axis-aligned square of half side {@code half} around the point lies inside the union. */
    public boolean containsSquare(double cx, double cz, double half) {
        if (count == 0) {
            return false;
        }
        double x0 = cx - half;
        double x1 = cx + half;
        double z0 = cz - half;
        double z1 = cz + half;
        int nx = edges(xs, x0, x1, 0);
        int nz = edges(zs, z0, z1, 1);
        for (int i = 0; i + 1 < nx; i++) {
            double mx = (xs[i] + xs[i + 1]) * 0.5;
            if (xs[i + 1] - xs[i] < EPS) {
                continue;
            }
            for (int j = 0; j + 1 < nz; j++) {
                if (zs[j + 1] - zs[j] < EPS) {
                    continue;
                }
                if (!contains(mx, (zs[j] + zs[j + 1]) * 0.5)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Sorted cut coordinates on one axis (0 = x, 1 = z) inside [lo, hi], both ends included. */
    private int edges(double[] out, double lo, double hi, int axis) {
        int n = 0;
        out[n++] = lo;
        out[n++] = hi;
        for (int i = 0, o = 0; i < count; i++, o += 4) {
            double a = rects[o + axis];
            double b = rects[o + 2 + axis];
            if (a > lo && a < hi) {
                out[n++] = a;
            }
            if (b > lo && b < hi) {
                out[n++] = b;
            }
        }
        for (int i = 1; i < n; i++) { // insertion sort: a few dozen values at most
            double v = out[i];
            int j = i - 1;
            while (j >= 0 && out[j] > v) {
                out[j + 1] = out[j];
                j--;
            }
            out[j + 1] = v;
        }
        return n;
    }

    /**
     * Signed Chebyshev distance from a body-centre point to the region's edge: positive inside (capped at
     * {@link #MARGIN_CAP}), negative outside (distance to the nearest rectangle), {@code -MARGIN_CAP} with no region.
     */
    public double margin(double px, double pz) {
        if (count == 0) {
            return -MARGIN_CAP;
        }
        if (!contains(px, pz)) {
            double best = Double.MAX_VALUE;
            for (int i = 0, o = 0; i < count; i++, o += 4) {
                double dx = Math.max(0.0, Math.max(rects[o] - px, px - rects[o + 2]));
                double dz = Math.max(0.0, Math.max(rects[o + 1] - pz, pz - rects[o + 3]));
                best = Math.min(best, Math.max(dx, dz));
            }
            return -Math.min(best, MARGIN_CAP);
        }
        double lo = 0.0;
        double hi = MARGIN_CAP;
        if (containsSquare(px, pz, hi)) {
            return hi;
        }
        for (int it = 0; it < 16; it++) {
            double mid = (lo + hi) * 0.5;
            if (containsSquare(px, pz, mid)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The landing is wide around this stop point: nothing of the landing hold applies there. */
    public boolean wide(double stopX, double stopZ) {
        return containsSquare(stopX, stopZ, WIDE_HALF);
    }

    /**
     * Rolls the horizontal ground motion from a state with the given keys (same order and constants as the replica:
     * input, move, friction) until the body is at rest or for {@link #STOP_HORIZON} ticks. Result in
     * {@link #stopX()}, {@link #stopZ()}. Allocation free.
     *
     * @param slip   block slipperiness under the feet (0.6 normally)
     * @param speed  movement speed attribute without the sprint modifier (0.1)
     * @param sneak  sneak held (input x 0.3)
     */
    public void computeStop(double x, double z, double vx, double vz, int forward, int strafe, float yawDeg,
                            boolean sprint, double speed, double slip, boolean sneak) {
        double accel = 0.0;
        double ix = strafe * MovementPhysics.INPUT_SCALE;
        double iz = forward * MovementPhysics.INPUT_SCALE;
        double len2 = ix * ix + iz * iz;
        double ax = 0.0;
        double az = 0.0;
        if (len2 > 1.0e-7) {
            if (len2 > 1.0) {
                double inv = 1.0 / Math.sqrt(len2);
                ix *= inv;
                iz *= inv;
            }
            if (sneak) {
                ix *= 0.3;
                iz *= 0.3;
            }
            double s = sprint && forward > 0 && !sneak ? speed * 1.3 : speed;
            accel = s * (0.21600002 / (slip * slip * slip));
            double yaw = Math.toRadians(yawDeg);
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            ax = (ix * cos - iz * sin) * accel;
            az = (iz * cos + ix * sin) * accel;
        }
        double f = slip * MovementPhysics.AIR_FRICTION;
        int t = 0;
        int horizon = accel == 0.0 ? SLIDE_HORIZON : STOP_HORIZON;
        while (t < horizon) {
            vx += ax;
            vz += az;
            x += vx;
            z += vz;
            vx *= f;
            vz *= f;
            t++;
            if (accel == 0.0 && vx * vx + vz * vz < 1.0e-6) {
                break;
            }
        }
        stopX = x;
        stopZ = z;
        stopTicks = t;
    }

    public double stopX() {
        return stopX;
    }

    public double stopZ() {
        return stopZ;
    }

    public int stopTicks() {
        return stopTicks;
    }

    /**
     * The body is stable on this top: horizontal speed under {@link #STABLE_SPEED} and the no-key stop point inside
     * the region with {@link #STABLE_MARGIN}. Overwrites the stop point.
     */
    public boolean stable(double x, double z, double vx, double vz, double slip) {
        if (vx * vx + vz * vz >= STABLE_SPEED * STABLE_SPEED) {
            return false;
        }
        return momentumSpent(x, z, vx, vz, slip);
    }

    /** With no key held from here the body comes to rest on the top with {@link #STABLE_MARGIN} to spare. */
    public boolean momentumSpent(double x, double z, double vx, double vz, double slip) {
        computeStop(x, z, vx, vz, 0, 0, 0f, false, MovementPhysics.BASE_SPEED, slip, false);
        return margin(stopX, stopZ) >= STABLE_MARGIN;
    }

    /** Union bounds (body-centre space shrunk back to the top's own extent), component 0..3: minX, minZ, maxX, maxZ. */
    public double topBound(int component) {
        if (count == 0) {
            return 0.0;
        }
        boolean max = component >= 2;
        double v = max ? -Double.MAX_VALUE : Double.MAX_VALUE;
        for (int i = 0, o = 0; i < count; i++, o += 4) {
            double r = rects[o + component];
            v = max ? Math.max(v, r) : Math.min(v, r);
        }
        return max ? v - HALF_WIDTH : v + HALF_WIDTH;
    }
}
