package com.utilitysuite.tools.enchant;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Random;

/**
 * Engine-free first stage of the remote enchantment-seed cracker. The open enchanting menu syncs the player's seed
 * through a container data slot that carries only its low 16 bits, so 2^16 candidates remain; the three slot level
 * costs (which depend only on the seed and the bookshelf power for any enchantable item) narrow them down. Vanilla's
 * legacy random source is the same LCG as {@link java.util.Random}, so the cost roll is reproduced here without the game.
 */
public final class SeedCostFilter {

    private SeedCostFilter() {
    }

    /** Vanilla EnchantmentHelper#getEnchantmentCost for slots 0..2 with the menu's "cost below slot+1 = 0" rule. */
    public static void costs(Random random, int seed, int power, int[] out) {
        if (power > 15) {
            power = 15;
        }
        random.setSeed(seed);
        for (int k = 0; k < 3; k++) {
            int j = random.nextInt(8) + 1 + (power >> 1) + random.nextInt(power + 1);
            int c = k == 0 ? Math.max(j / 3, 1) : k == 1 ? j * 2 / 3 + 1 : Math.max(j, power * 2);
            out[k] = c < k + 1 ? 0 : c;
        }
    }

    /**
     * Candidates as packed pairs {@code seed, power} (flattened) that reproduce {@code observed} costs.
     *
     * @param low16    the low 16 bits seen in the menu
     * @param observed the three costs shown
     * @param prior    previous candidate seeds to narrow (null = scan all 2^16)
     */
    public static IntArrayList candidates(int low16, int[] observed, int[] prior) {
        Random random = new Random(0);
        int[] tmp = new int[3];
        IntArrayList out = new IntArrayList();
        int n = prior == null ? 65536 : prior.length;
        for (int i = 0; i < n; i++) {
            int seed = prior == null ? (i << 16) | (low16 & 0xFFFF) : prior[i];
            for (int power = 0; power <= 15; power++) {
                costs(random, seed, power, tmp);
                if (tmp[0] == observed[0] && tmp[1] == observed[1] && tmp[2] == observed[2]) {
                    out.add(seed);
                    out.add(power);
                }
            }
        }
        return out;
    }
}
