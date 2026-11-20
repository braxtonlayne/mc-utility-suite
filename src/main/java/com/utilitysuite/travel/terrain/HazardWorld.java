package com.utilitysuite.travel.terrain;

/** Engine-free world view for the hazard scan. Implementations must not allocate per call. */
public interface HazardWorld {

    /** True when the block has a collision shape a player could stand on or hit their head on. */
    boolean solid(int x, int y, int z);

    /** True when the block holds lava (source or flowing). */
    boolean lava(int x, int y, int z);

    /** False when the column's chunk is not loaded (the scan skips it). */
    boolean loaded(int x, int z);
}
