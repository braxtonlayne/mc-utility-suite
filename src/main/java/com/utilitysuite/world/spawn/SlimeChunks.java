package com.utilitysuite.world.spawn;

/**
 * Engine-free slime chunk test (Java Edition): vanilla seeds a {@code java.util.Random} with
 * {@code worldSeed + x*x*4987142 + x*5947611 + z*z*4392871 + z*389711 ^ 987234911} (the int products overflow as
 * ints, exactly like the game) and the chunk is a slime chunk when {@code nextInt(10) == 0}. The Random is inlined
 * here so the check allocates nothing.
 */
public final class SlimeChunks {

    public static final long SALT = 987234911L;
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private SlimeChunks() {
    }

    /** The seed vanilla's {@code WorldgenRandom.seedSlimeChunk} feeds to its Random. */
    public static long randomSeed(long worldSeed, int chunkX, int chunkZ) {
        return worldSeed + (long) (chunkX * chunkX * 4987142) + (long) (chunkX * 5947611)
                + (long) (chunkZ * chunkZ) * 4392871L + (long) (chunkZ * 389711) ^ SALT;
    }

    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long s = (randomSeed(worldSeed, chunkX, chunkZ) ^ MULTIPLIER) & MASK;
        // java.util.Random.nextInt(10): bound is not a power of two, so reject the top partial range.
        while (true) {
            s = (s * MULTIPLIER + ADDEND) & MASK;
            int bits = (int) (s >>> (48 - 31));
            int val = bits % 10;
            if (bits - val + 9 >= 0) {
                return val == 0;
            }
        }
    }
}
