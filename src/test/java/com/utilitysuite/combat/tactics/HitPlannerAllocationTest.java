package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HitPlanner#decide} and {@link GearRead#update} run every engaged tick. */
class HitPlannerAllocationTest {

    private static final long BUDGET = 1_024;
    private static final HitPlanner.Policy[] POLICIES = HitPlanner.Policy.values();

    @Test
    void decideAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HitPlanner p = new HitPlanner();
        CombatSnapshot s = HitPlannerTest.snap(2.5);
        s.sweepWeapon = true;
        long[] tick = {0};
        int[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            int phase = (int) (t % 40);
            s.attackStrength = Math.min(1f, phase / 12.5f);
            s.onGround = phase < 20 || phase > 27;
            s.pvy = s.onGround ? 0.0 : 0.3 - 0.08 * (phase - 20);
            s.fallDistance = s.pvy < 0 ? 0.1f : 0f;
            s.sprinting = (t & 3) != 0;
            s.sweepHostiles = (int) (t >> 6) & 3;
            s.sweepProtected = (int) (t >> 9) & 1;
            s.knockKind = (int) (t >> 11) % 5;
            s.knockValue = (t & 128) != 0 ? 0.5 : 0.0;
            p.policy = POLICIES[(int) (t >> 13) & 3];
            p.comboLive = (t & 256) != 0;
            p.targetIsPlayer = (t & 512) != 0;
            p.oppCharged = (t & 1024) != 0;
            HitPlannerTest.target(s, (t & 2048) != 0 ? 6.0 : 2.5);
            p.decide(s);
            if (p.swing()) {
                p.onSwing();
                sink[0]++;
            }
            sink[0] += p.jump() ? 1 : 0;
            sink[0] += p.kind().ordinal();
        });
        assertTrue(bytes < BUDGET, "HitPlanner.decide allocated " + bytes + " bytes over 50k calls");
        assertTrue(sink[0] != -1);
    }

    @Test
    void gearReadUpdateAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        GearRead g = new GearRead();
        long[] tick = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            if ((t & 15) == 0) {
                g.noteTaken(3.0 + (t & 7));
            }
            if ((t & 15) == 7) {
                g.noteDealt(2.0 + (t & 3));
            }
            g.update(20.0 - (t & 15), 20.0 - (t & 7), GearRead.afterArmor(7.0, 20.0, 8.0),
                    GearRead.afterArmor(9.0, 15.0, 0.0));
            sink[0] += g.takenWeight((t & 1) == 0) + g.ratio();
            if ((t & 4095) == 0) {
                g.reset();
            }
        });
        assertTrue(bytes < BUDGET, "GearRead.update allocated " + bytes + " bytes over 50k calls");
        assertTrue(sink[0] != -1.0);
    }
}
