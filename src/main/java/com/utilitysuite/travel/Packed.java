package com.utilitysuite.travel;

/**
 * Block positions packed into a {@code long}, bit-compatible with {@code BlockPos.asLong} (x: 26 bits at 38, z: 26 bits
 * at 12, y: 12 bits at 0) so pure planning code and tests need no Minecraft class.
 */
public final class Packed {

    private static final long MASK_XZ = (1L << 26) - 1L;
    private static final long MASK_Y = (1L << 12) - 1L;

    private Packed() {
    }

    public static long pack(int x, int y, int z) {
        return ((x & MASK_XZ) << 38) | ((z & MASK_XZ) << 12) | (y & MASK_Y);
    }

    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    public static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
