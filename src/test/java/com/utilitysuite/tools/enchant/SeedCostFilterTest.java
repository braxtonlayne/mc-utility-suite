package com.utilitysuite.tools.enchant;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedCostFilterTest {

    /** The engine-free roll relies on vanilla's legacy random being java.util.Random's LCG. */
    @Test
    void legacyRandomMatchesJavaRandom() {
        int[] seeds = {0, 1, -1, 123456789, Integer.MIN_VALUE, 0x7ABC1234};
        for (int seed : seeds) {
            LegacyRandomSource mc = new LegacyRandomSource(0);
            mc.setSeed(seed);
            Random jr = new Random(0);
            jr.setSeed(seed);
            for (int i = 0; i < 50; i++) {
                int bound = 1 + (i % 17);
                assertEquals(jr.nextInt(bound), mc.nextInt(bound), "seed " + seed + " draw " + i);
            }
        }
    }

    /** Vanilla's cost formula with the vanilla random source, written out independently for the check. */
    private static int[] vanillaCosts(int seed, int power) {
        LegacyRandomSource r = new LegacyRandomSource(0);
        r.setSeed(seed);
        int[] out = new int[3];
        for (int k = 0; k < 3; k++) {
            int j = r.nextInt(8) + 1 + (power >> 1) + r.nextInt(power + 1);
            int c = k == 0 ? Math.max(j / 3, 1) : (k == 1 ? j * 2 / 3 + 1 : Math.max(j, power * 2));
            out[k] = c < k + 1 ? 0 : c;
        }
        return out;
    }

    @Test
    void costsMatchVanillaRoll() {
        Random jr = new Random(0);
        int[] got = new int[3];
        for (int seed = -500; seed < 500; seed += 7) {
            for (int power = 0; power <= 15; power++) {
                SeedCostFilter.costs(jr, seed, power, got);
                assertArrayEq(vanillaCosts(seed, power), got);
            }
        }
    }

    @Test
    void candidatesContainTrueSeedAndNarrowAcrossPowers() {
        int truth = 0x5A3C9E71;
        int[] obs15 = vanillaCosts(truth, 15);
        IntArrayList first = SeedCostFilter.candidates(truth & 0xFFFF, obs15, null);
        assertTrue(contains(first, truth), "true seed must survive");
        int[] prior = seedsOf(first);
        int[] obs3 = vanillaCosts(truth, 3);
        IntArrayList second = SeedCostFilter.candidates(truth & 0xFFFF, obs3, prior);
        assertTrue(contains(second, truth));
        assertTrue(second.size() <= first.size(), "a second observation can only narrow");
    }

    private static boolean contains(IntArrayList pairs, int seed) {
        for (int i = 0; i < pairs.size(); i += 2) {
            if (pairs.getInt(i) == seed) {
                return true;
            }
        }
        return false;
    }

    private static int[] seedsOf(IntArrayList pairs) {
        it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet s = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet();
        for (int i = 0; i < pairs.size(); i += 2) {
            s.add(pairs.getInt(i));
        }
        return s.toIntArray();
    }

    private static void assertArrayEq(int[] a, int[] b) {
        assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(b));
    }
}
