package com.utilitysuite.combat.reflex;

import com.utilitysuite.combat.reflex.ShieldDecision.Act;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShieldDecisionTest {

    private static final int NONE = Integer.MAX_VALUE;
    private static final double CONE = ShieldDecision.DEFAULT_FRONT_CONE_DEG;

    @Test
    void raisesInTimeForAFrontalThreat() {
        assertEquals(Act.RAISE, ShieldDecision.decide(true, false, 5, 6f, 10f, CONE, false, false));
        assertEquals(Act.NONE, ShieldDecision.decide(true, false, 20, 6f, 10f, CONE, false, false), "too early");
        assertEquals(Act.HOLD, ShieldDecision.decide(true, true, 3, 6f, 10f, CONE, false, false));
    }

    @Test
    void legitIgnoresThreatsOutsideTheFrontCone() {
        assertEquals(Act.NONE, ShieldDecision.decide(true, false, 3, 6f, 150f, CONE, false, false));
        assertEquals(Act.LOWER, ShieldDecision.decide(true, true, 3, 6f, -150f, CONE, false, false));
    }

    @Test
    void lessLegitTurnsToBlockWithTheTurnTimeInTheLead() {
        assertEquals(Act.TURN_AND_RAISE, ShieldDecision.decide(true, false, 10, 6f, 150f, CONE, true, false));
        assertTrue(ShieldDecision.turnTicks(180) >= 8, "180 degrees at 450 deg/s is 0.4 s");
    }

    @Test
    void lowersForASwingUnlessAHitIsImminent() {
        assertEquals(Act.LOWER, ShieldDecision.decide(true, true, 10, 6f, 0f, CONE, false, true));
        assertEquals(Act.LOWER, ShieldDecision.decide(true, true, NONE, 0f, Float.NaN, CONE, false, true));
        assertEquals(Act.HOLD, ShieldDecision.decide(true, true, 2, 6f, 0f, CONE, false, true));
    }

    @Test
    void lowersWhenTheThreatIsGoneOrNoShield() {
        assertEquals(Act.LOWER, ShieldDecision.decide(true, true, NONE, 0f, Float.NaN, CONE, false, false));
        assertEquals(Act.LOWER, ShieldDecision.decide(false, true, 3, 6f, 0f, CONE, false, false));
        assertEquals(Act.NONE, ShieldDecision.decide(false, false, 3, 6f, 0f, CONE, false, false));
    }

    @Test
    void ignoresHarmlessProjectiles() {
        assertEquals(Act.NONE, ShieldDecision.decide(true, false, 3, 0.5f, 0f, CONE, false, false));
    }
}
