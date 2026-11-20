package com.utilitysuite.world.oresim;

import java.util.Arrays;

/**
 * Terrain knowledge limited to surface heights of the 3 x 3 chunks around the simulated chunk (vein boxes never reach
 * further). Blocks themselves stay {@link TerrainOracle#UNKNOWN}: the client cannot see the generation-time state of
 * blocks (later features, caves the player dug, ...), so per-block refinement is left to verification.
 * Immutable once built.
 */
public final class HeightmapTerrainOracle implements TerrainOracle {

    public static final int SIDE = 48;

    private final int originX;
    private final int originZ;
    private final int[] heights;

    /**
     * @param heights 48 x 48 surface heights (index {@code dx * 48 + dz}) starting at block ((chunkX-1)*16,
     *                (chunkZ-1)*16); {@link TerrainOracle#UNKNOWN_HEIGHT} where unknown
     */
    public HeightmapTerrainOracle(int chunkX, int chunkZ, int[] heights) {
        if (heights.length != SIDE * SIDE) {
            throw new IllegalArgumentException("heights must be 48x48");
        }
        this.originX = (chunkX - 1) * 16;
        this.originZ = (chunkZ - 1) * 16;
        this.heights = heights;
    }

    public static int[] unknownHeights() {
        int[] h = new int[SIDE * SIDE];
        Arrays.fill(h, UNKNOWN_HEIGHT);
        return h;
    }

    @Override
    public byte classify(OreSpec spec, int x, int y, int z) {
        return UNKNOWN;
    }

    @Override
    public int surfaceHeight(int x, int z) {
        int dx = x - originX;
        int dz = z - originZ;
        if (dx < 0 || dz < 0 || dx >= SIDE || dz >= SIDE) {
            return UNKNOWN_HEIGHT;
        }
        return heights[dx * SIDE + dz];
    }
}
