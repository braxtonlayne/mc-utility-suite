package com.utilitysuite.combat;

import com.utilitysuite.combat.assistant.AerialPlanner;
import com.utilitysuite.combat.assistant.AimSolver;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.DamageClassifier;
import com.utilitysuite.combat.assistant.LineOfSight;
import com.utilitysuite.combat.assistant.ShieldBreakSequencer;
import com.utilitysuite.combat.assistant.SpacingController;
import com.utilitysuite.combat.assistant.StrafePlanner;
import com.utilitysuite.combat.assistant.SustainPlanner;
import com.utilitysuite.combat.assistant.SwingGate;
import com.utilitysuite.combat.coach.ThreatMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pure decision code shared by the Combat Assistant and the Fight Coach. */
class CombatDecisionTest {

    /** Player at the origin on the ground, target (player-sized) at +x distance d, everything ready. */
    static CombatSnapshot snap(double d) {
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
        s.tx = d;
        s.ty = 64;
        s.minX = d - 0.3;
        s.maxX = d + 0.3;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = -0.3;
        s.maxZ = 0.3;
        return s;
    }

    // ---- spacing ------------------------------------------------------------------------------------------------

    @Test
    void spacingKeepsBandWithHysteresis() {
        SpacingController c = new SpacingController();
        assertEquals(SpacingController.FORWARD, c.update(4.0, 2.7, 3.1, false, false));
        assertEquals(SpacingController.FORWARD, c.update(3.0, 2.7, 3.1, false, false), "until the band middle");
        assertEquals(SpacingController.HOLD, c.update(2.85, 2.7, 3.1, false, false));
        assertEquals(SpacingController.HOLD, c.update(3.05, 2.7, 3.1, false, false));
        assertEquals(SpacingController.BACK, c.update(2.5, 2.7, 3.1, false, false));
        assertEquals(SpacingController.BACK, c.update(2.8, 2.7, 3.1, false, false));
        assertEquals(SpacingController.HOLD, c.update(2.95, 2.7, 3.1, false, false));
    }

    @Test
    void spacingNeverStepsTowardAHazard() {
        SpacingController c = new SpacingController();
        assertEquals(SpacingController.HOLD, c.update(5.0, 2.7, 3.1, true, false), "cliff ahead");
        assertEquals(SpacingController.HOLD, c.update(1.0, 2.7, 3.1, false, true), "lava behind");
        assertEquals(-1, SpacingController.classify(2.0, 2.7, 3.1));
        assertEquals(0, SpacingController.classify(2.9, 2.7, 3.1));
        assertEquals(1, SpacingController.classify(3.5, 2.7, 3.1));
    }

    // ---- strafe -------------------------------------------------------------------------------------------------

    @Test
    void strafeInvertsAwayFromHazards() {
        StrafePlanner p = new StrafePlanner();
        int first = p.update(false, false, 30);
        assertNotEquals(StrafePlanner.NONE, first);
        // Hazard on the side we are moving toward: invert immediately.
        int toward = first;
        boolean hazLeft = toward == StrafePlanner.LEFT;
        int next = p.update(hazLeft, !hazLeft, 30);
        assertEquals(-toward, next);
        // Stays on the safe side while the hazard persists, even past the voluntary flip interval.
        for (int i = 0; i < 100; i++) {
            assertEquals(-toward, p.update(hazLeft, !hazLeft, 5));
        }
        assertEquals(StrafePlanner.NONE, p.update(true, true, 30), "hazards both sides: stop strafing");
    }

    @Test
    void strafeAlternatesAfterInterval() {
        StrafePlanner p = new StrafePlanner();
        int a = p.update(false, false, 3);
        assertEquals(a, p.update(false, false, 3));
        assertEquals(a, p.update(false, false, 3));
        assertEquals(-a, p.update(false, false, 3));
    }

    // ---- cooldown & crit gating ----------------------------------------------------------------------------------

