package com.utilitysuite.core.autopilot;

import com.google.gson.JsonObject;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.IDangerModel;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflexSchedulerTest {

    private final DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
    private final DangerModel danger = new DangerModel(clock);
    private final ReflexScheduler s = new ReflexScheduler(clock);

    @Test
    void bestDamagePerTickWins() {
        danger.report(HazardKind.PROJECTILE, 10, 5f, 0f);
        // dodge bids first: 5 damage in 2 ticks (2.5/tick)
        assertTrue(s.mayAct(Reflex.DODGE, true, 2, 5f, danger), "alone it may act");
        // shield bids: 5 damage in 1 tick (5/tick) beats the dodge
        assertTrue(s.mayAct(Reflex.SHIELD, true, 1, 5f, danger));
        assertFalse(s.mayAct(Reflex.DODGE, true, 2, 5f, danger), "the dodge now loses to the live shield bid");
    }

    @Test
    void aReflexThatCannotFinishInTimeLoses() {
        danger.report(HazardKind.PROJECTILE, 3, 6f, 0f);
        // shield: high rate but needs 5 ticks (a 90 deg turn at 450 deg/s plus the raise) - too late
        assertTrue(s.mayAct(Reflex.SHIELD, true, 5, 20f, danger));
        // dodge: lower rate but done in 2 ticks
        assertTrue(s.mayAct(Reflex.DODGE, true, 2, 6f, danger));
        assertFalse(s.mayAct(Reflex.SHIELD, true, 5, 20f, danger), "late beats nothing; in time beats late");
    }

    @Test
    void actingReflexHoldsTheTurn() {
        danger.report(HazardKind.FALL, 20, 10f, IDangerModel.NO_BEARING);
        assertTrue(s.mayAct(Reflex.FALL_CLUTCH, true, 8, 10f, danger));
        s.noteActed(Reflex.FALL_CLUTCH);
        assertEquals(Reflex.FALL_CLUTCH, s.active());
        assertFalse(s.mayAct(Reflex.SHIELD, true, 1, 50f, danger), "a better bid waits for the acting reflex");
        assertTrue(s.mayAct(Reflex.FALL_CLUTCH, true, 8, 10f, danger), "the acting reflex keeps its yes");
        s.noteDone(Reflex.FALL_CLUTCH);
        assertNull(s.active());
        assertTrue(s.mayAct(Reflex.SHIELD, true, 1, 50f, danger));
    }

    @Test
    void anActingReflexThatGoesSilentTimesOut() {
        s.noteActed(Reflex.DODGE);
        clock.t += ReflexScheduler.ACTING_TIMEOUT_TICKS;
        assertNull(s.active());
    }

    @Test
    void oldBidsStopCounting() {
        assertTrue(s.mayAct(Reflex.SHIELD, true, 1, 50f, danger));
        clock.t += ReflexScheduler.BID_LIFE_TICKS;
        assertTrue(s.mayAct(Reflex.DODGE, true, 4, 1f, danger), "the shield did not bid again");
    }

    @Test
    void disabledReflexIsRefusedAndDoesNotBlock() {
        assertFalse(s.mayAct(Reflex.SHIELD, false, 1, 50f, danger));
        assertTrue(s.mayAct(Reflex.DODGE, true, 4, 1f, danger));
    }

    @Test
    void autopilotRefusesADisabledReflexAndKeepsAllowsCompatible() {
        ProfileConfig p = new ProfileConfig("t", new JsonObject());
        p.set(Autopilot.ID, Autopilot.K_SHIELD, false);
        Autopilot a = new Autopilot(clock, null);
        a.onProfileLoaded(p);
        assertFalse(a.mayAct(Reflex.DODGE, 1, 1f), "off: nothing may act");
        a.setEnabled(true);
        assertFalse(a.allows(Reflex.SHIELD));
        assertFalse(a.mayAct(Reflex.SHIELD, 1, 50f));
        assertTrue(a.mayAct(Reflex.DODGE, 1, 1f));
        a.noteActed(Reflex.DODGE, "projectile");
        assertEquals(Reflex.DODGE, a.activeReflex());
        a.onStopAll();
        assertNull(a.activeReflex(), "Stop-All clears the acting reflex");
    }
}
