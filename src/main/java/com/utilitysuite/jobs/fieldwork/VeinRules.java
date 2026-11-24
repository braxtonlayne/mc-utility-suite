package com.utilitysuite.jobs.fieldwork;

/**
 * Engine-free rules of the vein miner: the hazard check before a queued block is mined, and which queued blocks a short
 * walk may go for. Allocation free; the world is read through a {@link Probe}.
 */
final class VeinRules {

    static final int SAFE = 0;
    /** Lava (source or flowing) touches one of the block's six faces: breaking it could let the lava in. */
    static final int LAVA = 1;
    /** A gravity block (sand, gravel, concrete powder, anvil...) sits on top: it would fall into the gap. */
    static final int FALLING_ABOVE = 2;

    /** The six face neighbours as x,y,z triples. */
    private static final int[] FACE_OFFSETS = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};

    /** Reads the world for {@link #hazard}. Implementations must not allocate. */
    interface Probe {
        boolean lavaAt(int x, int y, int z);

        boolean fallingBlockAt(int x, int y, int z);
    }

    private VeinRules() {
    }

    /** Number of face neighbours (for tests). */
    static int faceCount() {
        return FACE_OFFSETS.length / 3;
    }

    /** Face neighbour {@code i}: axis 0 = x, 1 = y, 2 = z (for tests). */
    static int faceOffset(int i, int axis) {
        return FACE_OFFSETS[i * 3 + axis];
    }

    /** {@link #SAFE}, {@link #LAVA} (checked first) or {@link #FALLING_ABOVE} for the block at x, y, z. */
    static int hazard(Probe probe, int x, int y, int z) {
        int[] o = FACE_OFFSETS;
        for (int i = 0; i < o.length; i += 3) {
            if (probe.lavaAt(x + o[i], y + o[i + 1], z + o[i + 2])) {
                return LAVA;
            }
        }
        return probe.fallingBlockAt(x, y + 1, z) ? FALLING_ABOVE : SAFE;
    }

    /**
     * True when a short walk may go for the block at bx, by, bz: standing at most {@code walkCap} blocks
     * (horizontally) from where the vein started brings it within {@code reach} horizontally, and it is not so far
     * above or below the eyes that no walk on this level could reach it.
     */
    static boolean walkable(double originX, double originZ, double eyeY, int bx, int by, int bz, double reach,
                            double walkCap) {
        double dx = bx + 0.5 - originX, dz = bz + 0.5 - originZ;
        double horizontal = walkCap + reach - 0.5;
        if (dx * dx + dz * dz > horizontal * horizontal) {
            return false;
        }
        return Math.abs(by + 0.5 - eyeY) <= reach - 0.5;
    }
}
