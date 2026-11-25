package com.utilitysuite.world.stronghold;

/**
 * Engine-free geometry for the two-throw stronghold finder: 2D ray intersection and snapping a distance from the world
 * origin onto the vanilla 1.21.1 concentric stronghold ring bands.
 * <p>
 * Vanilla places ring {@code k} (0-based) at {@code 4*32 + 6*32*k} chunks from the origin, randomised by
 * {@code ±1.25*32} chunks, and the start piece can sit up to about half a chunk further off after biome snapping; in
 * blocks this gives ring 1 = 1280..2816, ring 2 = 4352..5888, ring 3 = 7424..8960, ... (centre {@code 2048 + 3072*k},
 * half-width 768). There are eight rings (128 strongholds).
 */
public final class StrongholdMath {

    public static final int RING_COUNT = 8;
    public static final double RING_CENTER_0 = 2048;
    public static final double RING_STEP = 3072;
    public static final double RING_HALF_WIDTH = 768;

    private StrongholdMath() {
    }

    /** Result of {@link #intersect}; {@code t1}/{@code t2} are the distances along each (unit) ray. */
    public record Hit(double x, double z, double t1, double t2) {
    }

    /** A located stronghold estimate. {@code ring} is 1-based; {@code snapped} is true when the raw point was moved. */
    public record Estimate(double x, double z, double rawX, double rawZ, int ring, boolean snapped) {

        public double radius() {
            return Math.sqrt(x * x + z * z);
        }
    }

    /**
     * Intersects two horizontal rays. Directions need not be normalised. Returns null when the rays are (nearly)
     * parallel, i.e. the angle between them is below {@code minAngleDeg}, or when they meet behind either origin.
     */
    public static Hit intersect(double x1, double z1, double dx1, double dz1, double x2, double z2, double dx2,
                                double dz2, double minAngleDeg) {
        double l1 = Math.hypot(dx1, dz1);
        double l2 = Math.hypot(dx2, dz2);
        if (l1 < 1e-9 || l2 < 1e-9) {
            return null;
        }
        double ax = dx1 / l1;
        double az = dz1 / l1;
        double bx = dx2 / l2;
        double bz = dz2 / l2;
        double cross = ax * bz - az * bx; // sin of the angle between the rays
        if (Math.abs(cross) < Math.sin(Math.toRadians(minAngleDeg))) {
            return null;
        }
        double wx = x2 - x1;
        double wz = z2 - z1;
        double t1 = (wx * bz - wz * bx) / cross;
        double t2 = (wx * az - wz * ax) / cross;
        if (t1 <= 0 || t2 <= 0) {
            return null;
        }
        return new Hit(x1 + ax * t1, z1 + az * t1, t1, t2);
    }

    /** Lower bound of the 1-based ring's band, in blocks from the origin. */
    public static double ringMin(int ring) {
        return RING_CENTER_0 + RING_STEP * (ring - 1) - RING_HALF_WIDTH;
    }

    /** Upper bound of the 1-based ring's band. */
    public static double ringMax(int ring) {
        return RING_CENTER_0 + RING_STEP * (ring - 1) + RING_HALF_WIDTH;
    }

    /** 1-based ring whose band is nearest to {@code radius} (0 inside a band counts as distance 0). */
    public static int nearestRing(double radius) {
        int best = 1;
        double bestGap = Double.MAX_VALUE;
        for (int r = 1; r <= RING_COUNT; r++) {
            double gap = radius < ringMin(r) ? ringMin(r) - radius : radius > ringMax(r) ? radius - ringMax(r) : 0;
            if (gap < bestGap) {
                bestGap = gap;
                best = r;
            }
        }
        return best;
    }

    /** Radius clamped into the nearest ring band. */
    public static double snapRadius(double radius) {
        int r = nearestRing(radius);
        return Math.max(ringMin(r), Math.min(ringMax(r), radius));
    }

    /**
     * Turns a raw intersection into an estimate: with {@code snap}, a point outside every ring band is moved radially
     * (along the line from the world origin) onto the nearest band edge.
     */
    public static Estimate estimate(double rawX, double rawZ, boolean snap) {
        double radius = Math.hypot(rawX, rawZ);
        int ring = nearestRing(radius);
        if (!snap || radius < 1e-6) {
            return new Estimate(rawX, rawZ, rawX, rawZ, ring, false);
        }
        double r = snapRadius(radius);
        if (r == radius) {
            return new Estimate(rawX, rawZ, rawX, rawZ, ring, false);
        }
        double k = r / radius;
        return new Estimate(rawX * k, rawZ * k, rawX, rawZ, ring, true);
    }
}
