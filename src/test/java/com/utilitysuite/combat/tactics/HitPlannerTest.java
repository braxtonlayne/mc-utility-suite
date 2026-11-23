package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.travel.movement.MovementPhysics;
import com.utilitysuite.travel.movement.PhysicsBodySim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One behaviour test per {@link HitPlanner} rule, plus body-sim runs of a planned crit and a sprint hit. */
class HitPlannerTest {

    static final int FLOOR_Y = 64;
    static final MovementPhysics.Voxels FLAT = (x, y, z) -> y < FLOOR_Y;

    /** Player at the origin on the ground, a 0.6-wide target {@code dist} blocks east, sword charged. */
    static CombatSnapshot snap(double dist) {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0.0;
        s.py = FLOOR_Y;
        s.pz = 0.0;
        s.eyeY = FLOOR_Y + 1.62;
        s.onGround = true;
        s.attackStrength = 1f;
        s.crosshairOnTarget = true;
        s.lineOfSight = true;
        s.headroom = 4;
        s.targetHealth = 20f;
        target(s, dist);
        return s;
    }

    static void target(CombatSnapshot s, double dist) {
        s.tx = s.px + dist;
        s.ty = FLOOR_Y;
        s.tz = s.pz;
        s.minX = s.tx - 0.3;
        s.maxX = s.tx + 0.3;
        s.minY = FLOOR_Y;
        s.maxY = FLOOR_Y + 1.95;
        s.minZ = s.tz - 0.3;
        s.maxZ = s.tz + 0.3;
    }

    /** A planner past its opener (one swing sent). */
    static HitPlanner afterOpener() {
        HitPlanner p = new HitPlanner();
        p.onSwing();
        return p;
    }

    /** Attack strength that is {@code ticks} from READY with a sword (12.5-tick period). */
    static float readyIn(int ticks, double speed) {
        return (float) (0.95 - (ticks - 0.5) * speed / 20.0);
    }

    // ---- opener, re-engage, combo -------------------------------------------------------------------------------

    @Test
    void openerIsASprintKnockbackHitThatWaitsBrieflyForSprint() {
        HitPlanner p = new HitPlanner();
        CombatSnapshot s = snap(2.5);
        p.sprintPending = true;
        p.decide(s);
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
        assertTrue(p.holdSprint());
        assertFalse(p.swing(), "forward and sprint held, sprint not on yet: wait for it");
        s.sprinting = true;
        p.decide(s);
        assertTrue(p.swing(), "sprinting and charged: the knockback hit");
        assertFalse(p.jump());
    }

    @Test
    void sprintKnockbackSwingsWithoutSprintWhenNoneIsComing() {
        HitPlanner p = new HitPlanner();
        p.decide(snap(2.5));
        assertTrue(p.swing(), "no sprint on its way: a hit now beats a late one");
        HitPlanner q = new HitPlanner();
        q.sprintPending = true;
        CombatSnapshot s = snap(2.5);
        for (int i = 0; i < HitPlanner.MAX_KB_WAIT; i++) {
            q.decide(s);
            assertFalse(q.swing());
        }
        q.decide(s);
        assertTrue(q.swing(), "sprint never came: plain swing after the wait");
    }

