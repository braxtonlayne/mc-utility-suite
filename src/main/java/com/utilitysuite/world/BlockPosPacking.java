package com.utilitysuite.world;

/**
 * Pure-Java copy of {@code BlockPos.asLong()} / {@code getX/Y/Z(long)} so world logic stays testable without game
 * classes. Bit layout: X 26 bits at 38, Z 26 bits at 12, Y 12 bits at 0 (identical to vanilla).
 */
public final class BlockPosPacking {

    private BlockPosPacking() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) | ((long) z & 0x3FFFFFFL) << 12;
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

    public static long distSq(long a, long b) {
        long dx = x(a) - x(b);
        long dy = y(a) - y(b);
        long dz = z(a) - z(b);
        return dx * dx + dy * dy + dz * dz;
    }

    /** {@code ChunkPos.asLong}. */
    public static long chunk(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    public static int chunkX(long chunk) {
        return (int) chunk;
    }

    public static int chunkZ(long chunk) {
        return (int) (chunk >>> 32);
    }
}
