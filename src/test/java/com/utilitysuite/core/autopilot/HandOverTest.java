package com.utilitysuite.core.autopilot;

import com.google.gson.JsonObject;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IAutopilot.Reach;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-over key: the engine-free state and the autopilot's answers while it is held. */
class HandOverTest {

    // ---- HandOverState --------------------------------------------------------------------------------------------

    @Test
    void heldFromThePressTickToTheReleaseTick() {
        HandOverState s = new HandOverState();
        assertFalse(s.held());
        assertEquals(HandOverState.NEVER, s.startTick());
        assertEquals(HandOverState.NO_CHANGE, s.update(false, 1));
        assertEquals(HandOverState.STARTED, s.update(true, 2));
        assertTrue(s.held());
        assertEquals(2, s.startTick());
        assertEquals(HandOverState.NO_CHANGE, s.update(true, 3));
        assertEquals(HandOverState.ENDED, s.update(false, 4), "released: over on that very tick");
        assertFalse(s.held());
        assertEquals(4, s.endTick());
        assertEquals(2, s.startTick(), "the start of the last hand-over stays readable");
    }

    @Test
    void stopAllEndsItAndTheKeyMustBeLetGoFirst() {
        HandOverState s = new HandOverState();
        s.update(true, 1);
        assertEquals(HandOverState.ENDED, s.revoke(2));
        assertFalse(s.held());
        assertEquals(HandOverState.NO_CHANGE, s.update(true, 3), "still down after Stop-All: nothing");
        assertFalse(s.held());
        s.update(false, 4);
        assertEquals(HandOverState.STARTED, s.update(true, 5), "let go and pressed again");
    }

    @Test
    void doubleTapLatchesOnlyWhenTheSettingIsOn() {
        HandOverState s = new HandOverState();
        s.update(true, 1);
        s.update(false, 2);
        s.update(true, 3);
        s.update(false, 4);
        assertFalse(s.held(), "no latch by default");

        s.setLatchOnDoubleTap(true);
        s.update(true, 20);
        s.update(false, 21);
        s.update(true, 23); // second press within the window
        assertTrue(s.latched());
        s.update(false, 24);
        assertTrue(s.held(), "latched: stays on with the key up");
        assertEquals(HandOverState.ENDED, s.update(true, 60), "the next press ends it");
        assertFalse(s.held());
        s.update(true, 61);
        assertFalse(s.held(), "the unlatching press does not hand over again while it stays down");
        s.update(false, 62);

        s.update(true, 100);
        s.update(false, 101);
        s.update(true, 120); // too slow: an ordinary hold
        assertFalse(s.latched());
        s.update(false, 121);
        assertFalse(s.held());

        s.update(true, 200);
        s.update(false, 201);
        s.update(true, 202);
        assertTrue(s.latched());
        assertEquals(HandOverState.ENDED, s.revoke(203), "Stop-All beats the latch");
        assertFalse(s.latched());
    }

    // ---- Autopilot ------------------------------------------------------------------------------------------------

    private static final class Rig {
        final DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
        final boolean[] key = {false};
        final Autopilot a = new Autopilot(clock, null);

        Rig() {
            a.onProfileLoaded(new ProfileConfig("t", new JsonObject()));
            a.useHandOverKey(() -> key[0]);
        }

        void tick() {
            clock.t++;
            a.tickWhileOff(); // the every-tick listener
            if (a.isEnabled()) {
                a.onTick(); // the module registry only ticks it while on
            }
        }
    }

    @Test
    void offAndUnpressedNothingChanges() {
        Rig r = new Rig();
        r.tick();
        IAutopilot ap = r.a;
        assertFalse(ap.handOverHeld());
        assertFalse(ap.isOn());
        assertEquals(-1L, ap.handOverStartTick());
        for (Reflex x : Reflex.values()) {
            assertFalse(ap.allows(x), x.name());
        }
    }

