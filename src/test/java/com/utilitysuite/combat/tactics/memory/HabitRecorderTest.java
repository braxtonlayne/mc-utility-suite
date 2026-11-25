package com.utilitysuite.combat.tactics.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.combat.tactics.memory.HabitRecorder.*;
import static org.junit.jupiter.api.Assertions.*;

/** C6 habit record: pearls, ranged, mace; persistence (v2, v1 compatible); recalled decisions; zero allocation. */
class HabitRecorderTest {

    private static final double NAN = Double.NaN;
    private static final String UUID = "00000000-0000-0000-0000-000000000042";

    /** {@code n} calm ground ticks with the given item. */
    private static long ground(HabitRecorder r, long clock, int n, int held) {
        for (int i = 0; i < n; i++) {
            r.tick(clock++, true, 64, held, false, false);
        }
        return clock;
    }

    @Test
    void pearlsAreClassifiedByDirectionAndHealth() {
        HabitRecorder r = new HabitRecorder();
        r.reset();
        long c = ground(r, 0, 400, HELD_OTHER); // 20 s
        // target at (0,0), we stand at (5,0): +x is toward us
        r.onProjectile(PROJ_PEARL, -1.2, 0.4, 0.1, 6f, 0, 0, 5, 0);  // away, at 6 HP: escape + low HP
        r.onProjectile(PROJ_PEARL, -1.0, 0.3, -0.2, 7f, 0, 0, 5, 0); // away again at 7 HP
        r.onProjectile(PROJ_PEARL, 1.3, 0.3, 0.0, 18f, 0, 0, 5, 0);  // toward us: engage
        r.onProjectile(PROJ_PEARL, 0.0, 0.5, 1.5, 15f, 0, 0, 5, 0);  // sideways: neither
        OpponentMemory.HabitSummary h = r.summary();
        assertEquals(4 * 1200.0 / 400, h.pearlsPerMin(), 1e-9);
        assertEquals(0.5, h.pearlLowHpShare(), 1e-9);
        assertEquals(0.5, h.pearlEscapeShare(), 1e-9);
        assertEquals(0.25, h.pearlEngageShare(), 1e-9);
        assertEquals(5.0, h.pearlDistance(), 1e-9);
        assertEquals(6.5, r.pearlOutHp(), 1e-9);
        assertEquals(400, c);
    }

    @Test
    void rangedShotsRecordWeaponDrawAndRange() {
        HabitRecorder r = new HabitRecorder();
        long c = ground(r, 0, 300, HELD_BOW);
        for (int i = 0; i < 20; i++) {
            r.tick(c++, true, 64, HELD_BOW, true, false); // a 20-tick draw
        }
        r.onProjectile(PROJ_ARROW, 0, 0.2, 2.8, 20f, 0, 0, 0, 15); // released at 15 blocks
        r.tick(c++, true, 64, HELD_BOW, false, false);
        // a crossbow charged earlier and shot 30 ticks later
        for (int i = 0; i < 25; i++) {
            r.tick(c++, true, 64, HELD_CROSSBOW, true, false);
        }
        c = ground(r, c, 30, HELD_CROSSBOW);
        r.onProjectile(PROJ_ARROW, 0, 0.1, 3.1, 20f, 0, 0, 0, 9);
        r.onProjectile(PROJ_TRIDENT, 0, 0.1, 2.5, 20f, 0, 0, 0, 6); // no draw left to credit
        r.onProjectile(PROJ_ARROW, 0, 0, 0.01, 20f, 0, 0, 0, 6);    // a lying arrow: ignored
        OpponentMemory.HabitSummary h = r.summary();
        assertEquals(3, r.rangedShots());
        assertEquals(1 / 3.0, h.bowShare(), 1e-9);
        assertEquals(1 / 3.0, h.crossbowShare(), 1e-9);
        assertEquals(1 / 3.0, h.tridentShare(), 1e-9);
        assertEquals(22.5, h.drawTicks(), 1e-9);
        assertEquals(10.0, h.rangedRange(), 1e-9);
        assertTrue(h.rangedPerMin() > 0);
    }

    @Test
    void windChargesSplitIntoLaunchesAndShots() {
        HabitRecorder r = new HabitRecorder();
        ground(r, 0, 200, HELD_MACE);
        r.onProjectile(PROJ_WIND, 0.1, -1.4, 0.1, 20f, 0, 0, 3, 0); // at its own feet
        r.onProjectile(PROJ_WIND, 1.4, 0.1, 0.0, 20f, 0, 0, 3, 0);  // at us
        r.onProjectile(PROJ_WIND, 0.0, -1.3, 0.2, 20f, 0, 0, 3, 0);
        assertEquals(2, r.windLaunches());
        assertEquals(1, r.windShots());
    }

