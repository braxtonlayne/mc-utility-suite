package com.utilitysuite.jobs.planner;

/**
 * Same bit layout as {@code BlockPos.asLong()} (x 26 bits, z 26 bits, y 12 bits) so planning code and tests can work
 * with packed positions without loading Minecraft classes.
 */
public final class PackedPos {
    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long X_MASK = (1L << X_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;
    private static final int Z_OFFSET = Y_BITS;
    private static final int X_OFFSET = Y_BITS + Z_BITS;

    private PackedPos() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & X_MASK) << X_OFFSET | ((long) y & Y_MASK) | ((long) z & Z_MASK) << Z_OFFSET;
    }

    public static int x(long p) {
        return (int) (p << 64 - X_OFFSET - X_BITS >> 64 - X_BITS);
    }

    public static int y(long p) {
        return (int) (p << 64 - Y_BITS >> 64 - Y_BITS);
    }

    public static int z(long p) {
        return (int) (p << 64 - Z_OFFSET - Z_BITS >> 64 - Z_BITS);
    }

    public static long distSq(long a, long b) {
        long dx = x(a) - x(b);
        long dy = y(a) - y(b);
        long dz = z(a) - z(b);
        return dx * dx + dy * dy + dz * dz;
    }

    public static String format(long p) {
        return x(p) + " " + y(p) + " " + z(p);
    }
}
