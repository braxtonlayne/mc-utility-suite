package com.utilitysuite.combat.assistant;

/**
 * Allocation-free voxel traversal (Amanatides-Woo) from one point to another, used for the line-of-sight timeout
 * instead of vanilla's raycast (which allocates per call). The start voxel is skipped; the walk is capped.
 */
public final class LineOfSight {

    /** Block solidity lookup at integer coordinates. */
    @FunctionalInterface
    public interface SolidTest {
        boolean solid(int x, int y, int z);
    }

    private LineOfSight() {
    }

    /** @return true when no solid voxel lies between the two points within {@code maxSteps} voxels */
    public static boolean clear(double x0, double y0, double z0, double x1, double y1, double z1, SolidTest test,
                                int maxSteps) {
        int x = floor(x0);
        int y = floor(y0);
        int z = floor(z0);
        int ex = floor(x1);
        int ey = floor(y1);
        int ez = floor(z1);
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int sz = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tdx = sx != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tdy = sy != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tdz = sz != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;
        double tmx = sx > 0 ? (x + 1 - x0) / dx : sx < 0 ? (x0 - x) / -dx : Double.POSITIVE_INFINITY;
        double tmy = sy > 0 ? (y + 1 - y0) / dy : sy < 0 ? (y0 - y) / -dy : Double.POSITIVE_INFINITY;
        double tmz = sz > 0 ? (z + 1 - z0) / dz : sz < 0 ? (z0 - z) / -dz : Double.POSITIVE_INFINITY;
        for (int step = 0; step < maxSteps; step++) {
            if (x == ex && y == ey && z == ez) {
                return true;
            }
            if (tmx <= tmy && tmx <= tmz) {
                if (tmx > 1.0) {
                    return true;
                }
                x += sx;
                tmx += tdx;
            } else if (tmy <= tmz) {
                if (tmy > 1.0) {
                    return true;
                }
                y += sy;
                tmy += tdy;
            } else {
                if (tmz > 1.0) {
                    return true;
                }
                z += sz;
                tmz += tdz;
            }
            if (test.solid(x, y, z)) {
                return false;
            }
        }
        return false;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
