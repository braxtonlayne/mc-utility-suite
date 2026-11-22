package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillEstimatorTest {

    /** A mob: one swing per cycle in reach, lands every other one, never combos or dodges, reacts in 12 ticks. */
    static long mob(SkillEstimator e, int id, long t, int rounds) {
        for (int r = 0; r < rounds; r++) {
            e.noteOpponentSwing(id, t++, true);
            if ((r & 1) == 0) {
                e.noteOpponentHit(id, t++, 3f);
            }
            e.noteOwnHit(id, t++);
            e.noteOwnHit(id, t++);
            e.noteReaction(id, 12);
        }
        return t;
    }

    /**
     * A player: {@code hitsOf10} of 10 in-reach swings land, in combos of {@code combo}, reacts in {@code react}
     * ticks, dodges a third of our swings.
     */
    static long player(SkillEstimator e, int id, long t, int rounds, int hitsOf10, int combo, int react) {
        int hitsLeft = 0;
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 10; k++) {
                e.noteOpponentSwing(id, t++, true);
                if (k < hitsOf10) {
                    e.noteOpponentHit(id, t++, 4f);
                    if (++hitsLeft >= combo) {
                        hitsLeft = 0;
                        e.noteOwnMiss(id, t++);
                        e.noteOwnHit(id, t++);
                        e.noteOwnHit(id, t++);
                    }
                }
            }
            e.noteReaction(id, react);
        }
        return t;
    }

    @Test
    void mobComesOutLow() {
        SkillEstimator e = new SkillEstimator();
        mob(e, 1, 0, 200);
        double s = e.skill(1);
        assertTrue(s >= 0.18 && s <= 0.32, "mob skill " + s);
        assertEquals(1, e.bestCombo(1));
    }

    @Test
    void goodPlayerComesOutHigh() {
        SkillEstimator e = new SkillEstimator();
        player(e, 1, 0, 30, 7, 3, 5);
        double s = e.skill(1);
        assertTrue(s >= 0.78 && s <= 0.92, "player skill " + s);
        assertEquals(3, e.bestCombo(1));
        double ask = SkillEstimator.oneNotchAbove(s, SkillEstimator.NOTCH, 0.2, 0.95);
        assertTrue(ask > s && ask <= 0.95);
    }

    @Test
    void unknownUntilEightSamples() {
        SkillEstimator e = new SkillEstimator();
        assertEquals(SkillEstimator.UNKNOWN_SKILL, e.skill(5), 0.0);
        for (int i = 0; i < 7; i++) {
            e.noteOwnHit(5, i);
            assertEquals(SkillEstimator.UNKNOWN_SKILL, e.skill(5), 0.0);
        }
        assertEquals(7, e.samples(5));
        e.noteOwnHit(5, 7);
        assertEquals(8, e.samples(5));
        assertTrue(e.skill(5) < SkillEstimator.UNKNOWN_SKILL, "it never hits or dodges: below unknown");
    }

    @Test
    void monotonicInAccuracy() {
        double prev = -1;
        for (int hits = 2; hits <= 9; hits++) {
            SkillEstimator e = new SkillEstimator();
            player(e, 1, 0, 40, hits, 1, 8);
            double s = e.skill(1);
            assertTrue(s > prev, hits + "/10 -> " + s + " after " + prev);
            prev = s;
        }
    }

    @Test
    void monotonicInComboLength() {
        double prev = -1;
        for (int combo = 1; combo <= 4; combo++) {
            SkillEstimator e = new SkillEstimator();
            player(e, 1, 0, 40, 8, combo, 8);
            double s = e.skill(1);
            assertTrue(s > prev, "combo " + combo + " -> " + s + " after " + prev);
            prev = s;
            assertEquals(combo, e.bestCombo(1));
        }
    }

    @Test
    void reportedSkillStepsAtMostTwoHundredthsPerEvent() {
        // a strong player for 200 events, then it plays like a mob: the dial moves in bounded steps both ways
        SkillEstimator f = new SkillEstimator();
        double last = f.skill(2);
        for (int i = 0; i < 400; i++) {
            switch (i % 5) {
                case 0 -> f.noteOpponentSwing(2, i, true);
                case 1 -> f.noteOpponentHit(2, i, 5f);
                case 2 -> f.noteOpponentHit(2, i, 5f);
                case 3 -> f.noteReaction(2, i < 200 ? 3 : 20);
                default -> f.noteOwnMiss(2, i);
            }
            double s = f.skill(2);
            assertTrue(Math.abs(s - last) <= SkillEstimator.MAX_STEP + 1e-12, "step " + (s - last) + " at " + i);
            last = s;
        }
        assertTrue(last > 0 && last < 1);
    }

    @Test
    void forgetAndSlotReuse() {
        SkillEstimator e = new SkillEstimator();
        mob(e, 100, 0, 20);
        assertTrue(e.samples(100) > 8);
        e.forget(100);
        assertEquals(0, e.samples(100));
        assertEquals(SkillEstimator.UNKNOWN_SKILL, e.skill(100), 0.0);

        mob(e, 100, 0, 5);
        for (int id = 0; id < SkillEstimator.MAX_OPPONENTS; id++) {
            e.noteOwnHit(id, 0);
        }
        assertEquals(0, e.samples(100), "least recently touched slot reused");
        assertEquals(1, e.samples(0));
        e.clear();
        assertEquals(0, e.samples(0));
    }
}
