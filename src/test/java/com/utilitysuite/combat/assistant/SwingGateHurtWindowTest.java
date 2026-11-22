package com.utilitysuite.combat.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwingGateHurtWindowTest {

    private static CombatSnapshot snap() {
        CombatSnapshot s = new CombatSnapshot();
        s.py = 64;
        s.eyeY = 65.62;
        s.health = 20;
        s.maxHealth = 20;
        s.onGround = true;
        s.attackStrength = 1f;
        s.reach = 3.0;
        s.crosshairOnTarget = true;
        s.lineOfSight = true;
        s.tx = 1.0;
        s.ty = 64;
        s.minX = 0.7;
        s.maxX = 1.3;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = -0.3;
        s.maxZ = 0.3;
        return s;
    }

    @Test
    void unchangedWithoutHurtTime() {
        CombatSnapshot s = snap();
        s.targetHurtTime = 0;
        assertEquals(SwingGate.Action.SWING, SwingGate.decide(s, false, false, 0, 12));
        s.attackStrength = 0.5f;
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, false, false, 0, 12));
    }

    @Test
    void heldDuringImmunityAndReleasedAtTheThreshold() {
        CombatSnapshot s = snap();
        for (int h = 10; h > SwingGate.HURT_HOLD; h--) {
            s.targetHurtTime = h;
            assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, false, false, 0, 12), "hurtTime " + h);
        }
        for (int h = SwingGate.HURT_HOLD; h >= 0; h--) {
            s.targetHurtTime = h;
            assertEquals(SwingGate.Action.SWING, SwingGate.decide(s, false, false, 0, 12), "hurtTime " + h);
        }
    }

    @Test
    void holdIsBoundedByTheRemainingHurtTime() {
        CombatSnapshot s = snap();
        int held = 0;
        for (int h = 10; h >= 0; h--) {
            s.targetHurtTime = h;
            if (SwingGate.decide(s, false, false, 0, 12) == SwingGate.Action.SWING) {
                break;
            }
            held++;
        }
        assertEquals(10 - SwingGate.HURT_HOLD, held);
    }

    @Test
    void critSyncPathHoldsTheSwingButStillHops() {
        CombatSnapshot s = snap();
        s.targetHurtTime = 8;
        s.onGround = false;
        s.fallDistance = 0.5f;
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, true, true, 0, 12), "falling but target immune");
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, true, true, 12, 12), "wait timeout keeps the hold");
        s.targetHurtTime = SwingGate.HURT_HOLD;
        assertEquals(SwingGate.Action.SWING, SwingGate.decide(s, true, true, 0, 12));
        s.targetHurtTime = 0;
        assertEquals(SwingGate.Action.SWING, SwingGate.decide(s, true, true, 0, 12));
        CombatSnapshot g = snap();
        g.targetHurtTime = 9;
        g.attackStrength = 0.7f;
        assertEquals(SwingGate.Action.JUMP, SwingGate.decide(g, true, false, 0, 12));
    }
}
