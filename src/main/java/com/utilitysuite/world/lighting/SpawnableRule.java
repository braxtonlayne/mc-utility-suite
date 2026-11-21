package com.utilitysuite.world.lighting;

/**
 * Engine-free hostile spawn rule for one floor position (Java Edition 1.18+): the floor block must allow a spawn on
 * its top face, the two blocks above must be empty spawn space, and the block light above the floor must be at or
 * below the threshold (0 in vanilla). Sky light decides the colour: with no sky light the spot spawns at any time
 * ({@link #ALWAYS}); with sky light it spawns only at night or in a storm ({@link #NIGHT}).
 */
public final class SpawnableRule {

    public static final int NONE = 0;
    public static final int ALWAYS = 1;
    public static final int NIGHT = 2;

    private SpawnableRule() {
    }

    /**
     * @param floorValid      the floor block accepts a spawn on top (full sturdy top, not glass/leaves/slab bottom)
     * @param feetEmpty       the block above the floor is valid empty spawn space
     * @param headEmpty       the block two above the floor is valid empty spawn space
     * @param blockLight      block light at the feet position
     * @param skyLight        sky light at the feet position
     * @param blockThreshold  highest block light that still allows a spawn (vanilla 0)
     */
    public static int classify(boolean floorValid, boolean feetEmpty, boolean headEmpty, int blockLight, int skyLight,
                               int blockThreshold) {
        if (!floorValid || !feetEmpty || !headEmpty || blockLight > blockThreshold) {
            return NONE;
        }
        return skyLight > 0 ? NIGHT : ALWAYS;
    }
}
