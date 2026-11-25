package com.utilitysuite.jobs.survival;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.jobs.survival.PiglinAdvisor.Act;
import com.utilitysuite.jobs.survival.PiglinAdvisor.BarterInputs;
import com.utilitysuite.jobs.survival.PiglinAdvisor.Drop;
import com.utilitysuite.jobs.survival.PiglinAdvisor.MutableDecision;
import com.utilitysuite.jobs.survival.PiglinAdvisor.Piglin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allocation-free barter step (PiglinGuard's half-second decision): same verdicts as the list API on random
 * scenes, capacity handling, and zero bytes allocated per decision once the buffers exist.
 */
class PiglinBarterAllocationTest {

    private static final String[] ITEMS = {"minecraft:gravel", "minecraft:ender_pearl", "minecraft:obsidian",
            "minecraft:soul_sand", "minecraft:string", "minecraft:quartz"};

    @Test
    void primitiveVariantMatchesListApiOnRandomScenes() {
        Random r = new Random(42);
        BarterInputs in = new BarterInputs(8, 8);
        MutableDecision out = new MutableDecision();
        for (int round = 0; round < 5_000; round++) {
            List<Piglin> ps = new ArrayList<>();
            List<Drop> ds = new ArrayList<>();
            int np = r.nextInt(6);
            int nd = r.nextInt(6);
            for (int i = 0; i < np; i++) {
                ps.add(new Piglin(i + 1, r.nextDouble() * 20, r.nextInt(4) != 0, r.nextInt(6) == 0,
                        r.nextInt(3) == 0));
            }
            for (int i = 0; i < nd; i++) {
                ds.add(new Drop(100 + i, ITEMS[r.nextInt(ITEMS.length)], r.nextDouble() * 10));
            }
            int ingots = r.nextInt(3);
            int since = r.nextInt(40);
            double range = r.nextBoolean() ? 6.0 : 0.0;
            var expected = PiglinAdvisor.barter(ingots, ps, ds, since, 20, range, Set.of());
            in.clear();
            for (Piglin p : ps) {
                in.addPiglin(p.entityId(), p.distance(), p.adult(), p.angry(), p.admiring());
            }
            for (Drop d : ds) {
                in.addDrop(d.entityId(), d.distance(), PiglinAdvisor.isJunk(d.itemId(), Set.of()));
            }
            Act a = PiglinAdvisor.barter(ingots, in, since, 20, range, out);
            assertEquals(expected.act(), a, "round " + round);
            assertEquals(expected.act(), out.act);
            assertEquals(expected.targetId(), out.targetId, "round " + round);
        }
    }

    @Test
    void capacityIsBoundedAndClearResets() {
        BarterInputs in = new BarterInputs(2, 1);
        assertTrue(in.addPiglin(1, 2, true, false, false));
        assertTrue(in.addPiglin(2, 3, true, false, false));
        assertFalse(in.addPiglin(3, 1, true, false, false)); // full: ignored
        assertTrue(in.addDrop(9, 1, false));
        assertFalse(in.addDrop(10, 1, false));
        MutableDecision out = new MutableDecision();
        assertEquals(Act.GIVE, PiglinAdvisor.barter(5, in, 40, 20, 6, out));
        assertEquals(1, out.targetId);
        in.clear();
        assertEquals(0, in.piglinCount());
        assertEquals(0, in.dropCount());
        assertEquals(Act.NONE, PiglinAdvisor.barter(0, in, 40, 20, 6, out));
        assertEquals(-1, out.targetId);
    }

    @Test
    void decisionAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        BarterInputs in = new BarterInputs(32, 64);
        MutableDecision out = new MutableDecision();
        int[] tick = {0};
        long bytes = AllocationProbe.measure(10_000, () -> {
            int t = tick[0]++;
            in.clear();
            for (int i = 0; i < 12; i++) {
                in.addPiglin(i, 1.0 + ((t + i) % 15), (i & 3) != 0, i == 11 && (t & 7) == 0, (i % 5) == 0);
            }
            for (int i = 0; i < 20; i++) {
                in.addDrop(100 + i, (t + i) % 9, (i & 1) == 0);
            }
            PiglinAdvisor.barter(t & 1, in, t % 40, 20, 6.0, out);
        });
        assertEquals(0, bytes, "barter decision allocated " + bytes + " bytes over 10000 calls");
    }
}
