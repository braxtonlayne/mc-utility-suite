package com.utilitysuite.combat.cues;

import com.utilitysuite.combat.tactics.MaceDivePlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CueLogicTest {

    @Test
    void disabledCueNeverPlays() {
        CueScheduler s = new CueScheduler();
        assertFalse(s.request(CueScheduler.Cue.PLAYER_BLIP, 0));
        s.setEnabled(CueScheduler.Cue.PLAYER_BLIP, true);
        assertTrue(s.request(CueScheduler.Cue.PLAYER_BLIP, 0));
    }

    @Test
    void perCueGapAndGlobalBucket() {
        CueScheduler s = new CueScheduler();
        for (CueScheduler.Cue c : CueScheduler.Cue.values()) {
            s.setEnabled(c, true);
        }
        s.setRate(3, 5);
        assertTrue(s.request(CueScheduler.Cue.LEASE_CLICK, 100));
        assertFalse(s.request(CueScheduler.Cue.LEASE_CLICK, 101), "per-cue gap (3 ticks)");
        assertTrue(s.request(CueScheduler.Cue.SWING_READY, 101));
        assertTrue(s.request(CueScheduler.Cue.PLAYER_BLIP, 101));
        assertFalse(s.request(CueScheduler.Cue.MACE_LETHAL, 101), "bucket of 3 is empty");
        assertTrue(s.request(CueScheduler.Cue.MACE_LETHAL, 106), "one token back after 5 ticks");
        assertFalse(s.request(CueScheduler.Cue.MACE_RISE, 106));
    }

    @Test
    void burstOverLongRunIsCapped() {
        CueScheduler s = new CueScheduler();
        s.setEnabled(CueScheduler.Cue.MACE_RISE, true);
        s.setRate(4, 5);
        int played = 0;
        for (long t = 0; t < 200; t++) {
            if (s.request(CueScheduler.Cue.MACE_RISE, t)) {
                played++;
            }
        }
        assertTrue(played <= 4 + 200 / 5, "rate-limited: " + played);
    }

    @Test
    void masterVolumeScalesAndZeroMutes() {
        CueScheduler s = new CueScheduler();
        s.setEnabled(CueScheduler.Cue.LEASE_CLICK, true);
        s.setMaster(0.5f);
        assertEquals(0.5f * CueScheduler.Cue.LEASE_CLICK.baseVolume, s.volume(CueScheduler.Cue.LEASE_CLICK), 1e-6);
        s.setMaster(0f);
        assertFalse(s.request(CueScheduler.Cue.LEASE_CLICK, 0));
    }

    @Test
    void maceToneRisesThenChimesOnceAtLethal() {
        MaceRiseCue c = new MaceRiseCue();
        float needed = 30f; // 6 base + 24 bonus -> lethal at a 10 block fall
        int rises = 0;
        int lethal = 0;
        float lastPitch = 0f;
        for (float fall = 0f; fall < 20f; fall += 0.25f) {
            int r = c.update(true, false, fall, 0, needed);
            if (r == MaceRiseCue.RISE) {
                rises++;
                assertTrue(c.pitch() > lastPitch, "pitch climbs");
                lastPitch = c.pitch();
            } else if (r == MaceRiseCue.LETHAL) {
                lethal++;
                assertTrue(MaceRiseCue.BASE_DAMAGE + MaceDivePlanner.smashBonus(fall, 0) >= needed);
            }
        }
        assertTrue(rises >= 1 && rises <= MaceRiseCue.STEPS);
        assertEquals(1, lethal);
        assertEquals(MaceRiseCue.NONE, c.update(true, true, 0f, 0, needed), "landing resets");
        assertEquals(MaceRiseCue.LETHAL, c.update(true, false, 19f, 0, needed), "a new fall can chime again");
    }

    @Test
    void maceSilentWithoutMaceOrBelowSmash() {
        MaceRiseCue c = new MaceRiseCue();
        assertEquals(MaceRiseCue.NONE, c.update(false, false, 30f, 0, 10f));
        assertEquals(MaceRiseCue.NONE, c.update(true, false, 1.0f, 0, 10f));
        // no target: aims at the FULL tier, so a long fall still chimes
        assertEquals(MaceRiseCue.LETHAL, c.update(true, false, 9f, 0, Float.NaN));
    }

    @Test
    void proximityReportsNewcomersOnly() {
        ProximityTracker t = new ProximityTracker(4);
        assertTrue(t.observe(7, 1));
        assertTrue(t.observe(8, 1));
        t.sweep(1);
        assertFalse(t.observe(7, 2), "still in range");
        t.sweep(2); // 8 left range
        assertEquals(1, t.size());
        assertTrue(t.observe(8, 3), "came back");
        t.observe(7, 3);
        t.sweep(3);
        for (int id = 100; id < 110; id++) {
            t.observe(id, 4);
        }
        assertEquals(4, t.size(), "capped, no growth");
    }
}