    @Test
    void swingOnlyAtFullCharge() {
        CombatSnapshot s = snap(3.0);
        s.attackStrength = 0.9f;
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, false, false, 0, 12));
        s.attackStrength = 0.95f;
        assertEquals(SwingGate.Action.SWING, SwingGate.decide(s, false, false, 0, 12));
        s.crosshairOnTarget = false;
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, false, false, 0, 12));
        s.crosshairOnTarget = true;
        CombatSnapshot far = snap(3.6);
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(far, false, false, 0, 12), "out of reach");
    }

    @Test
    void critSyncHopsThenSwingsWhileFalling() {
        CombatSnapshot s = snap(3.0);
        s.attackStrength = 0.7f;
        assertEquals(SwingGate.Action.JUMP, SwingGate.decide(s, true, false, 0, 12), "pre-hop from 60% charge");
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, true, true, 0, 12), "one hop per swing");
        s.onGround = false;
        s.fallDistance = 0f;
        s.attackStrength = 1f;
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, true, true, 2, 12), "rising: wait for the fall");
        s.fallDistance = 0.3f;
        assertEquals(SwingGate.Action.SWING, SwingGate.decide(s, true, true, 3, 12), "falling: crit swing");
        s.inWater = true;
        assertEquals(SwingGate.Action.NONE, SwingGate.decide(s, true, true, 3, 12), "no crits in water");
        assertEquals(SwingGate.Action.SWING, SwingGate.decide(s, true, true, 12, 12), "falls back after the wait");
    }

    // ---- shield break --------------------------------------------------------------------------------------------

    @Test
    void shieldBreakSwapsToAxeSwingsAndSwapsBack() {
        ShieldBreakSequencer seq = new ShieldBreakSequencer();
        CombatSnapshot s = snap(3.0);
        s.targetBlocking = true;
        s.axeSlot = 2;
        s.selectedSlot = 0;
        assertTrue(ShieldBreakSequencer.opportunity(s));
        assertEquals(ShieldBreakSequencer.Action.SELECT_AXE, seq.step(s, true));
        assertTrue(seq.active());
        s.selectedSlot = 2;
        assertEquals(ShieldBreakSequencer.Action.SWING, seq.step(s, true));
        assertEquals(ShieldBreakSequencer.Action.SELECT_WEAPON, seq.step(s, true));
        assertFalse(seq.active());
    }

    @Test
    void shieldBreakAbortsWhenShieldLowered() {
        ShieldBreakSequencer seq = new ShieldBreakSequencer();
        CombatSnapshot s = snap(3.0);
        s.targetBlocking = true;
        s.axeSlot = 4;
        assertEquals(ShieldBreakSequencer.Action.SELECT_AXE, seq.step(s, true));
        s.targetBlocking = false;
        assertEquals(ShieldBreakSequencer.Action.SELECT_WEAPON, seq.step(s, true));
        assertFalse(seq.active());
    }

    @Test
    void noShieldBreakWithoutAxeOrWhenHoldingIt() {
        ShieldBreakSequencer seq = new ShieldBreakSequencer();
        CombatSnapshot s = snap(3.0);
        s.targetBlocking = true;
        s.axeSlot = -1;
        assertEquals(ShieldBreakSequencer.Action.NONE, seq.step(s, true));
        s.axeSlot = 0;
        s.selectedSlot = 0;
        assertEquals(ShieldBreakSequencer.Action.NONE, seq.step(s, true), "already holding the axe");
        s.axeSlot = 3;
        assertEquals(ShieldBreakSequencer.Action.NONE, seq.step(s, false), "disabled");
    }

    // ---- emergency sustain ---------------------------------------------------------------------------------------

    @Test
    void totemWhenLethalImminent() {
        CombatSnapshot s = snap(3.0);
        s.health = 3f;
        s.totemInvSlot = 14;
        assertEquals(SustainPlanner.Action.EQUIP_TOTEM, SustainPlanner.decide(s, 8f, 2f, true, true));
        s.offhandTotem = true;
        assertEquals(SustainPlanner.Action.RETREAT, SustainPlanner.decide(s, 8f, 2f, true, true),
                "totem already equipped, no apple: back off");
        // Large last hit makes 9 health lethal-imminent.
        s.offhandTotem = false;
        s.health = 9f;
        assertEquals(SustainPlanner.Action.EQUIP_TOTEM, SustainPlanner.decide(s, 8f, 10f, true, true));
        assertEquals(SustainPlanner.Action.NONE, SustainPlanner.decide(s, 8f, 10f, false, true), "totems off");
        assertEquals(40, SustainPlanner.menuSlotOf(4));
        assertEquals(14, SustainPlanner.menuSlotOf(14));
    }

    @Test
    void goldenAppleOrRetreatAtLowHealth() {
        CombatSnapshot s = snap(3.0);
        s.health = 7f;
        s.gappleSlot = 5;
        assertEquals(SustainPlanner.Action.EAT, SustainPlanner.decide(s, 8f, 2f, true, true));
        s.gappleOnCooldown = true;
        assertEquals(SustainPlanner.Action.RETREAT, SustainPlanner.decide(s, 8f, 2f, true, true));
        s.gappleOnCooldown = false;
        assertEquals(SustainPlanner.Action.RETREAT, SustainPlanner.decide(s, 8f, 2f, true, false), "apples off");
        s.health = 12f;
        assertEquals(SustainPlanner.Action.NONE, SustainPlanner.decide(s, 8f, 2f, true, true));
        s.health = 6f;
        s.absorption = 4f;
        assertEquals(SustainPlanner.Action.NONE, SustainPlanner.decide(s, 8f, 2f, true, true), "absorption counts");
    }

    // ---- aim, line of sight, aerial, threats, damage types --------------------------------------------------------

    @Test
    void aimPointsAtNearestBoxPointWithLead() {
        float[] out = new float[2];
        CombatSnapshot s = snap(3.0); // target along +x
        AimSolver.solve(s, 0, out);
        assertEquals(-90f, out[0], 0.5f);
        assertEquals(0f, out[1], 1f, "eye height is inside the box's vertical span");
        s.tvz = 0.5; // moving toward +z: lead shifts the yaw toward +z (less negative)
        AimSolver.solve(s, 2, out);
        assertTrue(out[0] > -90f + 5f, "lead yaw " + out[0]);
        CombatSnapshot above = snap(2.0);
        above.minY = 68;
        above.maxY = 69.8;
        AimSolver.solve(above, 0, out);
        assertTrue(out[1] < -30f, "looks up at a target above: " + out[1]);
        assertEquals(10f, AimSolver.yawDelta(5f, 355f), 1e-4);
    }

    @Test
    void lineOfSightBlockedByWall() {
        LineOfSight.SolidTest wallAtX2 = (x, y, z) -> x == 2;
        assertFalse(LineOfSight.clear(0.5, 65.6, 0.5, 4.5, 65.6, 0.5, wallAtX2, 64));
        assertTrue(LineOfSight.clear(0.5, 65.6, 0.5, 1.8, 65.6, 0.5, wallAtX2, 64));
        LineOfSight.SolidTest open = (x, y, z) -> false;
        assertTrue(LineOfSight.clear(0.5, 65.6, 0.5, -7.3, 61.2, 9.9, open, 64));
        assertFalse(LineOfSight.clear(0, 0, 0, 500, 0, 0, open, 64), "capped walk counts as no sight");
    }

    @Test
    void maceSwapOnlyWhenFallingFarEnough() {
        CombatSnapshot s = snap(3.0);
        s.maceSlot = 6;
        assertFalse(AerialPlanner.shouldSwapToMace(s, true), "on the ground");
        s.onGround = false;
        s.fallDistance = 1.0f;
        assertFalse(AerialPlanner.shouldSwapToMace(s, true));
        s.fallDistance = 2.0f;
        assertTrue(AerialPlanner.shouldSwapToMace(s, true));
        assertFalse(AerialPlanner.shouldSwapToMace(s, false), "disabled");
    }

    @Test
    void windChargeSequenceOrder() {
        AerialPlanner p = new AerialPlanner();
        CombatSnapshot s = snap(3.0);
        s.maceSlot = 6;
        s.windChargeSlot = 7;
        assertEquals(AerialPlanner.Action.AIM_DOWN, p.step(s, true));
        assertEquals(AerialPlanner.Action.AIM_DOWN, p.step(s, true), "still turning");
        s.pitch = 89f;
        assertEquals(AerialPlanner.Action.THROW, p.step(s, true));
        assertEquals(AerialPlanner.Action.RESTORE, p.step(s, true));
        assertFalse(p.active());
        s.pitch = 0f;
        assertEquals(AerialPlanner.Action.NONE, p.step(s, true), "cooldown before the next launch");
    }

    @Test
    void threatMathFlagsIncomingProjectiles() {
        // Arrow 10 blocks out along +x flying straight at the player.
        assertTrue(ThreatMath.incoming(10, 0, 0, -1.5, 0, 0));
        // Same arrow flying away / passing 3 blocks wide / resting.
        assertFalse(ThreatMath.incoming(10, 0, 0, 1.5, 0, 0));
        assertFalse(ThreatMath.incoming(10, 0, 3, -1.5, 0, 0));
        assertFalse(ThreatMath.incoming(1, 0, 0, 0, 0, 0));
        assertEquals(90f, ThreatMath.relativeYaw(-1, 0, 0f), 0.01f, "-x is to the right when facing +z");
    }

    @Test
    void damageTypesClassified() {
        assertEquals(DamageClassifier.Kind.ENVIRONMENTAL, DamageClassifier.kind("minecraft:fall"));
        assertEquals(DamageClassifier.Kind.ENVIRONMENTAL, DamageClassifier.kind("lava"));
        assertEquals(DamageClassifier.Kind.ENVIRONMENTAL, DamageClassifier.kind("minecraft:drown"));
        assertEquals(DamageClassifier.Kind.ENTITY_ATTACK, DamageClassifier.kind("minecraft:mob_attack"));
        assertEquals(DamageClassifier.Kind.ENTITY_ATTACK, DamageClassifier.kind("minecraft:arrow"));
        assertEquals(DamageClassifier.Kind.UNKNOWN, DamageClassifier.kind("othermod:mob_attack"));
        assertEquals(DamageClassifier.Kind.UNKNOWN, DamageClassifier.kind(null));
    }
}
