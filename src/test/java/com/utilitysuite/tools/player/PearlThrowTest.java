package com.utilitysuite.tools.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlThrowTest {

    private static final int REACTION = 2;

    private final PearlPlan plan = new PearlPlan();
    private final PearlThrow seq = new PearlThrow(plan, () -> REACTION);

    /** Runs to the end with fixed facts; returns how many USE actions were asked for (each tap accepted). */
    private int runOut(int selected, boolean pearl) {
        int uses = 0;
        for (int i = 0; i < 200 && seq.phase() != PearlThrow.Phase.IDLE; i++) {
            PearlThrow.Action a = seq.tick(selected, pearl, 0);
            if (a == PearlThrow.Action.USE) {
                uses++;
                seq.useTapped(true, selected);
            } else if (a == PearlThrow.Action.RESTORE && seq.owesSwapBack()) {
                seq.swapBackQueued(true);
            }
        }
        assertEquals(PearlThrow.Phase.IDLE, seq.phase());
        return uses;
    }

    private void swapInAndWait() {
        assertTrue(plan.plan(4, i -> i == 27));
        seq.start();
        seq.tick(4, false, 1);
        seq.tick(4, true, 0);
    }

    private void swapInAndThrow() {
        swapInAndWait();
        seq.tick(4, true, 0);
        assertEquals(PearlThrow.Action.USE, seq.tick(4, true, 0));
        seq.useTapped(true, 4);
    }

    // ---- the reviewed bug: the selection moved off the pearl slot before the throw ----------------------------------

    @Test
    void selectionMovedBeforeThrowIsCancelledNotThrown() {
        assertTrue(plan.plan(0, i -> i == 6));
        seq.start();
        assertEquals(PearlThrow.Action.NONE, seq.tick(6, true, 0));
        // the selection is on slot 3 when the throw is due
        PearlThrow.Action a = seq.tick(3, true, 0);
        assertNotEquals(PearlThrow.Action.USE, a);
        assertEquals(PearlThrow.Action.RESTORE, a, "goes straight to the restore");
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome(), "never THROWN without a use tap");
        assertFalse(seq.reselect(), "a selection somebody else changed is left alone");
        assertFalse(seq.owesSwapBack());
        assertEquals(PearlThrow.Action.FINISH, seq.tick(3, true, 0));
        assertTrue(seq.takeNotice(), "the user is told");
        assertFalse(seq.takeNotice(), "once");
    }

    @Test
    void selectionMovedDuringSwapInSwapsTheStackBackWithoutThrowing() {
        assertTrue(plan.plan(4, i -> i == 27));
        seq.start();
        assertEquals(PearlThrow.Action.NONE, seq.tick(4, false, 1), "swap click still queued");
        assertEquals(PearlThrow.Action.NONE, seq.tick(5, true, 0), "pearl arrived; the selection moved meanwhile");
        assertTrue(seq.owesSwapBack());
        assertEquals(PearlThrow.Action.NONE, seq.tick(5, true, 0));
        PearlThrow.Action a = seq.tick(5, true, 0);
        assertEquals(PearlThrow.Action.RESTORE, a);
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome());
        assertTrue(seq.owesSwapBack(), "the swap back is asked for");
        seq.swapBackQueued(true);
        assertEquals(PearlThrow.Action.NONE, seq.tick(5, true, 1));
        assertEquals(PearlThrow.Action.FINISH, seq.tick(5, false, 0));
        assertFalse(seq.owesSwapBack());
        assertTrue(seq.takeNotice());
    }

    @Test
    void pearlGoneFromTheSlotCancelsAndUndoesOurOwnSelection() {
        assertTrue(plan.plan(0, i -> i == 6));
        seq.start();
        seq.tick(6, true, 0);
        assertEquals(PearlThrow.Action.RESTORE, seq.tick(6, false, 0));
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome());
        assertTrue(seq.reselect(), "the selection is still ours: put it back");
    }

    @Test
    void refusedUseTapIsCancelled() {
        assertTrue(plan.plan(3, i -> i == 3));
        seq.start();
        seq.tick(3, true, 0);
        assertEquals(PearlThrow.Action.USE, seq.tick(3, true, 0));
        assertEquals(PearlThrow.Action.RESTORE, seq.useTapped(false, 3));
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome());
    }

    // ---- the normal runs ---------------------------------------------------------------------------------------------

    @Test
    void hotbarPearlIsThrownOnceAndThePreviousSlotReselected() {
        assertTrue(plan.plan(0, i -> i == 6));
        seq.start();
        assertEquals(PearlThrow.Action.NONE, seq.tick(6, true, 0));
        assertEquals(PearlThrow.Action.USE, seq.tick(6, true, 0));
        assertEquals(PearlThrow.Outcome.NONE, seq.outcome(), "not thrown until the tap is accepted");
        seq.useTapped(true, 6);
        assertEquals(PearlThrow.Outcome.THROWN, seq.outcome());
        PearlThrow.Action a = PearlThrow.Action.NONE;
        for (int i = 0; i < PearlThrow.SETTLE_TICKS; i++) {
            a = seq.tick(6, false, 0);
        }
        assertEquals(PearlThrow.Action.RESTORE, a);
        assertTrue(seq.reselect());
        assertEquals(PearlThrow.Action.FINISH, seq.tick(6, false, 0));
        assertEquals(PearlThrow.Outcome.THROWN, seq.outcome());
        assertFalse(seq.takeNotice());
    }

    @Test
    void inventoryPearlIsSwappedThrownAndSwappedBack() {
        assertTrue(plan.plan(4, i -> i == 27));
        seq.start();
        seq.tick(4, false, 1);
        assertEquals(1, runOut(4, true));
        assertEquals(PearlThrow.Outcome.THROWN, seq.outcome());
        assertFalse(seq.owesSwapBack());
    }

    @Test
    void swapInThatNeverArrivesTimesOutWithNothingOwed() {
        assertTrue(plan.plan(4, i -> i == 27));
        seq.start();
        assertEquals(0, runOut(4, false));
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome());
        assertFalse(seq.owesSwapBack());
    }

    @Test
    void aThrowThatOutlivesTheTimeoutStaysAThrow() {
        swapInAndThrow();
        PearlThrow.Action a = PearlThrow.Action.NONE;
        for (int i = 0; i < PearlThrow.SETTLE_TICKS; i++) {
            a = seq.tick(4, true, 0);
        }
        assertEquals(PearlThrow.Action.RESTORE, a);
        seq.swapBackQueued(true);
        for (int i = 0; i < PearlThrow.TIMEOUT && a != PearlThrow.Action.FINISH; i++) {
            a = seq.tick(4, true, 1); // the click never runs
        }
        assertEquals(PearlThrow.Action.FINISH, a);
        assertEquals(PearlThrow.Outcome.THROWN, seq.outcome());
        assertTrue(seq.owesSwapBack(), "left for the idle ticks to pay");
    }

    // ---- lease lost mid-run (the user scrolled or clicked: manual input wins) ---------------------------------------

    @Test
    void leaseLostAfterSwapInOwesTheSwapBackAndPaysItLater() {
        swapInAndWait();
        seq.interrupted();
        assertEquals(PearlThrow.Phase.IDLE, seq.phase());
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome(), "no throw happened");
        assertTrue(seq.takeNotice());
        assertTrue(seq.owesSwapBack());
        assertTrue(seq.debtTick());
        assertTrue(seq.debtStillValid(true, false, false, false));
        assertFalse(seq.debtStillValid(false, false, true, false), "the user moved the pearl: nothing to undo");
        seq.resumeRestore(true);
        assertFalse(seq.reselect(), "the selection is the user's now");
        assertEquals(PearlThrow.Action.NONE, seq.tick(5, true, 1));
        assertEquals(PearlThrow.Action.FINISH, seq.tick(5, false, 0));
        assertFalse(seq.owesSwapBack());
        assertFalse(seq.takeNotice(), "told once, at the cancel");
    }

    @Test
    void leaseLostDuringSwapInIsOnlyOwedWhenThePearlReallyArrived() {
        assertTrue(plan.plan(4, i -> i == 27));
        seq.start();
        seq.tick(4, false, 1);
        seq.interrupted();
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome());
        assertTrue(seq.owesSwapBack(), "unknown here: the click may have run this tick");
        assertFalse(seq.debtStillValid(false, true, false, false), "click dropped with the lease: pearl never moved");
        assertTrue(seq.debtStillValid(true, false, false, false), "click ran: the pearl is in the hotbar slot");
    }

    @Test
    void hotbarOnlyRunOwesNothingWhenTheLeaseIsLost() {
        assertTrue(plan.plan(0, i -> i == 6));
        seq.start();
        seq.tick(6, true, 0);
        seq.interrupted();
        assertFalse(seq.owesSwapBack());
        assertEquals(PearlThrow.Outcome.CANCELLED, seq.outcome());
        assertTrue(seq.takeNotice());
    }

    @Test
    void leaseLostAfterTheThrowIsStillAThrowAndOwesTheSwapBack() {
        swapInAndThrow();
        seq.interrupted();
        assertEquals(PearlThrow.Outcome.THROWN, seq.outcome());
        assertFalse(seq.takeNotice());
        assertTrue(seq.owesSwapBack());
        assertTrue(seq.debtStillValid(true, false, false, false), "pearls left in the hotbar slot");
        assertTrue(seq.debtStillValid(false, false, true, false), "last pearl thrown; the slot's own stack is due back");
        assertFalse(seq.debtStillValid(false, false, true, true), "last pearl thrown from an empty hand: nothing to do");
    }

    @Test
    void leaseLostWithTheSwapBackQueuedIsCheckedAgainstTheInventory() {
        swapInAndThrow();
        PearlThrow.Action a = PearlThrow.Action.NONE;
        for (int i = 0; i < PearlThrow.SETTLE_TICKS; i++) {
            a = seq.tick(4, true, 0);
        }
        assertEquals(PearlThrow.Action.RESTORE, a);
        seq.swapBackQueued(true);
        seq.interrupted();
        assertTrue(seq.owesSwapBack());
        assertTrue(seq.debtStillValid(true, false, false, false), "the queued swap back was dropped with the lease");
        assertFalse(seq.debtStillValid(false, true, false, false), "it ran after all: pearls are back in the inventory");
        assertFalse(seq.debtStillValid(false, false, false, true), "it ran after all: the slot's own stack is back");
    }

    @Test
    void anOwedSwapBackIsGivenUpAfterTheTimeout() {
        swapInAndWait();
        seq.interrupted();
        for (int i = 0; i < PearlThrow.DEBT_TIMEOUT; i++) {
            assertTrue(seq.debtTick());
        }
        assertFalse(seq.debtTick());
        assertFalse(seq.owesSwapBack());
        assertTrue(seq.debtTick(), "nothing owed: nothing to give up");
    }
}
