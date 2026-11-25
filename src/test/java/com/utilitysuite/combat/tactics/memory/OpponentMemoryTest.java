package com.utilitysuite.combat.tactics.memory;

import com.google.gson.JsonParser;
import com.utilitysuite.combat.tactics.SkillEstimator;
import com.utilitysuite.combat.tactics.memory.OpponentMemory.FightSummary;
import com.utilitysuite.combat.tactics.memory.OpponentMemory.Profile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpponentMemoryTest {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";
    private static final double NAN = Double.NaN;

    @Test
    void firstFightIsTakenWholeThenEmaBlends() {
        OpponentMemory m = new OpponentMemory();
        m.record("Play.Example.net", UUID, "Steve",
                new FightSummary(-0.6, 0.4, 6.0, 0.1, 0.0, 3.1, 11.0, 0.7, false), 100);
        Profile p = m.lookup("play.example.net", UUID);
        assertNotNull(p);
        assertEquals(-0.6, p.strafeBias, 1e-9);
        m.record("play.example.net", UUID, "Steve",
                new FightSummary(0.4, NAN, NAN, 0.1, 0.0, NAN, 11.0, 0.5, true), 200);
        assertEquals(-0.6 + 0.3 * (0.4 + 0.6), p.strafeBias, 1e-9);
        assertEquals(0.4, p.shieldUse, 1e-9, "an unmeasured value keeps the history");
        assertEquals(6.0, p.pearlOutHp, 1e-9);
        assertEquals(2, p.fights);
        assertEquals(1, p.wins);
        assertTrue(m.dirty());
    }

    @Test
    void profilesAreKeyedPerServer() {
        OpponentMemory m = new OpponentMemory();
        m.record("a.net", UUID, "X", new FightSummary(0, 0, NAN, 0, 0, NAN, NAN, 0.5, true), 1);
        assertNull(m.lookup("b.net", UUID));
    }

    @Test
    void hintNamesTheReadableHabits() {
        OpponentMemory m = new OpponentMemory();
        Profile p = m.record("", UUID, "Alex", new FightSummary(-0.5, 0.5, 7.0, 0.0, 0.3, 3.4, NAN, 0.8, false), 5);
        String h = OpponentMemory.hint(p);
        assertTrue(h.startsWith("Alex: 0-1"), h);
        assertTrue(h.contains("strafes left"), h);
        assertTrue(h.contains("shields a lot"), h);
        assertTrue(h.contains("pearls out near 7 HP"), h);
        assertTrue(h.contains("mace diver"), h);
        assertTrue(h.contains("long reach 3.4"), h);
        assertEquals("", OpponentMemory.hint(null));
    }

    @Test
    void priorSkillShrinksTowardNeutralAndSeedsEstimator() {
        Profile p = new Profile();
        p.skill = 0.9;
        p.fights = 2;
        double prior = OpponentMemory.priorSkill(p, 0.5);
        assertEquals(0.5 + 0.5 * 0.4, prior, 1e-9);
        assertEquals(0.5, OpponentMemory.priorSkill(null, 0.5));

        SkillEstimator est = new SkillEstimator();
        est.seedPrior(42, prior);
        assertEquals(prior, est.skill(42), 1e-9);
        est.forget(42);
        assertEquals(SkillEstimator.UNKNOWN_SKILL, est.skill(42), 1e-9);
    }

    @Test
    void jsonRoundTripIsCompact() {
        OpponentMemory m = new OpponentMemory();
        m.record("srv", UUID, "Steve", new FightSummary(0.123456, NAN, 5.0, 0.2, 0.0, 3.0, 10.0, 0.66, true), 9);
        String json = m.toJson().toString();
        assertTrue(json.length() < 140, json);
        OpponentMemory back = OpponentMemory.fromJson(JsonParser.parseString(json).getAsJsonObject());
        Profile p = back.lookup("srv", UUID);
        assertEquals("Steve", p.name);
        assertEquals(0.123, p.strafeBias, 1e-9);
        assertTrue(Double.isNaN(p.shieldUse));
        assertEquals(1, p.wins);
        assertEquals(9, p.lastSeen);
    }

    @Test
    void capDropsLeastRecentlySeen() {
        OpponentMemory m = new OpponentMemory();
        FightSummary f = new FightSummary(0, 0, NAN, 0, 0, NAN, NAN, 0.5, true);
        for (int i = 0; i <= OpponentMemory.MAX_PROFILES; i++) {
            m.record("s", "u" + i, "", f, i);
        }
        assertEquals(OpponentMemory.MAX_PROFILES, m.size());
        assertNull(m.lookup("s", "u0"));
        assertNotNull(m.lookup("s", "u1"));
    }
}
