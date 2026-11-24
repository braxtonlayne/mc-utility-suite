package com.utilitysuite.travel.movement;

/** Ray against an axis-aligned box (slab clipping). Static and allocation-free: for gaze rays in tick paths. */
final class RayBox {

    private RayBox() {
    }

    /**
     * Ray parameter at which the ray {@code o + d * t} (t &ge; 0) enters the box, 0 when it starts inside, or -1 when
     * it misses.
     */
    static double enter(double ox, double oy, double oz, double dx, double dy, double dz, double minX, double minY,
                        double minZ, double maxX, double maxY, double maxZ) {
        double lo = 0.0;
        double hi = Double.MAX_VALUE;
        if (Math.abs(dx) < 1.0e-12) {
            if (ox < minX || ox > maxX) {
                return -1.0;
            }
        } else {
            double a = (minX - ox) / dx;
            double b = (maxX - ox) / dx;
            lo = Math.max(lo, Math.min(a, b));
            hi = Math.min(hi, Math.max(a, b));
            if (lo > hi) {
                return -1.0;
            }
        }
        if (Math.abs(dy) < 1.0e-12) {
            if (oy < minY || oy > maxY) {
                return -1.0;
            }
        } else {
            double a = (minY - oy) / dy;
            double b = (maxY - oy) / dy;
            lo = Math.max(lo, Math.min(a, b));
            hi = Math.min(hi, Math.max(a, b));
            if (lo > hi) {
                return -1.0;
            }
        }
        if (Math.abs(dz) < 1.0e-12) {
            if (oz < minZ || oz > maxZ) {
                return -1.0;
            }
        } else {
            double a = (minZ - oz) / dz;
            double b = (maxZ - oz) / dz;
            lo = Math.max(lo, Math.min(a, b));
            hi = Math.min(hi, Math.max(a, b));
            if (lo > hi) {
                return -1.0;
            }
        }
        return lo;
    }
}
