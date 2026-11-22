package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.ArenaQuery;
import com.utilitysuite.combat.tactics.TacticUtility.Tactic;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaTacticsTest {

    /** Scripted arena: both searches answer the configured spot; flat floor at y=64 unless overridden. */
    static final class FakeArena implements ArenaQuery {
        boolean high = true;
        boolean ceil = true;
        double sx, sy, sz, gain;
        int stepX = Integer.MIN_VALUE, stepZ;

        public double coverage() { return 1; }
        public int floorY(int x, int z) { return x == stepX && z == stepZ ? 65 : 64; }
        public int headroom(int x, int z) { return 4; }
        public int floorKind(int x, int z) { return 0; }
        public boolean evalKnock(double tx, double ty, double tz) { return false; }
        public double knockX() { return 0; }
        public double knockZ() { return 0; }
        public double knockValue() { return 0; }
        public int knockKind() { return 0; }
        public boolean findHighGround(double px, double py, double pz, double tx, double ty, double tz, double m) { return high; }
        public boolean findLowCeiling(double px, double py, double pz, double tx, double ty, double tz, double m) { return ceil; }
        public double spotX() { return sx; }
        public double spotY() { return sy; }
        public double spotZ() { return sz; }
        public double spotGain() { return gain; }
    }

    /** Player at (0.5, 0.5), target 3 blocks east (+x): forward = +x, left = -z. */
    private static CombatSnapshot snap() {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0.5;
        s.py = 64;
        s.pz = 0.5;
        s.tx = 3.5;
        s.ty = 64;
        s.tz = 0.5;
        s.onGround = true;
        return s;
    }

    private static ArenaTactics ready(CombatSnapshot s, FakeArena a, double x, double z, double gain) {
        a.sx = x;
        a.sz = z;
        a.sy = 65;
        a.gain = gain;
        ArenaTactics t = new ArenaTactics();
        t.assess(s, a);
        return t;
    }

    @Test
    void ratesByGainAndCloseness() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics near = ready(s, a, 2.5, 0.5, 2);
        ArenaTactics far = ready(s, a, 8.5, 0.5, 2);
        ArenaTactics low = ready(s, a, 2.5, 0.5, 1);
        assertTrue(near.highGround() > far.highGround());
        assertTrue(near.highGround() > low.highGround());
        assertTrue(near.ceilingDeny() > 0);
        a.high = false;
        a.ceil = false;
        near.assess(s, a);
        assertEquals(0.0, near.highGround());
        assertEquals(0.0, near.ceilingDeny());
    }

    @Test
    void alreadyAboveTheTargetIsNotWorthMoving() {
        CombatSnapshot s = snap();
        s.py = 65.2;
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 2.5, 0.5, 2);
        assertEquals(0.0, t.highGround());
        assertFalse(t.plan(s, a, Tactic.HIGH_GROUND));
    }

    @Test
    void keysFollowTheSpotInAllFourQuadrants() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 4.5, -3.5, 2); // ahead and left
        assertTrue(t.plan(s, a, Tactic.HIGH_GROUND));
        assertEquals(SpacingController.FORWARD, t.forward());
        assertEquals(StrafePlanner.LEFT, t.side());
        t = ready(s, a, 4.5, 4.5, 2); // ahead and right
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertEquals(SpacingController.FORWARD, t.forward());
        assertEquals(StrafePlanner.RIGHT, t.side());
        t = ready(s, a, -3.5, -3.5, 2); // behind and left
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertEquals(SpacingController.BACK, t.forward());
        assertEquals(StrafePlanner.LEFT, t.side());
        t = ready(s, a, -3.5, 4.5, 2); // behind and right
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertEquals(SpacingController.BACK, t.forward());
        assertEquals(StrafePlanner.RIGHT, t.side());
        assertFalse(t.sprint(), "never sprint backwards");
    }

    @Test
    void deadZoneAndSprintRule() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 0.7, 2.5, 2); // forward component 0.2: inside the dead zone
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertEquals(SpacingController.HOLD, t.forward());
        assertEquals(StrafePlanner.RIGHT, t.side());
        t = ready(s, a, 6.5, 0.5, 2);
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertTrue(t.sprint());
        t = ready(s, a, 2.5, 0.5, 2);
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertFalse(t.sprint(), "short walk: no sprint");
    }

    @Test
    void hazardsVetoSteps() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 4.5, -3.5, 2);
        s.hazardFront = true;
        s.hazardLeft = true;
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertEquals(SpacingController.HOLD, t.forward());
        assertEquals(StrafePlanner.NONE, t.side());
        s.hazardFront = false;
        s.hazardLeft = false;
        t = ready(s, a, -3.5, 4.5, 2);
        s.hazardBack = true;
        s.hazardRight = true;
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertEquals(SpacingController.HOLD, t.forward());
        assertEquals(StrafePlanner.NONE, t.side());
    }

    @Test
    void holdsOnTheSpot() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 0.9, 0.5, 2);
        assertTrue(t.plan(s, a, Tactic.CEILING_DENY));
        assertTrue(t.holding());
        assertEquals(SpacingController.HOLD, t.forward());
        assertEquals(StrafePlanner.NONE, t.side());
        s.px = 1.5; // 0.6 from the spot: still within the leave radius
        t.plan(s, a, Tactic.CEILING_DENY);
        assertTrue(t.holding());
        s.px = 2.5; // knocked well off it
        t.plan(s, a, Tactic.CEILING_DENY);
        assertFalse(t.holding());
    }

    @Test
    void givesUpWithoutProgress() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 4.5, -3.5, 2);
        for (int i = 0; i < ArenaTactics.TIMEOUT_TICKS - 1; i++) {
            assertTrue(t.plan(s, a, Tactic.HIGH_GROUND), "tick " + i);
        }
        boolean any = true;
        for (int i = 0; i < 3; i++) {
            any = t.plan(s, a, Tactic.HIGH_GROUND);
        }
        assertFalse(any, "never moved: falls back");

        ArenaTactics u = ready(s, a, 4.5, -3.5, 2);
        for (int i = 0; i < 300; i++) {
            s.px = 0.5 + i * 0.01; // closing in steadily resets the clock
            s.pz = 0.5 - i * 0.01;
            assertTrue(u.plan(s, a, Tactic.HIGH_GROUND));
        }
    }

    @Test
    void otherTacticsAndMissingSpotsFallBack() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 4.5, -3.5, 2);
        assertFalse(t.plan(s, a, Tactic.FIGHT));
        a.high = false;
        t.assess(s, a);
        assertFalse(t.plan(s, a, Tactic.HIGH_GROUND));
        assertTrue(t.plan(s, a, Tactic.CEILING_DENY));
        t.reset();
        assertFalse(t.plan(s, a, Tactic.CEILING_DENY));
    }

    @Test
    void jumpsOnlyForAOneBlockStepUp() {
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 6.5, 0.5, 2);
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertFalse(t.jump());
        a.stepX = 1;
        a.stepZ = 0;
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertTrue(t.jump());
        s.onGround = false;
        t.plan(s, a, Tactic.HIGH_GROUND);
        assertFalse(t.jump());
    }

    @Test
    void planAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        CombatSnapshot s = snap();
        FakeArena a = new FakeArena();
        ArenaTactics t = ready(s, a, 4.5, -3.5, 2);
        long bytes = AllocationProbe.measure(50_000, () -> t.plan(s, a, Tactic.HIGH_GROUND));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
    }
}
