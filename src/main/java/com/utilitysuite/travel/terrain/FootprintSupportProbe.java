package com.utilitysuite.travel.terrain;

/**
 * {@link SupportProbe} over a {@link CollisionQuery}: the player's box footprint, shifted horizontally, has support when
 * any block under it has collision between {@code feetY - dropDepth} and the feet. A drop shallower than
 * {@code dropDepth} therefore does not count as an edge (e.g. depth 1.5 lets the player step down one block).
 * Configure once per movement call; no allocation.
 */
public final class FootprintSupportProbe implements SupportProbe {

    private static final double EPS = 1.0e-7;

    private CollisionQuery query;
    private double minX;
    private double minZ;
    private double maxX;
    private double maxZ;
    private double feetY;
    private double depth;

    public FootprintSupportProbe configure(CollisionQuery query, double minX, double feetY, double minZ, double maxX,
                                           double maxZ, double dropDepth) {
        this.query = query;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.feetY = feetY;
        this.depth = dropDepth;
        return this;
    }

    @Override
    public boolean hasSupport(double offsetX, double offsetZ) {
        int x0 = floor(minX + offsetX + EPS);
        int x1 = floor(maxX + offsetX - EPS);
        int z0 = floor(minZ + offsetZ + EPS);
        int z1 = floor(maxZ + offsetZ - EPS);
        double lo = feetY - depth;
        double hi = feetY + EPS;
        int y0 = floor(lo) - 1; // a block whose top is exactly at feetY - depth still counts
        int y1 = floor(feetY - EPS);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y1; y >= y0; y--) {
                    if (query.collidesWithin(x, y, z, lo, hi)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
