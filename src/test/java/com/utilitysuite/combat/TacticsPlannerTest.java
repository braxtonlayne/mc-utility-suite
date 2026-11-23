package com.utilitysuite.combat;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.SpacingController;
import com.utilitysuite.combat.assistant.StrafePlanner;
import com.utilitysuite.combat.assistant.TacticsPlanner;
import com.utilitysuite.combat.assistant.TacticsPlanner.Tactic;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticsPlannerTest {

    /** Player at the origin, target {@code dx} blocks east (+x), facing it. */
    private static CombatSnapshot snap(double dx) {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0;
        s.py = 64;
        s.pz = 0;
        s.eyeY = 65.62;
        s.tx = dx;
        s.ty = 64;
        s.tz = 0;
        s.minX = dx - 0.3;
        s.maxX = dx + 0.3;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = -0.3;
        s.maxZ = 0.3;
        s.reach = 3.0;
        s.attackStrength = 1f;
        s.crosshairOnTarget = true;
        s.onGround = true;
        return s;
    }

    @Test
    void holdsAtTheNearEdgeInsteadOfChasingIntoTheGap() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = snap(7);
        s.gapNear = 3.0;
        s.gapFar = 6.0;
        assertEquals(Tactic.HOLD_GAP, t.plan(s, true, true));
        assertEquals(SpacingController.FORWARD, t.forward(), "walk up to the edge");
        assertFalse(t.sprint(), "never sprint toward a ravine");
        s.gapNear = 1.0;
        s.gapFar = 4.0;
        t.plan(s, true, true);
        assertEquals(SpacingController.HOLD, t.forward(), "stand-off reached: wait");
        assertEquals(StrafePlanner.NONE, t.side(), "no strafing along the lip");
        s.gapNear = 0.4;
        t.plan(s, true, true);
        assertEquals(SpacingController.BACK, t.forward(), "too close to the lip: step back");
    }

    @Test
    void swingsAtATargetMidJumpOverThePit() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = snap(2.6);
        s.gapNear = 1.0;
        s.gapFar = 2.6;
        s.targetOnGround = false;
        s.targetDropBelow = 12;
        t.plan(s, true, true);
        assertTrue(t.swingNow());
        s.targetDropBelow = 1; // a normal hop on flat ground
        t.plan(s, true, true);
        assertFalse(t.swingNow());
        s.targetDropBelow = 12;
        s.attackStrength = 0.3f; // not charged: wait a tick or two
        t.plan(s, true, true);
        assertFalse(t.swingNow());
    }

    @Test
    void gapBeyondTheTargetIsNotAnAmbush() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = snap(3);
        s.gapNear = 4;
        s.gapFar = 6;
        assertEquals(Tactic.NONE, t.plan(s, true, false));
        assertEquals(Tactic.NONE, t.plan(snap(3), false, false));
    }

    @Test
    void edgeKnockCirclesToTheFarSideThenSprintsIn() {
        TacticsPlanner t = new TacticsPlanner();
        // target 3 blocks east, drop to its north (-z): the player must end up south (+z) of it
        CombatSnapshot s = snap(3);
        s.knockDepth = 10;
        s.knockX = 0;
        s.knockZ = -1;
        assertEquals(Tactic.EDGE_KNOCK, t.plan(s, true, true));
        // player is west of the target (angle 180); wanted south (angle 90): error -90 -> RIGHT (clockwise)
        assertEquals(StrafePlanner.RIGHT, t.side());
        // now the player stands south of the target, facing north toward it: lined up
        s.px = 3;
        s.pz = 3;
        s.tz = 0;
        t.plan(s, true, true);
        assertEquals(StrafePlanner.NONE, t.side());
        assertEquals(SpacingController.FORWARD, t.forward());
        assertTrue(t.sprint(), "sprint hit for extra knockback");
    }

    @Test
    void edgeKnockNeverStrafesIntoAHazard() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = snap(3);
        s.knockDepth = 10;
        s.knockZ = -1;
        s.hazardRight = true;
        t.plan(s, true, true);
        assertEquals(StrafePlanner.NONE, t.side());
    }

    /** Lava pool east of the target (field knock direction +x): the player must end up west of it. */
    private static CombatSnapshot lava(double dx) {
        CombatSnapshot s = snap(dx);
        s.knockValue = 0.8;
        s.knockFieldX = 1;
        s.knockFieldZ = 0;
        s.knockKind = com.utilitysuite.combat.tactics.ArenaField.KNOCK_LAVA;
        return s;
    }

    @Test
    void knockVectorSprintsInWhenAlignedAndCirclesOtherwise() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = lava(3); // player already west of the target: aligned
        assertEquals(Tactic.KNOCK_VECTOR, t.plan(s, true, true));
        assertEquals(StrafePlanner.NONE, t.side());
        assertEquals(SpacingController.FORWARD, t.forward());
        assertTrue(t.sprint());
        // player north of the target (z -3): wanted west. Atan2 angle -90 -> 180: error -90 -> RIGHT? check sign
        s.px = 3;
        s.pz = -3;
        t.plan(s, true, true);
        // have = atan2(-3, 0) = -90, want = atan2(0, -1) = 180 -> d = 270 -> -90 -> RIGHT (clockwise)
        assertEquals(StrafePlanner.RIGHT, t.side());
        assertFalse(t.sprint());
        // player south of the target: mirrored
        s.pz = 3;
        t.plan(s, true, true);
        assertEquals(StrafePlanner.LEFT, t.side());
    }

    @Test
    void knockVectorNeedsValueAndSwitch() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = lava(3);
        s.knockValue = 0.2;
        assertEquals(Tactic.NONE, t.plan(s, true, true));
        s.knockValue = 0.8;
        assertEquals(Tactic.NONE, t.plan(s, true, false));
    }

    @Test
    void knockVectorAgainstAWallPinsWithoutSprinting() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = lava(5);
        s.knockKind = com.utilitysuite.combat.tactics.ArenaField.KNOCK_WALL;
        assertEquals(Tactic.KNOCK_VECTOR, t.plan(s, true, true));
        assertFalse(t.sprint());
        assertEquals(SpacingController.FORWARD, t.forward(), "closes to normal spacing");
        s = lava(2.5);
        s.knockKind = com.utilitysuite.combat.tactics.ArenaField.KNOCK_WALL;
        t.plan(s, true, true);
        assertEquals(SpacingController.HOLD, t.forward());
        assertFalse(t.sprint());
    }

    @Test
    void edgeGuardCirclesAwayFromADropBehindUsAndNeverBacksUp() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = snap(2);
        s.selfKnockDepth = 8; // a ravine behind us
        s.selfKnockX = -1;
        s.selfKnockZ = 0;
        s.knockDepth = 10; // the target is near an edge too: our own safety comes first
        s.knockX = 0;
        s.knockZ = -1;
        assertEquals(Tactic.EDGE_GUARD, t.plan(s, true, true));
        assertNotEquals(SpacingController.BACK, t.forward());
        assertNotEquals(StrafePlanner.NONE, t.side());
        s.selfKnockDist = 5.0; // further than a ground hit carries: no circling yet
        assertEquals(Tactic.EDGE_KNOCK, t.plan(s, true, true));
        s.selfKnockDist = 2.0;
        s.selfKnockDepth = 2; // a step down is no danger
        assertEquals(Tactic.EDGE_KNOCK, t.plan(s, true, true));
    }

    @Test
    void noKnockTacticsFromBelowTheTarget() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = snap(3);
        s.knockDepth = 10;
        s.knockX = 0;
        s.knockZ = -1;
        s.ty = s.py + 8.0; // it paces the rim, we stand on the ravine floor
        assertEquals(Tactic.NONE, t.plan(s, true, true));
        s.ty = s.py + 1.0; // a step up is still a knock
        assertEquals(Tactic.EDGE_KNOCK, t.plan(s, true, true));
    }

    @Test
    void edgeKnockKeepsPriorityOverKnockVector() {
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = lava(3);
        s.knockDepth = 10;
        s.knockX = 0;
        s.knockZ = -1;
        assertEquals(Tactic.EDGE_KNOCK, t.plan(s, true, true));
        assertEquals(StrafePlanner.RIGHT, t.side(), "uses knockX/knockZ, not the field vector");
    }

    @Test
    void knockVectorPlanningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = lava(3);
        long bytes = AllocationProbe.measure(100_000, () -> t.plan(s, true, true));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
    }

    @Test
    void planningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TacticsPlanner t = new TacticsPlanner();
        CombatSnapshot s = snap(3);
        s.knockDepth = 10;
        s.knockZ = -1;
        long bytes = AllocationProbe.measure(100_000, () -> t.plan(s, true, true));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
    }
}
