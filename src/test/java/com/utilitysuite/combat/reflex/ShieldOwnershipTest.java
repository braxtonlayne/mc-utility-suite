package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One owner of the shield: the reflex stands down while a fight stage runs USE, and never turns during a fight. */
class ShieldOwnershipTest {

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
    void allocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        boolean[] sink = {false};
        long bytes = AllocationProbe.measure(100_000, () -> {
            sink[0] ^= ShieldOwnership.reflexActs(true, true, sink[0]);
            sink[0] ^= ShieldOwnership.reflexMayTurn(true, sink[0]);
        });
        assertTrue(bytes < 1_024, "ownership checks allocated " + bytes + " bytes");
    }
}
