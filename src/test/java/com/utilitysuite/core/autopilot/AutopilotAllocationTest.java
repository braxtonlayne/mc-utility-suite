package com.utilitysuite.core.autopilot;

import com.google.gson.JsonObject;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.IDangerModel;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Performance mandate: report, queries, mayAct, the notes and onTick allocate nothing. */
class AutopilotAllocationTest {

    private static final String WHY = "constant reason";

    @Test
    void hotPathsDoNotAllocate() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
        BlackBoxTest.FakeVitals fake = new BlackBoxTest.FakeVitals();
        fake.v.present = true;
        fake.v.health = 5f; // low: LOW_HEALTH is reported every tick
        fake.v.maxHealth = 20f;
        fake.v.air = 300;
        fake.v.maxAir = 300;
        fake.v.fireTicks = 30;
        Autopilot a = new Autopilot(clock, fake);
        a.onProfileLoaded(new ProfileConfig("t", new JsonObject()));
        a.setEnabled(true);
        a.onTick(); // first tick subscribes (no bus here) and warms the reason table
        a.noteActed(Reflex.SHIELD, WHY);
        a.noteDeclined(Reflex.DODGE, WHY);
        a.noteDone(Reflex.SHIELD);
        IDangerModel d = a.danger();
        float[] sink = new float[1];

        long bytes = AllocationProbe.measure(200_000, () -> {
            clock.t++;
            d.report(HazardKind.PROJECTILE, 6, 4f, 30f);
            d.report(HazardKind.FALL, 12, 7f, IDangerModel.NO_BEARING);
            if (d.any()) {
                sink[0] += d.expectedDamage(d.worst()) + d.ticksToImpact(HazardKind.FALL) + d.bearingYawDeg(
                        HazardKind.PROJECTILE);
            }
            if (a.mayAct(Reflex.SHIELD, 3, 4f)) {
                a.noteActed(Reflex.SHIELD, WHY);
                a.noteDone(Reflex.SHIELD);
            }
            a.mayAct(Reflex.DODGE, 2, 4f);
            a.noteDeclined(Reflex.DODGE, WHY);
            a.activeReflex();
            a.onTick();
        });
        // a constant few KB is JIT/probe noise (same at 50k and 200k); one 16-byte object per pass would be 3.2 MB
        assertTrue(bytes < 16_384, "hot paths allocated " + bytes + " bytes over 200k iterations");
    }
}
