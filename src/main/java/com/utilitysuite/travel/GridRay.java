package com.utilitysuite.travel;

/**
 * Allocation-free voxel ray march (Amanatides &amp; Woo). Replaces {@code Level.clip} in per-tick sampling, which would
 * allocate a {@code ClipContext}, two {@code Vec3}s and a hit result per ray.
 */
public final class GridRay {

    private GridRay() {
    }

    /**
     * Distance along the ray from the origin to the first block for which {@code query.blocks} is true, or
     * {@code maxDistance} when nothing blocks within it. The direction need not be normalized (zero vector returns 0
     * if the origin block blocks, else maxDistance). The origin block itself counts.
     */
    public static double march(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance,
                               BlockQuery query) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int x = floor(ox);
        int y = floor(oy);
        int z = floor(oz);
        if (query.blocks(x, y, z)) {
            return 0.0;
        }
        if (len < 1.0e-9) {
            return maxDistance;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : boundary(ox, x, stepX) / Math.abs(dx);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : boundary(oy, y, stepY) / Math.abs(dy);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : boundary(oz, z, stepZ) / Math.abs(dz);
        while (true) {
            double t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (t > maxDistance) {
                return maxDistance;
            }
            if (query.blocks(x, y, z)) {
                return t;
            }
        }
    }

    private static double boundary(double origin, int cell, int step) {
        return step > 0 ? (cell + 1) - origin : origin - cell;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
