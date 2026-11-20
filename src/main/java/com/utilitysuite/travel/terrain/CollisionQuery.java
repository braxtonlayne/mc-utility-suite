package com.utilitysuite.travel.terrain;

/** Engine-free collision lookup used by {@link FootprintSupportProbe}. Implementations must not allocate per call. */
@FunctionalInterface
public interface CollisionQuery {

    /**
     * True when the block at (x, y, z) has a collision shape whose vertical extent (in world coordinates) overlaps
     * [minY, maxY].
     */
    boolean collidesWithin(int x, int y, int z, double minY, double maxY);
}
