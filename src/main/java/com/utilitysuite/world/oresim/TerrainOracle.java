package com.utilitysuite.world.oresim;

/**
 * What the simulator may know about the terrain at generation time. Ore placement consumes randomness only for blocks
 * that match the ore's target rule (and only for features with a 0..1 air-exposure discard chance), and skips a whole
 * vein when its box lies above the generation-time surface, so better terrain knowledge keeps the random stream in
 * step with vanilla. Implementations must be safe on the thread that runs the simulation.
 */
public interface TerrainOracle {

    byte UNKNOWN = 0;
    /** Matches the ore's replaceable rule (stone-like / deepslate-like / netherrack / base stone). */
    byte TARGET = 1;
    byte AIR = 2;
    /** Anything else (fluids, dirt, gravel, ...). */
    byte OTHER = 3;

    int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

    byte classify(OreSpec spec, int x, int y, int z);

    /** {@code OCEAN_FLOOR_WG} height at generation time, or {@link #UNKNOWN_HEIGHT}. */
    int surfaceHeight(int x, int z);

    /** Receives the ore blocks of noise-stage large veins. */
    interface LargeVeinSink {
        /** @param copper true for a copper vein block ({@code copper_ore}), false for iron ({@code deepslate_iron_ore}) */
        void acceptVeinOre(boolean copper, int x, int y, int z);
    }

    /**
     * Reports every ore block (not raw-ore blocks, not filler) that the noise stage's {@code OreVeinifier} places in
     * the chunk, if this oracle can evaluate the world's vein noises. Called on the simulation thread.
     *
     * @return false when large veins are unknown here (no generator access, veins disabled, failure)
     */
    default boolean largeVeins(int chunkX, int chunkZ, LargeVeinSink sink) {
        return false;
    }

    /** Nothing known: every block is assumed to be a target, every surface check passes. */
    TerrainOracle UNKNOWN_TERRAIN = new TerrainOracle() {
        @Override
        public byte classify(OreSpec spec, int x, int y, int z) {
            return UNKNOWN;
        }

        @Override
        public int surfaceHeight(int x, int z) {
            return UNKNOWN_HEIGHT;
        }
    };
}
