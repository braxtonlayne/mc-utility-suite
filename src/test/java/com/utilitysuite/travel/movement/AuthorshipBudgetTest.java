package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorshipBudgetTest {

    @Test
    void capsCorrectedTicksPerJump() {
        AuthorshipBudget b = new AuthorshipBudget();
        assertEquals(AuthorshipBudget.DEFAULT_CAP, b.cap());
        b.onTakeoff(false);
        for (int i = 0; i < AuthorshipBudget.DEFAULT_CAP; i++) {
            assertTrue(b.tryUse(1.0), "tick " + i);
        }
        assertFalse(b.tryUse(1.0), "the seventh corrected tick is over the cap");
        b.onTouchdown(true);
        b.onTakeoff(false);
        assertTrue(b.tryUse(1.0), "the next jump has a fresh allowance");
        b.onTakeoff(true);
        assertEquals(1, b.usedThisJump(), "an assisted takeoff counts as the jump's first corrected tick");
    }

    @Test
    void helpScalesWithTheMissAndNothingWhenTheJumpLands() {
        AuthorshipBudget b = new AuthorshipBudget();
        assertEquals(0.0, b.help(0.0));
        assertEquals(0.0, b.help(-0.2));
        assertEquals(0.5, b.help(0.5), 1e-9);
        assertEquals(1.0, b.help(1.0), 1e-9);
        assertEquals(1.0, b.help(3.0), 1e-9, "a block or more: full help");
        b.onTakeoff(false);
        assertFalse(b.tryUse(0.0), "already lands: no correction");
        assertEquals(0, b.usedThisJump());
    }

    @Test
    void helpFadesAsTheUnassistedSuccessRateRises() {
        AuthorshipBudget b = new AuthorshipBudget();
        for (int i = 0; i < AuthorshipBudget.RING; i++) {
            b.onTakeoff(true); // assisted jumps: the player needed help
            b.onTouchdown(true);
        }
        assertEquals(0.0, b.unassistedRate());
        assertEquals(1.0, b.fade());
        for (int i = 0; i < AuthorshipBudget.RING; i++) {
            b.onTakeoff(false);
            b.onTouchdown(true); // clean, unassisted landings
        }
        assertEquals(1.0, b.unassistedRate());
        assertEquals(AuthorshipBudget.FLOOR, b.fade(), 1e-9);
        assertEquals(AuthorshipBudget.FLOOR, b.help(1.0), 1e-9);
        assertEquals((int) Math.ceil(AuthorshipBudget.DEFAULT_CAP * AuthorshipBudget.FLOOR), b.allowance(1.0));
        // nine out of ten: half way between 80% and 100%
        b.onTakeoff(false);
        b.onTouchdown(false);
        assertEquals(0.9, b.unassistedRate(), 1e-9);
        assertEquals(1.0 - 0.5 * (1.0 - AuthorshipBudget.FLOOR), b.fade(), 1e-9);
    }

    @Test
    void tooFewJumpsDoNotFade() {
        AuthorshipBudget b = new AuthorshipBudget();
        for (int i = 0; i < AuthorshipBudget.MIN_SAMPLES - 1; i++) {
            b.onTakeoff(false);
            b.onTouchdown(true);
        }
        assertEquals(1.0, b.fade());
    }

    @Test
    void zeroMeansNoCorrections() {
        AuthorshipBudget b = new AuthorshipBudget();
        b.setCap(0);
        b.onTakeoff(false);
        assertEquals(0.0, b.help(1.0));
        assertEquals(0, b.allowance(1.0));
        assertFalse(b.tryUse(5.0));
        MovementAssistBrain brain = new MovementAssistBrain();
        brain.budget().setCap(0);
        MovementPhysicsTest.Grid g = new MovementPhysicsTest.Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 19, 4, 3, 19, 30);
        brain.decide(new MovementPhysics().set(0.5, 20, 1.31, 0, 0, 0.153, true), 0f, true, 1, 0, false, 20f, g);
        assertFalse(brain.jump(), "budget 0: not even an edge jump");
    }

    @Test
    void theBrainStopsAirSteeringAtTheCap() {
        // falling toward a lethal pit with a ledge to the right: the same state over and over (no landing in between)
        MovementPhysicsTest.Grid g = new MovementPhysicsTest.Grid();
        g.fill(-40, -10, -40, 79, -10, 79);
        g.fill(-2, 15, 1, -1, 15, 3);
        MovementPhysics air = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.2, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.budget().setCap(3);
        int corrected = 0;
        for (int i = 0; i < 10; i++) {
            b.decide(air, 0f, false, 1, 0, false, 20f, g);
            corrected += b.corrected() ? 1 : 0;
        }
        assertEquals(3, corrected);
    }

    @Test
    void budgetCallsAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        AuthorshipBudget b = new AuthorshipBudget();
        long bytes = AllocationProbe.measure(1000, () -> {
            b.onTakeoff(false);
            b.tryUse(0.7);
            b.help(0.3);
            b.onTouchdown(true);
        });
        assertTrue(bytes < 256, "budget allocated " + bytes + " bytes");
    }
}
