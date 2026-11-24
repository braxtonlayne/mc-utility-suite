package com.utilitysuite.combat.coach;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The post-fight tally: hit attribution, crits, spacing, fight end and the summary line. */
class FightTallyTest {

    private static final int OPP = 7;
    private final FightTally tally = new FightTally();
    // the simulated tick state
    private float strength = 1f;
    private boolean falling;
    private int hurt;
    private float health = 20f;
    private boolean oppSwing;
    private double dist = 3.0;

    private boolean tick() {
        boolean ended = tally.observe(OPP, dist, strength, strength >= 0.95f && falling, hurt, health, oppSwing);
        oppSwing = false;
        if (hurt > 0) {
            hurt--;
        }
        if (strength < 1f) {
            strength = Math.min(1f, strength + 0.1f);
        }
        return ended;
    }

    /** Our swing leaves this tick and the opponent's hurt animation starts on the next one. */
    private void landHit(boolean crit) {
        falling = crit;
        strength = 1f;
        tick();
        strength = 0.05f; // recharge reset: the swing left
        falling = false;
        tick();
        hurt = 10;
        tick();
    }

    private void takeHit(float dmg) {
        oppSwing = true;
        tick();
        health -= dmg;
        tick();
    }

    private void idle(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }

    @Test
    void noFightWithoutHits() {
        idle(300);
        assertFalse(tally.active());
        for (int i = 0; i < 100; i++) {
            assertFalse(tally.absent());
        }
    }

    @Test
    void countsLandedTakenAndCrits() {
        landHit(true);
        assertTrue(tally.active(), "first hit starts the fight");
        idle(12);
        landHit(false);
        idle(12);
        takeHit(4f);
        idle(12);
        landHit(true);
        idle(12);
        assertEquals(3, tally.landed());
        assertEquals(2, tally.crits());
        assertEquals(1, tally.taken());
        assertEquals(3.0, tally.avgSpacing(), 1e-9);
    }

    @Test
    void hurtWithoutOurSwingIsNotOurHit() {
        landHit(false); // start the fight
        idle(20);
        hurt = 10; // someone else's arrow: no recharge drop of ours
        idle(20);
        assertEquals(1, tally.landed());
    }

    @Test
    void takenIsDebouncedAndNeedsTheOpponentNear() {
        landHit(false);
        idle(5);
        health -= 3f;
        tick();
        health -= 1f; // same hit, absorption settling the next tick
        tick();
        assertEquals(1, tally.taken());
        idle(20);
        dist = 20.0; // far away and no swing: fall damage, not its hit
        health -= 2f;
        tick();
        assertEquals(1, tally.taken());
    }

    @Test
    void endsWhenOpponentGoneAndFormatsOnce() {
        landHit(true);
        idle(12);
        takeHit(5f);
        dist = 2.0;
        idle(12);
        boolean ended = false;
        for (int i = 0; i < FightTally.END_ABSENT_TICKS && !ended; i++) {
            ended = tally.absent();
        }
        assertTrue(ended);
        assertTrue(tally.worthPosting());
        String line = tally.formatLine();
        assertTrue(line.startsWith("Fight: landed 1 / taken 1, crits 100%, avg spacing "), line);
        tally.clearFight();
        assertFalse(tally.active());
        assertEquals(0, tally.landed());
    }

    @Test
    void endsAfterQuietStretch() {
        landHit(false);
        boolean ended = false;
        for (int i = 0; i < FightTally.END_QUIET_TICKS + 5 && !ended; i++) {
            ended = tick();
        }
        assertTrue(ended);
        assertFalse(tally.worthPosting(), "one stray hit is not worth a notice");
    }

    @Test
    void switchingOpponentEndsTheFight() {
        landHit(false);
        idle(3);
        assertTrue(tally.observe(OPP + 1, 3.0, 1f, false, 0, health, false));
    }

    @Test
    void formatLine() {
        assertEquals("Fight: landed 7 / taken 4, crits 43%, avg spacing 2.9", FightTally.format(7, 4, 3, 2.94));
        assertEquals("Fight: landed 0 / taken 3, crits -, avg spacing 3.0", FightTally.format(0, 3, 0, 3.0));
    }

    @Test
    void observingAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        landHit(false);
        long bytes = AllocationProbe.measure(50_000, () -> {
            tally.observe(OPP, 3.0, 1f, false, 0, 20f, false);
            tally.absent();
        });
        assertTrue(bytes < 1_024, "tally allocated " + bytes + " bytes");
    }

    @Test
    void ownOpponentCooldownRead() {
        double period = FightCoach.swingPeriod(1.6); // sword: 12.5 ticks
        assertEquals(12.5, period, 1e-9);
        assertEquals(0f, FightCoach.chargeOf(0, period), 1e-6f);
        assertEquals(0.4f, FightCoach.chargeOf(5, period), 1e-6f);
        assertEquals(1f, FightCoach.chargeOf(20, period), 1e-6f);
        assertEquals(13, FightCoach.readyIn(0, period));
        assertEquals(8, FightCoach.readyIn(5, period));
        assertEquals(0, FightCoach.readyIn(13, period));
    }
}
