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
