package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.ArenaField;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetreatChaseTest {

    /** We at the origin, the target {@code dist} blocks along +X. */
    static CombatSnapshot snap(double dist) {
        CombatSnapshot s = new CombatSnapshot();
        s.py = 64;
        s.onGround = true;
        s.headroom = 4;
        s.tx = dist;
        s.ty = 64;
        return s;
    }

    @Test
    void sprintsStraightAndSprintJumpsWhenItIsAFewBlocksAhead() {
        RetreatChase c = new RetreatChase();
        assertTrue(c.plan(snap(5.0)));
        assertEquals(SpacingController.FORWARD, c.forward());
        assertEquals(StrafePlanner.NONE, c.side());
        assertTrue(c.jump(), "sprint-jumping outruns its sprint");
        c.plan(snap(3.4));
        assertFalse(c.jump(), "close behind it: no hop");
        CombatSnapshot low = snap(5.0);
        low.headroom = 2;
        c.plan(low);
        assertFalse(c.jump(), "no room to jump");
    }

    @Test
    void driftsToLineAWallUpBehindIt() {
        RetreatChase c = new RetreatChase();
        CombatSnapshot s = snap(4.0);
        s.knockValue = 0.8;
        s.knockKind = ArenaField.KNOCK_WALL;
        s.knockFieldX = 0.0; // the wall is north of it (-z), we chase along +x: 90 degrees off
        s.knockFieldZ = -1.0;
        c.plan(s);
        assertTrue(c.side() != StrafePlanner.NONE, "drift toward the side that puts the wall behind it");
        assertFalse(c.jump(), "no hop while drifting");
        s.knockFieldX = 1.0; // the wall is straight beyond it
        s.knockFieldZ = 0.0;
        c.plan(s);
        assertEquals(StrafePlanner.NONE, c.side(), "already lined up");
    }

    @Test
    void planAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        RetreatChase c = new RetreatChase();
        CombatSnapshot s = snap(4.0);
        s.knockKind = ArenaField.KNOCK_WALL;
        long[] tick = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            s.tx = 2.0 + (t % 7);
            s.knockValue = (t & 1) == 0 ? 0.5 : 0.0;
            s.knockFieldZ = (t & 2) == 0 ? 1.0 : -1.0;
            c.plan(s);
        });
        assertTrue(bytes < 1_024, "RetreatChase allocated " + bytes + " bytes");
    }
}