    @Test
    void reEngageAfterAGapIsASprintKnockbackHit() {
        HitPlanner p = afterOpener();
        CombatSnapshot near = snap(2.5);
        p.decide(near);
        assertEquals(HitPlanner.Kind.CRIT, p.kind(), "mid-fight against a big mob: crit");
        CombatSnapshot far = snap(7.0);
        far.attackStrength = 0.3f;
        for (int i = 0; i < HitPlanner.REENGAGE_TICKS; i++) {
            p.decide(far);
        }
        p.decide(near);
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind(), "back from a gap: knockback again");
        p.onSwing();
        p.decide(near);
        assertEquals(HitPlanner.Kind.CRIT, p.kind(), "the re-engage is one hit");
    }

    @Test
    void liveComboPlanKeepsSprintKnockback() {
        HitPlanner p = afterOpener();
        p.comboLive = true;
        p.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
        assertFalse(p.jump(), "no hop inside a combo");
    }

    // ---- crit ---------------------------------------------------------------------------------------------------

    @Test
    void critAgainstAHighHealthMobHopsAboutSixTicksBeforeReady() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        s.attackStrength = readyIn(9, 1.6);
        p.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, p.kind());
        assertFalse(p.jump(), "nine ticks out: too early to hop");
        s.attackStrength = readyIn(6, 1.6);
        p.decide(s);
        assertTrue(p.jump(), "six ticks out: hop so the first falling tick meets the charge");
        p.decide(s);
        assertFalse(p.jump(), "one hop per hit");
    }

    @Test
    void hopTimingFollowsTheWeaponNotAFixedChargeShare() {
        // an axe (0.8 attacks per second, 25-tick period): 60% charge is still ten ticks out
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        s.ownAttackSpeed = 0.8;
        s.attackStrength = 0.6f;
        p.decide(s);
        assertFalse(p.jump(), "the old 60% pre-jump would land long before the axe is charged");
        s.attackStrength = readyIn(6, 0.8);
        p.decide(s);
        assertTrue(p.jump());
        assertEquals(6, HitPlanner.hopLead(4));
        assertEquals(5, HitPlanner.hopLead(3), "a lower ceiling ends the jump sooner");
    }

    @Test
    void critSwingsOnTheFirstFallingTickWithSprintOff() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        p.decide(s);
        assertTrue(p.jump());
        s.onGround = false;
        s.sprinting = true;
        s.pvy = 0.25; // rising
        p.decide(s);
        assertFalse(p.swing());
        assertFalse(p.releaseForward(), "keep forward on the way up");
        s.pvy = 0.0; // apex
        p.decide(s);
        assertTrue(p.releaseForward(), "release forward at the apex: sprint drops before the swing tick");
        assertTrue(p.dropSprint());
        s.pvy = -0.08;
        s.fallDistance = 0.08f;
        p.decide(s);
        assertFalse(p.swing(), "falling but still sprinting: a sprint hit is never a crit");
        s.sprinting = false;
        p.decide(s);
        assertTrue(p.swing(), "falling, sprint off, charged: crit");
    }

    @Test
    void critFallsBackToAPlainSwingWhenNoFallComes() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        int swingTick = -1;
        for (int i = 0; i < 20 && swingTick < 0; i++) {
            p.decide(s); // this body never leaves the ground
            if (p.swing()) {
                swingTick = i;
            }
        }
        assertEquals(HitPlanner.MAX_CRIT_WAIT, swingTick);
    }

    @Test
    void walledTargetIsCritted() {
        HitPlanner p = afterOpener();
        p.targetIsPlayer = true;
        CombatSnapshot s = snap(2.5);
        s.knockValue = 0.6;
        s.knockKind = ArenaField.KNOCK_WALL;
        p.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, p.kind());
    }

    @Test
    void eatingTargetIsCritted() {
        HitPlanner p = afterOpener();
        p.targetIsPlayer = true;
        CombatSnapshot s = snap(2.5);
        s.targetUsingItem = true;
        p.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, p.kind());
    }

    @Test
    void outDamagedPrefersCrits() {
        HitPlanner p = afterOpener();
        p.targetIsPlayer = true;
        p.gearRatio = 2.0;
        p.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.CRIT, p.kind());
    }

    @Test
    void heavilyOutGearedHopsForTheCritEvenWhileTheirHitIsCharged() {
        // arena finding 2026-11-22 (stone sword into full diamond): ground hits dealt less than the opponent
        // regenerated, and the "no hop while their hit is charged" rule kept every hit on the ground
        HitPlanner p = afterOpener();
        p.targetIsPlayer = true;
        p.oppCharged = true;
        p.gearRatio = 3.0;
        p.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.CRIT, p.kind());
        assertTrue(p.jump(), "the hop starts although their hit is charged");

        HitPlanner mild = afterOpener();
        mild.targetIsPlayer = true;
        mild.oppCharged = true;
        mild.gearRatio = 2.0;
        mild.decide(snap(2.5));
        assertFalse(mild.jump(), "below the crit-or-nothing ratio the charged-hit rule still holds");
        assertEquals(HitPlanner.Kind.SPRINT_KB, mild.kind());
    }

    @Test
    void heavilyOutGearedCritsThroughAComboPlanAndAReEngageButNotTheOpener() {
        HitPlanner opener = new HitPlanner();
        opener.targetIsPlayer = true;
        opener.gearRatio = 3.0;
        opener.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.SPRINT_KB, opener.kind(), "the opener stays a sprint-knockback hit");

        HitPlanner combo = afterOpener();
        combo.targetIsPlayer = true;
        combo.comboLive = true;
        combo.gearRatio = 3.0;
        combo.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.CRIT, combo.kind(), "a knockback hit into that armour is not worth the combo");

        HitPlanner re = afterOpener();
        re.targetIsPlayer = true;
        re.gearRatio = 3.0;
        CombatSnapshot far = snap(7.0);
        far.attackStrength = 0.2f;
        for (int i = 0; i < HitPlanner.REENGAGE_TICKS + 2; i++) {
            re.decide(far);
        }
        re.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.CRIT, re.kind(), "a re-engage is a crit too");

        HitPlanner mob = afterOpener();
        mob.targetIsPlayer = false;
        mob.comboLive = true;
        mob.gearRatio = 3.0;
        mob.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.SPRINT_KB, mob.kind(), "players only");
    }

    @Test
    void critTimingOffNeverPicksCrit() {
        HitPlanner p = afterOpener();
        p.critTiming = false;
        p.decide(snap(2.5));
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
        assertFalse(p.jump());
    }

    // ---- no hop -------------------------------------------------------------------------------------------------

    @Test
    void noHopWithADropOrLavaBehind() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        s.hazardBack = true;
        p.decide(s);
        assertFalse(p.jump());
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
    }

    @Test
    void noHopWithARavineAFewBlocksBehind() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        s.selfKnockDepth = 10; // a hit taken mid-air carries 3-5 blocks
        s.selfKnockDist = 5.0;
        p.decide(s);
        assertFalse(p.jump());
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
        HitPlanner q = afterOpener();
        s.selfKnockDepth = 2; // a step down is no danger
        q.decide(s);
        assertTrue(q.jump(), "a step down behind does not stop the crit hop");
    }

    @Test
    void noHopUnderALowCeiling() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        s.headroom = 2;
        p.decide(s);
        assertFalse(p.jump());
        assertTrue(p.swing(), "swings on the ground instead of waiting for a hop");
    }

    @Test
    void noHopAtTheLipOfAGap() {
        HitPlanner p = afterOpener();
        p.holdGap = true;
        p.decide(snap(2.5));
        assertFalse(p.jump());
    }

    @Test
    void noHopWhileTheOpponentsChargedHitIsInReach() {
        HitPlanner p = afterOpener();
        p.targetIsPlayer = true;
        p.oppCharged = true;
        CombatSnapshot s = snap(2.5);
        p.decide(s);
        assertFalse(p.jump());
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
        p.onSwing(); // that hit went out on the ground
        p.oppCharged = false; // their swing is spent: the next hit may be a crit again
        s.attackStrength = 0.2f;
        p.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, p.kind());
    }

    @Test
    void aCritPlanDroppedOnTheGroundWhenAHopBecomesALiability() {
        HitPlanner p = afterOpener();
        p.targetIsPlayer = true;
        CombatSnapshot s = snap(2.5);
        s.attackStrength = readyIn(7, 1.6);
        p.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, p.kind(), "latched");
        p.oppCharged = true;
        p.decide(s);
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind(), "still on the ground: re-planned to a ground hit");
    }

    // ---- sweep --------------------------------------------------------------------------------------------------

    @Test
    void sweepWhenTwoHostilesStandInTheBox() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        s.sweepWeapon = true;
        s.sweepHostiles = 1; // the target plus one more
        s.pvx = 0.25; // walking in
        s.attackStrength = readyIn(2, 1.6);
        p.decide(s);
        assertEquals(HitPlanner.Kind.SWEEP, p.kind());
        assertTrue(p.releaseForward(), "slow down: a sweep needs a slow ground swing");
        assertTrue(p.dropSprint());
        s.attackStrength = 1f;
        p.decide(s);
        assertFalse(p.swing(), "still too fast to sweep");
        s.pvx = 0.05;
        p.decide(s);
        assertTrue(p.swing());
    }

    @Test
    void neverSweepWithAPetVillagerOrNeutralMobInTheBox() {
        HitPlanner p = afterOpener();
        CombatSnapshot s = snap(2.5);
        s.sweepWeapon = true;
        s.sweepHostiles = 3;
        s.sweepProtected = 1;
        p.decide(s);
        assertNotEquals(HitPlanner.Kind.SWEEP, p.kind());

        // a forced plain hit standing still with a sword would sweep: withheld, sprint asked for instead
        HitPlanner q = afterOpener();
        q.policy = HitPlanner.Policy.PLAIN;
        for (int i = 0; i < 20; i++) {
            q.decide(s);
            assertFalse(q.swing(), "a sweep would hit the protected entity");
            assertTrue(q.holdSprint());
        }
        s.sprinting = true;
        q.decide(s);
        assertTrue(q.swing(), "a sprint hit never sweeps");
        s.sprinting = false;
        s.pvx = 0.2;
        q.decide(s);
        assertTrue(q.swing(), "moving at walking speed never sweeps either");
    }

    // ---- arena and policy overrides -----------------------------------------------------------------------------

    @Test
    void arenaReadTowardLavaOrADropIsAlwaysKnockback() {
        for (int kind : new int[]{ArenaField.KNOCK_LAVA, ArenaField.KNOCK_DROP}) {
            HitPlanner p = afterOpener();
            p.gearRatio = 3.0; // would want crits otherwise
            CombatSnapshot s = snap(2.5);
            s.knockValue = 0.8;
            s.knockKind = kind;
            s.sweepWeapon = true;
            s.sweepHostiles = 2;
            p.decide(s);
            assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
        }
    }

    @Test
    void fixedPoliciesOverrideTheChoice() {
        CombatSnapshot s = snap(2.5);
        HitPlanner kb = afterOpener();
        kb.policy = HitPlanner.Policy.KNOCKBACK;
        kb.decide(s);
        assertEquals(HitPlanner.Kind.SPRINT_KB, kb.kind());
        HitPlanner crit = new HitPlanner(); // even the opener
        crit.policy = HitPlanner.Policy.CRITS;
        crit.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, crit.kind());
        HitPlanner plain = afterOpener();
        plain.policy = HitPlanner.Policy.PLAIN;
        plain.decide(s);
        assertEquals(HitPlanner.Kind.PLAIN, plain.kind());
        assertTrue(plain.swing());
    }

    @Test
    void hurtWindowHoldsEveryKind() {
        HitPlanner p = new HitPlanner();
        CombatSnapshot s = snap(2.5);
        s.targetHurtTime = 6;
        p.decide(s);
        assertFalse(p.swing());
        s.targetHurtTime = 2;
        p.decide(s);
        assertTrue(p.swing());
    }

    // ---- body sim -----------------------------------------------------------------------------------------------

    /**
     * Client sprint as {@code LocalPlayer.aiStep} runs it: the sprint key with forward starts it (in the air too), no
     * forward stops it. Ground start by double tap is not used by the assistant.
     */
    static boolean sprintAfter(boolean sprinting, boolean forward, boolean sprintKey) {
        if (!sprinting && forward && sprintKey) {
            return true;
        }
        return sprinting && forward;
    }

    static void fill(CombatSnapshot s, BodySim b, boolean sprinting, int ticksSinceSwing, double targetAhead) {
        s.px = b.x();
        s.py = b.y();
        s.pz = b.z();
        s.eyeY = b.y() + 1.62;
        s.pvx = b.vx();
        s.pvy = b.vy();
        s.pvz = b.vz();
        s.onGround = b.onGround();
        s.fallDistance = (float) b.fallDistance();
        s.sprinting = sprinting;
        s.attackStrength = (float) Math.min(1.0, (ticksSinceSwing + 0.5) / 12.5);
        target(s, targetAhead);
    }

    @Test
    void bodySimPlannedCritHasSprintOffAndAFallOnTheSwingTick() {
        BodySim body = new PhysicsBodySim(FLAT).set(0.5, FLOOR_Y, 0.5, 0.0, 0.0, 0.0, true);
        HitPlanner p = afterOpener(); // the big mob after the opener: crits
        CombatSnapshot s = snap(2.5);
        boolean sprinting = true; // running in with forward and sprint held
        int sinceSwing = 0;
        int crits = 0;
        for (int tick = 0; tick < 120; tick++) {
            fill(s, body, sprinting, sinceSwing, 2.6);
            p.decide(s);
            if (p.swing()) {
                // the attack packet is handled before this tick's movement: the server sees the state sampled above
                assertEquals(HitPlanner.Kind.CRIT, p.kind());
                assertFalse(s.sprinting, "sprinting on the crit swing tick (tick " + tick + ")");
                assertTrue(s.fallDistance > 0f, "no fall on the crit swing tick (tick " + tick + ")");
                assertFalse(s.onGround);
                p.onSwing();
                sinceSwing = 0;
                crits++;
            } else {
                sinceSwing++;
            }
            boolean forward = !p.releaseForward();
            boolean sprintKey = !p.dropSprint();
            sprinting = sprintAfter(sprinting, forward, sprintKey);
            body.tick(forward ? 1 : 0, 0, -90f, sprinting, p.jump());
            // a real fight keeps the target in front; the sim just keeps it 2.6 blocks ahead
        }
        assertTrue(crits >= 6, "a crit about every sword cooldown, got " + crits);
    }

    @Test
    void bodySimSprintKnockbackHitIsOnTheGroundAndSprinting() {
        BodySim body = new PhysicsBodySim(FLAT).set(0.5, FLOOR_Y, 0.5, 0.0, 0.0, 0.0, true);
        HitPlanner p = new HitPlanner(); // opener
        CombatSnapshot s = snap(6.0);
        boolean sprinting = false;
        boolean forward = true;
        boolean sprintKey = true;
        double targetX = 6.5;
        int swingTick = -1;
        for (int tick = 0; tick < 60 && swingTick < 0; tick++) {
            fill(s, body, sprinting, 40, targetX - body.x());
            p.sprintPending = forward && sprintKey && !sprinting;
            p.decide(s);
            if (p.swing()) {
                swingTick = tick;
                assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind());
                assertTrue(s.sprinting, "sprint knockback needs sprint on the swing tick");
                assertTrue(s.onGround, "the opener is a ground hit");
                break;
            }
            assertFalse(p.jump());
            sprintKey = p.holdSprint() || !p.dropSprint();
            forward = !p.releaseForward();
            sprinting = sprintAfter(sprinting, forward, sprintKey);
            body.tick(forward ? 1 : 0, 0, -90f, sprinting, false);
        }
        assertTrue(swingTick > 0, "walked in and hit");
    }
}