    @Test
    void maceDivesNeedTheMaceAndARealFall() {
        HabitRecorder r = new HabitRecorder();
        long c = ground(r, 0, 100, HELD_MACE);
        // a launch to 12 blocks up, swing on the way down at 4 blocks up
        double y = 64;
        for (int i = 0; i < 12; i++) {
            r.tick(c++, false, y += 1.0, HELD_MACE, false, false);
        }
        for (int i = 0; i < 8; i++) {
            r.tick(c++, false, y -= 1.0, HELD_MACE, false, i == 7);
        }
        r.tick(c++, true, 64, HELD_MACE, false, false); // landing does not count it twice
        assertEquals(1, r.dives());
        // a jump crit with the mace: too small a fall
        r.tick(c++, false, 65.2, HELD_MACE, false, false);
        r.tick(c++, true, 64, HELD_MACE, false, true);
        // a big fall with a sword: not a mace dive
        r.tick(c++, false, 74, HELD_OTHER, false, false);
        r.tick(c++, true, 64, HELD_OTHER, false, false);
        r.tick(c++, true, 64, HELD_OTHER, false, true); // a plain swing
        assertEquals(1, r.dives());
        OpponentMemory.HabitSummary h = r.summary();
        assertEquals(8.0, h.diveHeight(), 1e-9);
        assertTrue(Double.isNaN(h.divesPerMin()), "fight shorter than the rate minimum");
        assertEquals(1.0 / 3, r.maceRate(), 1e-9); // 3 melee swings, one of them a dive
    }

    @Test
    void shortFightsGiveNoRatesAndEmptyOnesNoShares() {
        HabitRecorder r = new HabitRecorder();
        ground(r, 0, 50, HELD_OTHER);
        OpponentMemory.HabitSummary h = r.summary();
        assertTrue(Double.isNaN(h.pearlsPerMin()));
        assertTrue(Double.isNaN(h.pearlEscapeShare()));
        assertTrue(Double.isNaN(r.rangedRate()));
        assertTrue(Double.isNaN(r.pearlOutHp()));
    }

    @Test
    void habitsPersistInV2AndV1FilesStillLoad() {
        OpponentMemory m = new OpponentMemory();
        OpponentMemory.HabitSummary h = new OpponentMemory.HabitSummary(2.0, 0.5, 1.0, 0.0, 4.0, NAN, NAN, NAN, NAN,
                NAN, NAN, 1.0, 0.0, 1.5, 9.0);
        m.record("srv", UUID, "Diver", new OpponentMemory.FightSummary(0, NAN, 6.0, NAN, 0.4, NAN, NAN, NAN, true),
                h, 7);
        JsonObject json = m.toJson();
        assertEquals(2, json.get("v").getAsInt());
        JsonArray a = json.getAsJsonObject("p").get(OpponentMemory.key("srv", UUID)).getAsJsonArray();
        assertEquals(12 + OpponentMemory.HABIT_FIELDS, a.size());
        OpponentMemory.Profile p = OpponentMemory.fromJson(JsonParser.parseString(json.toString()).getAsJsonObject())
                .lookup("srv", UUID);
        assertEquals(2.0, p.pearlsPerMin, 1e-9);
        assertEquals(1.0, p.pearlEscapeShare, 1e-9);
        assertEquals(9.0, p.diveHeight, 1e-9);
        assertTrue(Double.isNaN(p.drawTicks));

        // a v1 file (12 entries) loads with no habits, and the next fight's habits blend in whole
        String v1 = "{\"v\":1,\"p\":{\"" + OpponentMemory.key("srv", UUID)
                + "\":[\"Old\",4,1,3,0.1,null,null,null,null,null,null,0.6]}}";
        OpponentMemory old = OpponentMemory.fromJson(JsonParser.parseString(v1).getAsJsonObject());
        OpponentMemory.Profile q = old.lookup("srv", UUID);
        assertEquals(4, q.fights);
        assertTrue(Double.isNaN(q.pearlsPerMin));
        old.record("srv", UUID, "Old", new OpponentMemory.FightSummary(NAN, NAN, NAN, NAN, NAN, NAN, NAN, NAN, false),
                h, 8);
        assertEquals(2.0, q.pearlsPerMin, 1e-9);
        assertEquals(5, q.fights);
        // and an old reader's prefix (the first 12 entries) is unchanged
        JsonArray b = old.toJson().getAsJsonObject("p").get(OpponentMemory.key("srv", UUID)).getAsJsonArray();
        assertEquals("Old", b.get(0).getAsString());
        assertEquals(5, b.get(1).getAsInt());
    }

