package com.utilitysuite.jobs.goal;

import java.util.List;

/**
 * How a gather job finds ore.
 * <ul>
 *   <li>{@link #LIKE_A_PLAYER} (default): mines only ore it can see (open caves, ore exposed in the tunnel walls) and
 *       otherwise strip-mines at a sensible depth for that ore, the way a player does. No predicted or hidden ore
 *       positions are used.</li>
 *   <li>{@link #KNOWN_ORES}: heads straight for ore it knows of (loaded-chunk scans and seed predictions), even behind
 *       solid stone. Faster, less legit.</li>
 * </ul>
 * Engine free.
 */
public enum MiningStyle {
    LIKE_A_PLAYER,
    KNOWN_ORES;

    /** Strip-mine level when none of the ores is known: stay where the player is. */
    public static final int STAY = Integer.MIN_VALUE;

    /** {ore id fragment, lowest good y, highest good y, preferred y} for 1.18+ ore generation. */
    private static final Object[][] LEVELS = {
            {"ancient_debris", 8, 22, 15},
            {"nether_gold_ore", 10, 117, 15},
            {"nether_quartz_ore", 10, 117, 15},
            {"diamond_ore", -64, -48, -58},
            {"redstone_ore", -64, -32, -58},
            {"gold_ore", -64, 32, -16},
            {"lapis_ore", -32, 32, 0},
            {"iron_ore", -24, 56, 16},
            {"copper_ore", 0, 80, 48},
            {"coal_ore", 0, 96, 48},
            {"emerald_ore", 64, 256, 100},
    };

    /** {lowest good y, highest good y, preferred y} of the first known ore among the ids, or null. */
    public static int[] band(List<String> blockIds) {
        for (String id : blockIds) {
            for (Object[] row : LEVELS) {
                if (id.contains((String) row[0])) {
                    return new int[] {(int) row[1], (int) row[2], (int) row[3]};
                }
            }
        }
        return null;
    }

    /**
     * The y to strip-mine at for the given block ids: the current feet y when it is already in a good band for one of
     * them, else the preferred y of the first known ore, else {@link #STAY}.
     */
    public static int stripLevel(List<String> blockIds, int feetY) {
        int preferred = STAY;
        for (String id : blockIds) {
            for (Object[] row : LEVELS) {
                if (!id.contains((String) row[0])) {
                    continue;
                }
                if (feetY >= (int) row[1] && feetY <= (int) row[2]) {
                    return feetY;
                }
                if (preferred == STAY) {
                    preferred = (int) row[3];
                }
                break;
            }
        }
        return preferred;
    }
}
