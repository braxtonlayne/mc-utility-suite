package com.utilitysuite.core.autopilot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlackBoxTest {

    @Test
    void wrapsAtCapacityAndKeepsTheNewest() {
        BlackBox b = new BlackBox();
        for (int i = 0; i < BlackBox.CAPACITY + 10; i++) {
            b.record(i, 20f, null, null, BlackBox.EV_SAMPLE, null);
        }
        assertEquals(BlackBox.CAPACITY, b.size());
        long now = BlackBox.CAPACITY + 9;
        BlackBox.Snapshot s = b.snapshot(now, "test");
        assertEquals(BlackBox.WINDOW_TICKS + 1, s.rows(), "only the last 10 s");
        assertEquals(-BlackBox.WINDOW_TICKS, s.relTick(0), "oldest first");
        assertEquals(0, s.relTick(s.rows() - 1));
    }

    @Test
    void dumpKeepsOrderAndReasons() {
        BlackBox b = new BlackBox();
        b.record(10, 20f, HazardKind.FALL, null, BlackBox.EV_SAMPLE, null);
        b.record(11, 20f, HazardKind.FALL, Reflex.FALL_CLUTCH, BlackBox.EV_ACTED, "water");
        b.record(12, 20f, HazardKind.FALL, Reflex.FALL_CLUTCH, BlackBox.EV_DONE, null);
        b.record(13, 4f, null, Reflex.SHIELD, BlackBox.EV_DECLINED, "no shield");
        BlackBox.Snapshot s = b.snapshot(13, "death");
        assertEquals(4, s.rows());
        assertEquals("ACTED", s.event(1));
        assertEquals("water", s.reason(1));
        assertEquals("SHIELD", s.reflex(3));
        assertEquals("no shield", s.reason(3));
        JsonObject json = JsonParser.parseString(s.toJson()).getAsJsonObject();
        assertEquals("death", json.get("cause").getAsString());
        assertEquals(4, json.getAsJsonArray("rows").size());
        assertEquals(-3, json.getAsJsonArray("rows").get(0).getAsJsonObject().get("t").getAsInt());
    }

    @Test
    void reasonsAreInternedInAFixedTable() {
        BlackBox b = new BlackBox();
        assertEquals(b.intern("a"), b.intern("a"));
        for (int i = 0; i < BlackBox.MAX_REASONS + 5; i++) {
            b.intern("r" + i);
        }
        assertEquals(-1, b.intern("one too many"), "a full table records no reason rather than growing");
    }

    @Test
    void autopilotDumpsOnABigHitAndDebounces() {
        DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
        FakeVitals fake = new FakeVitals();
        Autopilot a = new Autopilot(clock, fake);
        a.onProfileLoaded(new ProfileConfig("t", new JsonObject()));
        a.setEnabled(true);
        fake.v.present = true;
        fake.v.health = 20f;
        fake.v.maxHealth = 20f;
        fake.v.air = 300;
        fake.v.maxAir = 300;
        a.onTick();
        clock.t++;
        fake.v.health = 12f; // an 8 point hit, over the default 6
        a.onTick();
        assertEquals(1, a.dumpCount());
        clock.t++;
        fake.v.health = 0f;
        fake.v.dead = true;
        a.onTick();
        assertEquals(1, a.dumpCount(), "a death right after the hit is debounced into the same dump");
        assertNull(a.dump("again"));
        clock.t += BlackBoxWriter.DEBOUNCE_TICKS;
        assertNotNull(a.dump("later"));
    }

    private static Autopilot offAutopilot(DangerModelTest.ManualClock clock, FakeVitals fake, boolean always) {
        ProfileConfig p = new ProfileConfig("t", new JsonObject());
        p.set(Autopilot.ID, Autopilot.K_BLACKBOX_ALWAYS, always);
        Autopilot a = new Autopilot(clock, fake);
        a.onProfileLoaded(p);
        fake.v.present = true;
        fake.v.health = 20f;
        fake.v.maxHealth = 20f;
        fake.v.air = 300;
        fake.v.maxAir = 300;
        return a;
    }

    @Test
    void recordsWhileOffWhenAlwaysIsOn() {
        DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
        FakeVitals fake = new FakeVitals();
        Autopilot a = offAutopilot(clock, fake, true);
        assertFalse(a.isOn());
        for (int i = 0; i < 5; i++) {
            clock.t++;
            fake.v.health = 20f - i;
            a.tickWhileOff();
        }
        assertEquals(5, a.blackBox().size(), "one row per changed health sample while off");
        BlackBox.Snapshot s = a.blackBox().snapshot(clock.t, "test");
        for (int i = 0; i < s.rows(); i++) {
            assertTrue(s.autopilotOff(i), "row " + i + " is marked off");
            assertNull(s.reflex(i), "no reflex while off");
        }
        assertTrue(s.toJson().contains("\"off\":true"));
    }

    @Test
    void recordsNothingWhileOffWhenAlwaysIsOff() {
        DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
        FakeVitals fake = new FakeVitals();
        Autopilot a = offAutopilot(clock, fake, false);
        for (int i = 0; i < 5; i++) {
            clock.t++;
            fake.v.health = 20f - i;
            a.tickWhileOff();
        }
        clock.t++;
        fake.v.health = 0f;
        fake.v.dead = true;
        a.tickWhileOff();
        assertEquals(0, a.blackBox().size());
        assertEquals(0, a.dumpCount());
    }

    @Test
    void aDeathWhileOffDumpsRowsMarkedOff() {
        DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
        FakeVitals fake = new FakeVitals();
        Autopilot a = offAutopilot(clock, fake, true);
        a.tickWhileOff();
        clock.t++;
        fake.v.health = 17f;
        a.tickWhileOff();
        clock.t++;
        fake.v.health = 0f;
        fake.v.dead = true;
        a.tickWhileOff();
        assertEquals(1, a.dumpCount(), "a death while off still saves the black box");
        BlackBox.Snapshot s = a.blackBox().snapshot(clock.t, "check");
        assertTrue(s.rows() >= 3);
        for (int i = 0; i < s.rows(); i++) {
            assertTrue(s.autopilotOff(i));
        }
        assertEquals("DAMAGE", s.event(s.rows() - 1));
        assertEquals(Autopilot.WHY_DEATH, s.reason(s.rows() - 1));
        assertFalse(a.isOn(), "recording never turns the autopilot on");
        for (Reflex r : Reflex.values()) {
            assertFalse(a.allows(r));
            assertFalse(a.mayAct(r, 1, 20f));
        }
        assertNull(a.activeReflex());
    }

    @Test
    void onRowsAreNotMarkedOffAndOffTickIsIgnoredWhileOn() {
        DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
        FakeVitals fake = new FakeVitals();
        Autopilot a = offAutopilot(clock, fake, true);
        a.setEnabled(true);
        a.onTick();
        clock.t++;
        fake.v.health = 18f;
        a.tickWhileOff(); // the registry ticks it while on: the off tick is a no-op
        assertEquals(1, a.blackBox().size());
        assertFalse(a.blackBox().snapshot(clock.t, "x").autopilotOff(0));
    }

    static final class FakeVitals implements VitalsMonitor.VitalsSource {
        final VitalsMonitor.Vitals v = new VitalsMonitor.Vitals();

        @Override
        public void sample(VitalsMonitor.Vitals out) {
            out.present = v.present;
            out.health = v.health;
            out.maxHealth = v.maxHealth;
            out.air = v.air;
            out.maxAir = v.maxAir;
            out.fireTicks = v.fireTicks;
            out.fireImmune = v.fireImmune;
            out.dead = v.dead;
        }
    }
}
