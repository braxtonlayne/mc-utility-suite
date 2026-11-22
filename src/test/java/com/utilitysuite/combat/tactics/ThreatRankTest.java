package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.utilitysuite.combat.tactics.ThreatRank.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreatRankTest {

    static final int ZOMBIE = 10;
    static final int CREEPER = 20;
    static final int SKELETON = 30;
    static final int OTHER = 40;

    /** A zombie at half health hitting range, the usual current target. */
    static double halfZombie() {
        return score(HOSTILE | LOS | MELEE_IN_RANGE, 2.5, 10f, false, true);
    }

    int round(ThreatRank r, long clock, double current, int id, double score, boolean emergency) {
        r.begin(ZOMBIE, clock);
        r.offer(ZOMBIE, current, false);
        r.offer(id, score, emergency);
        return r.decide(clock);
    }

    @Test
    void urgentThreatsOutrankAZombieAtHalfHealth() {
        double zombie = halfZombie() + CURRENT_BONUS;
        assertTrue(score(HOSTILE | LOS | SWELLING, 3.0, 20f, false, true) > zombie, "swelling creeper");
        assertTrue(score(HOSTILE | LOS | DRAWING | RANGED, 6.0, 20f, false, true) > zombie, "bow drawn close");
        assertTrue(score(HOSTILE | LOS | MELEE_IN_RANGE | BEHIND, 2.0, 20f, false, true) > zombie,
                "about to hit from behind");
        assertTrue(score(HOSTILE | LOS | MELEE_IN_RANGE, 2.0, 20f, false, true) < zombie,
                "a second zombie in front does not");
        assertTrue(score(HOSTILE | LOS | DRAWING, 6.0, 20f, false, true)
                > score(HOSTILE | LOS | DRAWING, 18.0, 20f, false, true), "closer draws rank higher");
    }

    @Test
    void aSwellingCreeperSwitchesAtOnceOthersAfterTheDwell() {
        ThreatRank r = new ThreatRank();
        double zombie = halfZombie();
        assertEquals(-1, round(r, 0, zombie, OTHER, zombie - 10, false));
        // just engaged: the dwell holds a non-emergency switch
        assertEquals(-1, round(r, 1, zombie, SKELETON, score(HOSTILE | LOS | DRAWING, 6.0, 20f, false, true), false));
        // an emergency does not wait
        assertEquals(CREEPER, round(r, 2, zombie, CREEPER, score(HOSTILE | LOS | SWELLING, 3.0, 20f, false, true),
                emergency(SWELLING, 3.0)));
        assertEquals(1, r.switches());

        ThreatRank later = new ThreatRank();
        round(later, 0, zombie, OTHER, 0, false);
        assertEquals(SKELETON, round(later, MIN_DWELL_TICKS, zombie, SKELETON,
                score(HOSTILE | LOS | DRAWING, 6.0, 20f, false, true), false), "after the dwell");
    }

    @Test
    void noisyScoresDoNotFlapOver100Ticks() {
        ThreatRank r = new ThreatRank();
        Random rnd = new Random(7);
        int current = ZOMBIE;
        for (long t = 0; t < 100; t += 5) {
            r.begin(current, t);
            double base = 150.0;
            r.offer(ZOMBIE, base + (rnd.nextDouble() - 0.5) * 60.0, false);
            r.offer(OTHER, base + (rnd.nextDouble() - 0.5) * 60.0, false);
            int next = r.decide(t);
            if (next >= 0) {
                current = next;
            }
        }
        assertEquals(0, r.switches(), "noise of +-30 never beats the 80-point bonus");
    }

    @Test
    void reactiveRuleNeverTargetAndSight() {
        ThreatRank r = new ThreatRank();
        double zombie = halfZombie();
        assertEquals(Double.NEGATIVE_INFINITY, score(LOS | SWELLING | MELEE_IN_RANGE, 1.0, 1f, false, true),
                "not hostile to us: never scores");
        assertEquals(Double.NEGATIVE_INFINITY, score(HOSTILE | LOS | SWELLING, 1.0, 1f, true, true), "never-target");
        assertEquals(Double.NEGATIVE_INFINITY, score(HOSTILE | SWELLING, 1.0, 1f, false, true), "no sight");
        assertTrue(score(HOSTILE | SWELLING, 1.0, 1f, false, false) > zombie, "sight not required: relaxed tuning");
        for (long t = 0; t < 100; t += 5) {
            r.begin(ZOMBIE, t);
            r.offer(ZOMBIE, zombie, false);
            r.offer(OTHER, score(LOS | SWELLING | MELEE_IN_RANGE | BEHIND, 1.0, 1f, false, true), true);
            r.offer(CREEPER, score(HOSTILE | LOS | SWELLING, 1.0, 1f, true, true), true);
            assertEquals(-1, r.decide(t));
        }
    }

    @Test
    void anUnlistedCurrentTargetKeepsItsBonus() {
        ThreatRank r = new ThreatRank();
        r.begin(ZOMBIE, 0);
        r.currentScore(halfZombie());
        r.offer(OTHER, halfZombie() + 10, false);
        assertEquals(-1, r.decide(100), "the bonus applies to a target the table does not list");
    }
}