    @Test
    void heldArmsEveryReflexWithTheToggleOff() {
        Rig r = new Rig();
        // every reflex switched off and danger-only: the key still arms all of them
        JsonObject root = new JsonObject();
        ProfileConfig p = new ProfileConfig("t", root);
        for (String k : new String[]{Autopilot.K_CLUTCH, Autopilot.K_PARKOUR, Autopilot.K_BLOCKS, Autopilot.K_TOTEM,
                Autopilot.K_SHIELD, Autopilot.K_DODGE, Autopilot.K_ESCAPE, Autopilot.K_ELYTRA, Autopilot.K_OFFHAND}) {
            p.set(Autopilot.ID, k, false);
        }
        p.set(Autopilot.ID, Autopilot.K_REACH, Reach.DANGER_ONLY.name());
        r.a.onProfileLoaded(p);

        r.key[0] = true;
        assertFalse(r.a.handOverHeld(), "takes effect on the next tick");
        r.tick();
        assertTrue(r.a.handOverHeld());
        assertEquals(r.clock.t, r.a.handOverStartTick());
        assertTrue(r.a.isOn());
        assertFalse(r.a.isEnabled(), "the toggle itself is untouched");
        assertEquals(Reach.ASSIST, r.a.reach());
        for (Reflex x : Reflex.values()) {
            assertTrue(r.a.allows(x), x.name());
        }
        assertTrue(r.a.mayAct(Reflex.FALL_CLUTCH, 2, 10f));
        r.a.noteActed(Reflex.FALL_CLUTCH, "test");
        assertEquals(Reflex.FALL_CLUTCH, r.a.activeReflex());
    }

    @Test
    void releaseHandsBackWithinTheTick() {
        Rig r = new Rig();
        r.key[0] = true;
        r.tick();
        assertTrue(r.a.mayAct(Reflex.SHIELD, 2, 6f));
        r.a.noteActed(Reflex.SHIELD, "test");

        r.key[0] = false; // key up: no tick has run yet
        assertFalse(r.a.handOverHeld(), "the release counts before the next tick");
        assertFalse(r.a.isOn());
        assertNull(r.a.activeReflex());
        for (Reflex x : Reflex.values()) {
            assertFalse(r.a.allows(x), x.name());
            assertFalse(r.a.mayAct(x, 1, 20f), x.name());
            assertNull(r.a.chainLease(x, "test"), x.name());
        }
        r.tick();
        assertFalse(r.a.handOverHeld());
        r.key[0] = true;
        r.tick();
        assertNull(r.a.activeReflex(), "the old reflex turn was dropped on release");
        assertTrue(r.a.mayAct(Reflex.DODGE, 1, 2f));
    }

    @Test
    void releaseKeepsAnAutopilotThatIsOnRunning() {
        Rig r = new Rig();
        r.a.setEnabled(true);
        r.key[0] = true;
        r.tick();
        assertTrue(r.a.mayAct(Reflex.SHIELD, 2, 6f));
        r.a.noteActed(Reflex.SHIELD, "test");
        r.key[0] = false;
        r.tick();
        assertTrue(r.a.isOn());
        assertEquals(Reflex.SHIELD, r.a.activeReflex(), "the toggle's own reflex is not cut short by the key");
    }

    @Test
    void stopAllRevokesTheKey() {
        Rig r = new Rig();
        r.key[0] = true;
        r.tick();
        assertTrue(r.a.handOverHeld());
        r.a.onStopAll();
        assertFalse(r.a.handOverHeld(), "Stop-All beats the key at once");
        assertFalse(r.a.isOn());
        for (int i = 0; i < 5; i++) {
            r.tick(); // still held down
            assertFalse(r.a.handOverHeld());
            assertFalse(r.a.allows(Reflex.FALL_CLUTCH));
        }
        r.key[0] = false;
        r.tick();
        r.key[0] = true;
        r.tick();
        assertTrue(r.a.handOverHeld(), "let go and pressed again");
    }

    @Test
    void aKeyThatCannotBeReadIsNeverHeld() {
        Rig r = new Rig();
        r.a.useHandOverKey(() -> {
            throw new NoClassDefFoundError("no game");
        });
        r.tick();
        r.tick();
        assertFalse(r.a.handOverHeld());
    }

    @Test
    void perTickPathsDoNotAllocate() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Rig r = new Rig();
        r.tick();
        boolean[] sink = new boolean[1];
        long bytes = AllocationProbe.measure(200_000, () -> {
            long t = ++r.clock.t;
            r.key[0] = (t & 15) < 12; // held 12 ticks, up 4: press and release edges included
            r.a.tickWhileOff();
            sink[0] ^= r.a.handOverHeld() ^ r.a.isOn() ^ r.a.allows(Reflex.PARKOUR)
                    ^ (r.a.reach() == Reach.ASSIST) ^ (r.a.handOverStartTick() > 0)
                    ^ r.a.mayAct(Reflex.DODGE, 2, 4f);
        });
        assertTrue(bytes < 16_384, "hand-over tick allocated " + bytes + " bytes over 200k iterations");
    }
}
