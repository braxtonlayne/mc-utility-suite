package com.utilitysuite.combat.reflex;

import com.utilitysuite.combat.assistant.stages.ShieldThreatFeed;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One owner of the shield: the reflex stands down while a fight stage runs USE, and never turns during a fight. */
class ShieldOwnershipTest {

    static final int NONE = Integer.MAX_VALUE;

    @Test
    void theReflexStandsDownWhileAStageRunsTheShieldInAFight() {
        assertFalse(ShieldOwnership.reflexActs(true, true, true));
    }

    @Test
    void theReflexKeepsTheShieldWhenNoStageRunsIt() {
        assertTrue(ShieldOwnership.reflexActs(true, true, false), "fight without a shield stage (block setting off)");
        assertTrue(ShieldOwnership.reflexActs(true, false, false), "no fight");
        assertTrue(ShieldOwnership.reflexActs(true, false, true), "a stage setting alone does not disarm it");
    }

    @Test
    void anUnarmedReflexNeverActs() {
        assertFalse(ShieldOwnership.reflexActs(false, false, false));
        assertFalse(ShieldOwnership.reflexActs(false, true, false));
        assertFalse(ShieldOwnership.reflexActs(false, true, true));
    }

    @Test
    void theReflexNeverTurnsTheCameraDuringAFight() {
        assertFalse(ShieldOwnership.reflexMayTurn(true, true), "the assistant owns the camera while engaged");
        assertTrue(ShieldOwnership.reflexMayTurn(true, false));
        assertFalse(ShieldOwnership.reflexMayTurn(false, false));
        assertFalse(ShieldOwnership.reflexMayTurn(false, true));
    }

    @Test
    void theReflexReportsToTheStageExactlyWhenItStandsDownForIt() {
        assertTrue(ShieldOwnership.reportsToStage(true, true, true, false));
        for (int i = 0; i < 8; i++) {
            boolean armed = (i & 1) != 0;
            boolean fighting = (i & 2) != 0;
            boolean stage = (i & 4) != 0;
            boolean standsDown = armed && !ShieldOwnership.reflexActs(armed, fighting, stage);
            assertTrue(ShieldOwnership.reportsToStage(armed, fighting, stage, false) == standsDown,
                    "armed " + armed + " fighting " + fighting + " stage " + stage);
            assertFalse(ShieldOwnership.reportsToStage(armed, fighting, stage, true),
                    "the user's own right click: nothing is asked of the stage");
        }
    }

    @Test
    void anArrowIsReportedAsASingleImpact() {
        ShieldThreatFeed feed = new ShieldThreatFeed();
        ShieldOwnership.reportToStage(feed, 0f, 70.0, 6, 5f, 20f, NONE, 0f, Float.NaN, NONE, 0f, Float.NaN, -1, 10);
        assertTrue(feed.take());
        assertEquals(6, feed.ticksToImpact());
        assertEquals(20f, feed.relYawDeg(), 1.0e-4);
        assertFalse(feed.standing());
    }

    @Test
    void theDuelTargetsOwnMeleeIsLeftToTheStage() {
        ShieldThreatFeed feed = new ShieldThreatFeed();
        ShieldOwnership.reportToStage(feed, 0f, 70.0, NONE, 0f, Float.NaN, NONE, 0f, Float.NaN, 2, 6f, 0f, 10, 10);
        assertFalse(feed.take(), "the block-and-answer loop reads its cooldown and its axe");
        // behind it an arrow is still reported
        ShieldOwnership.reportToStage(feed, 0f, 70.0, 9, 5f, 0f, NONE, 0f, Float.NaN, 2, 6f, 0f, 10, 10);
        assertTrue(feed.take());
        assertEquals(9, feed.ticksToImpact());
    }

    @Test
    void aSecondAttackerIsReportedAsAStandingThreat() {
        ShieldThreatFeed feed = new ShieldThreatFeed();
        ShieldOwnership.reportToStage(feed, 90f, 60.0, 9, 5f, 90f, 12, 20f, 90f, 4, 4f, 120f, 11, 10);
        assertTrue(feed.take());
        assertEquals(4, feed.ticksToImpact());
        assertEquals(4f, feed.damage());
        assertEquals(30f, feed.relYawDeg(), 1.0e-4);
        assertEquals(60.0, feed.frontConeDeg());
        assertTrue(feed.standing());
    }

    @Test
    void aSnowballHidesNothingAndAnEmptyTickClearsTheReport() {
        ShieldThreatFeed feed = new ShieldThreatFeed();
        ShieldOwnership.reportToStage(feed, 0f, 70.0, 1, 0.5f, 0f, 8, 20f, 10f, NONE, 0f, Float.NaN, -1, 10);
        assertTrue(feed.take());
        assertEquals(8, feed.ticksToImpact(), "the blast behind the snowball");
        ShieldOwnership.reportToStage(feed, 0f, 70.0, NONE, 0f, Float.NaN, NONE, 0f, Float.NaN, NONE, 0f, Float.NaN,
                -1, 10);
        assertFalse(feed.take());
    }

    @Test
    void allocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        boolean[] sink = {false};
        ShieldThreatFeed feed = new ShieldThreatFeed();
        long bytes = AllocationProbe.measure(100_000, () -> {
            sink[0] ^= ShieldOwnership.reflexActs(true, true, sink[0]);
            sink[0] ^= ShieldOwnership.reflexMayTurn(true, sink[0]);
            sink[0] ^= ShieldOwnership.reportsToStage(true, true, true, sink[0]);
            ShieldOwnership.reportToStage(feed, 10f, 70.0, 6, 5f, 20f, 9, 20f, -30f, 4, 4f, 0f, 11, 10);
            sink[0] ^= feed.take();
        });
        assertTrue(bytes < 1_024, "ownership checks allocated " + bytes + " bytes");
    }

    @Test
    void theDodgeYieldsToTheShieldOnlyForAnArrowItCanBlock() {
        // armed, usable, from the front: shield handles it
        assertTrue(ShieldOwnership.shieldCoversArrow(true, true, false, 20f, 70.0));
        // from behind without turning: the dodge keeps it
        assertFalse(ShieldOwnership.shieldCoversArrow(true, true, false, 170f, 70.0));
        // from behind but the reflex may turn to it
        assertTrue(ShieldOwnership.shieldCoversArrow(true, true, true, 170f, 70.0));
        // no shield in hand, or the reflex is off: dodge
        assertFalse(ShieldOwnership.shieldCoversArrow(true, false, true, 0f, 70.0));
        assertFalse(ShieldOwnership.shieldCoversArrow(false, true, true, 0f, 70.0));
        assertFalse(ShieldOwnership.shieldCoversArrow(true, true, true, Float.NaN, 70.0));
    }
}
