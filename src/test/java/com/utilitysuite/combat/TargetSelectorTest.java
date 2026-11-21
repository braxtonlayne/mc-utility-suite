package com.utilitysuite.combat;

import com.utilitysuite.combat.assistant.TargetCandidates;
import com.utilitysuite.combat.assistant.TargetSelector;
import com.utilitysuite.combat.assistant.TargetSelector.Priority;
import com.utilitysuite.combat.assistant.TargetSelector.Targets;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.combat.assistant.TargetCandidates.AGGRESSIVE;
import static com.utilitysuite.combat.assistant.TargetCandidates.HOSTILE;
import static com.utilitysuite.combat.assistant.TargetCandidates.LOS;
import static com.utilitysuite.combat.assistant.TargetCandidates.PLAYER;
import static com.utilitysuite.combat.assistant.TargetCandidates.RANGED;
import static com.utilitysuite.combat.assistant.TargetCandidates.SWELLING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetSelectorTest {

    private static final TargetSelector.AllyTest NO_ALLIES = id -> false;

    private static int pick(TargetCandidates c, Targets t, Priority p) {
        return TargetSelector.pick(c, t, p, 8.0, true, -1, NO_ALLIES);
    }

    @Test
    void closestPicksTheNearestVisibleHostile() {
        TargetCandidates c = new TargetCandidates();
        c.add(10, 25, 20, HOSTILE | LOS);
        c.add(11, 9, 20, HOSTILE | LOS);
        c.add(12, 4, 20, HOSTILE); // closer but behind a wall
        assertEquals(11, pick(c, Targets.HOSTILE, Priority.CLOSEST));
    }

    @Test
    void lineOfSightOffAllowsTargetsBehindWalls() {
        TargetCandidates c = new TargetCandidates();
        c.add(11, 9, 20, HOSTILE | LOS);
        c.add(12, 4, 20, HOSTILE);
        assertEquals(12, TargetSelector.pick(c, Targets.HOSTILE, Priority.CLOSEST, 8.0, false, -1, NO_ALLIES));
    }

    @Test
    void outOfRangeAndPassiveEntitiesAreIgnored() {
        TargetCandidates c = new TargetCandidates();
        c.add(10, 81, 20, HOSTILE | LOS); // 9 blocks
        c.add(11, 4, 10, LOS); // a cow
        assertEquals(-1, pick(c, Targets.HOSTILE, Priority.CLOSEST));
    }

    @Test
    void playersOnlyWhenTheFilterAsksAndNeverAllies() {
        TargetCandidates c = new TargetCandidates();
        c.add(20, 4, 20, PLAYER | LOS);
        c.add(21, 9, 20, PLAYER | LOS);
        c.add(10, 16, 20, HOSTILE | LOS);
        assertEquals(10, pick(c, Targets.HOSTILE, Priority.CLOSEST));
        assertEquals(20, pick(c, Targets.HOSTILE_AND_PLAYERS, Priority.CLOSEST));
        assertEquals(21, TargetSelector.pick(c, Targets.PLAYERS, Priority.CLOSEST, 8.0, true, -1, id -> id == 20));
    }

    @Test
    void lowestHealthBeatsDistance() {
        TargetCandidates c = new TargetCandidates();
        c.add(10, 4, 20, HOSTILE | LOS);
        c.add(11, 36, 3, HOSTILE | LOS);
        assertEquals(11, pick(c, Targets.HOSTILE, Priority.LOWEST_HEALTH));
    }

    @Test
    void threatRanksAttackerThenCreeperThenAggressiveThenRanged() {
        TargetCandidates c = new TargetCandidates();
        c.add(10, 4, 20, HOSTILE | LOS); // idle zombie, closest
        c.add(11, 36, 20, HOSTILE | LOS | RANGED); // skeleton
        assertEquals(11, pick(c, Targets.HOSTILE, Priority.THREAT));
        c.add(12, 49, 20, HOSTILE | LOS | AGGRESSIVE);
        assertEquals(12, pick(c, Targets.HOSTILE, Priority.THREAT));
        c.add(13, 60, 20, HOSTILE | LOS | SWELLING);
        assertEquals(13, pick(c, Targets.HOSTILE, Priority.THREAT));
        assertEquals(10, TargetSelector.pick(c, Targets.HOSTILE, Priority.THREAT, 8.0, true, 10, NO_ALLIES),
                "whoever just hit you comes first");
    }

    @Test
    void pickingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TargetCandidates c = new TargetCandidates();
        for (int i = 0; i < TargetCandidates.CAPACITY; i++) {
            c.add(100 + i, i, 20 - i % 7, HOSTILE | LOS | (i % 3 == 0 ? AGGRESSIVE : 0));
        }
        long bytes = AllocationProbe.measure(100_000, () -> pick(c, Targets.HOSTILE, Priority.THREAT));
        assertTrue(bytes < 1_024, "pick allocated " + bytes + " bytes");
    }
}
