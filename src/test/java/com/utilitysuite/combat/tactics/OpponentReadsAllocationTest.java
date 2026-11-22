package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FeintPlanner#observe}/{@link FeintPlanner#plan} and the {@link SkillEstimator} notes run every engaged tick. */
class OpponentReadsAllocationTest {

    private static final long BUDGET = 1_024;

    @Test
    void feintPlannerAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        FeintPlanner f = new FeintPlanner();
        long[] tick = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            int id = (int) (t % 11); // more ids than slots: exercises slot reuse
            int strafe = (t & 32) == 0 ? 1 : -1;
            double a = t * 0.07;
            double px = 3 * Math.sin(a);
            double pz = 3 * Math.cos(a);
            boolean back = (t & 15) < 6;
            f.observe(id, t, 0, 0, (float) (t % 360), (t & 7) == 0 ? 0.3 : 0, 0.1, (t & 3) == 0,
                    px, pz, back ? px * 0.05 : -pz * 0.05, back ? pz * 0.05 : px * 0.05, strafe);
            FeintPlanner.Move m = f.plan(id, t, strafe, 2.5 + (t & 3) * 0.4, (int) (t % 13), (int) (t % 7),
                    (t & 64) == 0, ((t * 2654435761L) & 1023) / 1024.0);
            sink[0] += m.ordinal() + f.strafeOverride() + f.forwardOverride() + (f.allowSprint() ? 1 : 0)
                    + f.preAim(id) + f.baitRate(id) + f.aimLeadTicks(id);
        });
        assertTrue(bytes < BUDGET, "FeintPlanner allocated " + bytes + " bytes over 50k ticks");
        assertTrue(sink[0] != 12345.678);
    }

    @Test
    void skillEstimatorAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        SkillEstimator e = new SkillEstimator();
        long[] tick = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            int id = (int) (t % 11);
            switch ((int) (t % 5)) {
                case 0 -> e.noteOpponentSwing(id, t, (t & 1) == 0);
                case 1 -> e.noteOpponentHit(id, t, 4f);
                case 2 -> e.noteOwnHit(id, t);
                case 3 -> e.noteOwnMiss(id, t);
                default -> e.noteReaction(id, (int) (t % 15));
            }
            sink[0] += e.skill(id) + e.samples(id) + e.bestCombo(id);
        });
        assertTrue(bytes < BUDGET, "SkillEstimator allocated " + bytes + " bytes over 50k events");
        assertTrue(sink[0] != 12345.678);
    }
}
