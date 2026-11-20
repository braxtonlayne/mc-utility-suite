package com.utilitysuite.travel;

/** Engine-free view of the world for planners and raycasts. Implementations must not allocate per call. */
@FunctionalInterface
public interface BlockQuery {

    /** True when the block at the position stops a ray (solid collision, or "not water" for the boat sampler). */
    boolean blocks(int x, int y, int z);
}
