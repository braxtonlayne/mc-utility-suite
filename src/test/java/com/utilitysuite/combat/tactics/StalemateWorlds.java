package com.utilitysuite.combat.tactics;

/**
 * Block layouts for the stalemate tests. Ground feet height is 64 (stone at y <= 63, air above).
 */
public final class StalemateWorlds {

    public static final int GROUND_FEET = 64;
    /** Ravine floor feet height: 8 below the ground (the bot arena's RAVINE_DEPTH). */
    public static final int RAVINE_FEET = 56;

    private StalemateWorlds() {
    }

    /**
     * The bot arena's flank ravine: x 9..11, z -12..12, 8 deep (floor stone at 55), with one-block stairs at both ends
     * (step j at z = +-(12 - j), its top at 62 - j, so the last step out is z = +-12 with feet 63, then the ground at
     * z = +-13). {@code stairs} false leaves the ravine a sealed trench.
     */
    public static CombatVoxels ravine(boolean stairs) {
        return (x, y, z) -> {
            if (y >= GROUND_FEET) {
                return CombatVoxels.AIR;
            }
            boolean inRavine = x >= 9 && x <= 11 && z >= -12 && z <= 12;
            if (!inRavine) {
                return CombatVoxels.SOLID;
            }
            if (y < RAVINE_FEET) {
                return CombatVoxels.SOLID;
            }
            if (stairs) {
                for (int j = 0; j < 7; j++) {
                    if ((z == 12 - j || z == -12 + j) && y <= 62 - j) {
                        return CombatVoxels.SOLID;
                    }
                }
            }
            return CombatVoxels.AIR;
        };
    }

    /**
     * Flat ground with a 1x1 pillar at (0, 0) whose top is two blocks up (feet 66: we cannot climb it, its owner can
     * drop off it) and a 3x3 ledge at x 8..10, z -1..1 three blocks up (feet 67: we can drop off it, nobody who drops
     * at most two blocks can get on or off it).
     */
    public static CombatVoxels pillarAndLedge() {
        return (x, y, z) -> {
            if (x == 0 && z == 0) {
                return y <= 65 ? CombatVoxels.SOLID : CombatVoxels.AIR;
            }
            if (x >= 8 && x <= 10 && z >= -1 && z <= 1) {
                return y <= 66 ? CombatVoxels.SOLID : CombatVoxels.AIR;
            }
            return y <= 63 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        };
    }

    /** Flat ground with a lava pool at x 3..5, z -3..3 (surface at 63). */
    public static CombatVoxels flatWithLava() {
        return (x, y, z) -> {
            if (x >= 3 && x <= 5 && z >= -3 && z <= 3 && y == 63) {
                return CombatVoxels.LAVA;
            }
            return y <= 63 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        };
    }
}
