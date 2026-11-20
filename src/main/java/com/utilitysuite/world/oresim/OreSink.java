package com.utilitysuite.world.oresim;

/** Receives predicted ore blocks from {@link OreSimulator}. */
@FunctionalInterface
public interface OreSink {

    /**
     * @param specIndex  index into the spec array passed to the simulator
     * @param vein       ordinal of the placement attempt inside the feature (0-based), identifies the cluster
     * @param confidence 0..1
     */
    void accept(int specIndex, int x, int y, int z, int vein, float confidence);
}