    @Test
    void hintSpeaksTheNewHabits() {
        OpponentMemory.Profile p = new OpponentMemory.Profile();
        p.name = "Rival";
        p.fights = 2;
        p.wins = 1;
        p.pearlOutHp = 6.0;
        p.pearlEngageShare = 0.6;
        p.pearlsPerMin = 3.0;
        p.divesPerMin = 2.0;
        p.diveHeight = 11.0;
        p.windLaunchesPerMin = 1.0;
        String h = OpponentMemory.hint(p);
        assertTrue(h.contains("pearls out near 6 HP"), h);
        assertTrue(h.contains("pearls in to engage"), h);
        assertTrue(h.contains("3 pearls/min"), h);
        assertTrue(h.contains("mace diver (~11-block dives)"), h);
        assertTrue(h.contains("wind-charge launches"), h);

        OpponentMemory.Profile b = new OpponentMemory.Profile();
        b.fights = 1;
        b.rangedPerMin = 4.0;
        b.bowShare = 0.2;
        b.crossbowShare = 0.8;
        b.rangedRange = 14.0;
        b.drawTicks = 25.0;
        b.windShotsPerMin = 1.0;
        String hb = OpponentMemory.hint(b);
        assertTrue(hb.contains("likes the crossbow at ~14 blocks (25-tick draws)"), hb);
        assertTrue(hb.contains("throws wind charges"), hb);
    }

    @Test
    void recalledHabitsDriveTheFightDecisions() {
        RecalledHabits r = new RecalledHabits();
        assertFalse(r.pearlLikely(1f));
        assertEquals(40.0, r.deflectHorizon(40.0));
        assertEquals(0.0, r.diveSpacing(HELD_MACE, false, 5));

        OpponentMemory.Profile p = new OpponentMemory.Profile();
        p.fights = 3;
        p.pearlOutHp = 6.0;
        p.pearlEscapeShare = 0.8;
        p.divesPerMin = 1.0;
        p.windShotsPerMin = 1.0;
        r.fill(p);
        assertTrue(r.pearlLikely(8f));
        assertFalse(r.pearlLikely(12f));
        assertEquals(60.0, r.deflectHorizon(40.0), 1e-9);
        assertEquals(RecalledHabits.DIVE_SPACING, r.diveSpacing(HELD_MACE, false, 4.0));
        assertEquals(0.0, r.diveSpacing(HELD_MACE, true, 4.0));   // on the ground: no dive coming
        assertEquals(0.0, r.diveSpacing(HELD_OTHER, false, 4.0)); // no mace in hand
        assertEquals(0.0, r.diveSpacing(HELD_MACE, false, 0.5));  // not above us

        p.pearlEscapeShare = 0.2; // pearls mostly to engage: no escape pre-warning
        r.fill(p);
        assertFalse(r.pearlLikely(6f));
        r.fill(null);
        assertFalse(r.known());
        RecalledHabits.NONE.fill(p); // the shared empty view never fills
        assertFalse(RecalledHabits.NONE.known());
    }

    @Test
    void perTickFeedAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HabitRecorder r = new HabitRecorder();
        long[] c = {0};
        long bytes = AllocationProbe.measure(20_000, () -> {
            long t = c[0]++;
            int phase = (int) (t % 40);
            boolean air = phase >= 20 && phase < 30;
            int held = (t & 64) == 0 ? HELD_MACE : HELD_BOW;
            r.tick(t, !air, 64 + (air ? 30 - phase : 0), held, held == HELD_BOW && phase < 15, phase == 29);
            if (phase == 10) {
                r.onProjectile(PROJ_ARROW, 0, 0.1, 2.5, 14f, 0, 0, 0, 12);
            } else if (phase == 20) {
                r.onProjectile(PROJ_PEARL, -1, 0.3, 0, 6f, 0, 0, 4, 0);
            } else if (phase == 21) {
                r.onProjectile(PROJ_WIND, 0, -1.4, 0, 20f, 0, 0, 3, 0);
            }
        });
        assertEquals(0, bytes, "habit feed allocated " + bytes + " bytes");
        RecalledHabits rec = new RecalledHabits();
        OpponentMemory.Profile p = new OpponentMemory.Profile();
        p.fights = 1;
        p.pearlOutHp = 6;
        p.divesPerMin = 2;
        rec.fill(p);
        long reads = AllocationProbe.measure(20_000, () -> {
            rec.pearlLikely(7f);
            rec.deflectHorizon(40);
            rec.diveSpacing(HELD_MACE, false, 3);
        });
        assertEquals(0, reads, "recalled reads allocated " + reads + " bytes");
    }
}
