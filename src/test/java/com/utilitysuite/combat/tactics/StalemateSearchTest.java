package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StalemateSearchTest {

    final StalemateSearch search = new StalemateSearch();

    int run(CombatVoxels w) {
        int st = StalemateSearch.RUNNING;
        for (int t = 0; t < 200 && st == StalemateSearch.RUNNING; t++) {
            st = search.step(w, 192);
        }
        return st;
    }

    static int x(long p) {
        return StalemateSearch.unpackX(p);
    }

    static int y(long p) {
        return StalemateSearch.unpackY(p);
    }

    static int z(long p) {
        return StalemateSearch.unpackZ(p);
    }

    /** Every step is one block sideways and at most {@code maxDrop} down / one up. */
    void assertWalkable(int maxDrop) {
        long[] r = search.route();
        for (int i = 1; i < search.routeLength(); i++) {
            int dx = Math.abs(x(r[i]) - x(r[i - 1]));
            int dz = Math.abs(z(r[i]) - z(r[i - 1]));
            int dy = y(r[i]) - y(r[i - 1]);
            assertEquals(1, dx + dz, "one block per step at " + i);
            assertTrue(dy <= 1 && dy >= -maxDrop, "step " + i + " dy " + dy);
        }
    }

    @Test
    void packingMatchesTheBlockPosLayout() {
        long p = StalemateSearch.pack(-1234, 70, 5678);
        assertEquals(-1234, StalemateSearch.unpackX(p));
        assertEquals(70, StalemateSearch.unpackY(p));
        assertEquals(5678, StalemateSearch.unpackZ(p));
        assertEquals(com.utilitysuite.travel.Packed.pack(-1234, -3, 5678), StalemateSearch.pack(-1234, -3, 5678));
    }

    @Test
    void outOfTheRavineUpItsStairsToTheTargetsLevel() {
        // we stand on the ravine floor; the target paces at the top, 8 blocks up, 5 blocks to the side
        search.begin(StalemateSearch.GOAL_APPROACH, 10.5, 56, 0.5, 5.5, 64, 0.5);
        assertEquals(StalemateSearch.FOUND, run(StalemateWorlds.ravine(true)));
        int n = search.routeLength();
        long[] r = search.route();
        assertEquals(StalemateSearch.pack(10, 56, 0), r[0], "starts at our feet");
        long end = r[n - 1];
        assertTrue(Math.abs(y(end) - 64) <= 1, "ends at the target's level");
        double hx = x(end) + 0.5 - 5.5;
        double hz = z(end) + 0.5 - 0.5;
        assertTrue(hx * hx + hz * hz <= StalemateSearch.APPROACH_REACH * StalemateSearch.APPROACH_REACH);
        assertWalkable(0); // up the stairs: never a drop, never a climb of more than one block
        boolean viaEnd = false;
        for (int i = 0; i < n; i++) {
            if (Math.abs(z(r[i])) >= 12 && y(r[i]) >= 62) {
                viaEnd = true; // the last steps out at one of the ends
            }
        }
        assertTrue(viaEnd, "leaves the ravine by its stairs");
        assertTrue(n < 40, "a short route, not a wander: " + n);
    }

    @Test
    void downTheStairsToATargetAtTheRavineBottom() {
        // the reverse layout (live finding): we are on top, the target sits on the ravine floor straight below
        search.begin(StalemateSearch.GOAL_APPROACH, 8.4, 64, 0.6, 9.3, 56, 0.3);
        assertEquals(StalemateSearch.FOUND, run(StalemateWorlds.ravine(true)));
        long end = search.route()[search.routeLength() - 1];
        assertEquals(56, y(end));
        assertWalkable(StalemateSearch.OUR_MAX_DROP); // stairs, or a no-damage hop of at most 3 onto them
        boolean onStairs = false;
        for (int i = 0; i < search.routeLength(); i++) {
            int cy = y(search.route()[i]);
            onStairs |= cy > 56 && cy < 64 && x(search.route()[i]) >= 9 && x(search.route()[i]) <= 11;
        }
        assertTrue(onStairs, "down by the stairs");
    }

    @Test
    void noStairsNoSafeWayDownButASurvivableFallIsFoundWhenAllowed() {
        search.begin(StalemateSearch.GOAL_APPROACH, 8.4, 64, 0.6, 9.3, 56, 0.3);
        assertEquals(StalemateSearch.NOT_FOUND, run(StalemateWorlds.ravine(false)));
        search.begin(StalemateSearch.GOAL_APPROACH, 8.4, 64, 0.6, 9.3, 56, 0.3, 12);
        assertEquals(StalemateSearch.FOUND, run(StalemateWorlds.ravine(false)));
        assertEquals(56, y(search.route()[search.routeLength() - 1]));
        assertWalkable(8);
        search.begin(StalemateSearch.GOAL_APPROACH, 8.4, 64, 0.6, 9.3, 56, 0.3, 40);
        assertEquals(StalemateSearch.FOUND, run(StalemateWorlds.ravine(false)), "clamped to the cap, still deep enough");
    }

    @Test
    void sealedTrenchHasNoApproach() {
        search.begin(StalemateSearch.GOAL_APPROACH, 10.5, 56, 0.5, 5.5, 64, 0.5);
        assertEquals(StalemateSearch.NOT_FOUND, run(StalemateWorlds.ravine(false)));
    }

    @Test
    void meetingGroundIsOpenFlatGroundTheTargetCanReachAndWeCanToo() {
        CombatVoxels w = StalemateWorlds.pillarAndLedge();
        search.begin(StalemateSearch.GOAL_APPROACH, 9.5, 67, 0.5, 0.5, 66, 0.5);
        assertEquals(StalemateSearch.NOT_FOUND, run(w), "the pillar top cannot be climbed");
        search.begin(StalemateSearch.GOAL_MEET, 9.5, 67, 0.5, 0.5, 66, 0.5);
        assertEquals(StalemateSearch.FOUND, run(w));
        long end = search.route()[search.routeLength() - 1];
        assertEquals(64, y(end), "on the ground below");
        double hx = x(end) + 0.5 - 0.5;
        double hz = z(end) + 0.5 - 0.5;
        assertTrue(hx * hx + hz * hz >= StalemateSearch.MEET_MIN_DISTANCE * StalemateSearch.MEET_MIN_DISTANCE);
        assertTrue(x(end) < 8 || x(end) > 10 || Math.abs(z(end)) > 1, "off the ledge");
        assertWalkable(StalemateSearch.OUR_MAX_DROP);
    }

    @Test
    void noMeetingGroundWhenTheTargetCanAlreadyWalkToUs() {
        search.begin(StalemateSearch.GOAL_MEET, 6.5, 64, 0.5, 0.5, 66, 0.5);
        assertEquals(StalemateSearch.NOT_FOUND, run(StalemateWorlds.pillarAndLedge()));
    }

    @Test
    void baitCellIsANewAngleAtABoundedDistance() {
        search.begin(StalemateSearch.GOAL_BAIT, 6.5, 64, 0.5, 0.5, 66, 0.5);
        assertEquals(StalemateSearch.FOUND, run(StalemateWorlds.pillarAndLedge()));
        long end = search.route()[search.routeLength() - 1];
        double hx = x(end) + 0.5 - 0.5;
        double hz = z(end) + 0.5 - 0.5;
        double h = Math.sqrt(hx * hx + hz * hz);
        assertTrue(h >= StalemateSearch.BAIT_MIN_DISTANCE && h <= StalemateSearch.BAIT_MAX_DISTANCE, "h=" + h);
        double turn = Math.toDegrees(Math.acos(hx / h)); // our old bearing from the target was +x
        assertTrue(turn >= StalemateSearch.BAIT_MIN_TURN_DEG - 1e-6, "turn " + turn);
    }

    @Test
    void neverStepsIntoOrBesideLava() {
        CombatVoxels w = StalemateWorlds.flatWithLava();
        search.begin(StalemateSearch.GOAL_APPROACH, 0.5, 64, 0.5, 8.5, 64, 0.5);
        assertEquals(StalemateSearch.FOUND, run(w));
        long[] r = search.route();
        for (int i = 0; i < search.routeLength(); i++) {
            int cx = x(r[i]);
            int cz = z(r[i]);
            assertFalse((cx >= 3 && cx <= 5 && cz >= -4 && cz <= 4) || (cx >= 2 && cx <= 6 && cz >= -3 && cz <= 3), "on or beside the lava at " + cx + "," + cz);
        }
        assertWalkable(StalemateSearch.OUR_MAX_DROP);
    }

    @Test
    void unknownBlocksAreNeverWalked() {
        CombatVoxels unloaded = (x, y, z) -> x > 3 ? CombatVoxels.UNKNOWN : (y <= 63 ? CombatVoxels.SOLID
                : CombatVoxels.AIR);
        search.begin(StalemateSearch.GOAL_APPROACH, 0.5, 64, 0.5, 8.5, 64, 0.5);
        assertEquals(StalemateSearch.NOT_FOUND, run(unloaded));
    }

    @Test
    void theSearchIsBudgetedPerStep() {
        search.begin(StalemateSearch.GOAL_BAIT, 0.5, 64, 0.5, 100.5, 64, 0.5); // nothing qualifies: floods the box
        CombatVoxels flat = (x, y, z) -> y <= 63 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        assertEquals(StalemateSearch.RUNNING, search.step(flat, 50));
        assertEquals(50, search.expansions());
        assertEquals(StalemateSearch.NOT_FOUND, run(flat));
        assertTrue(search.expansions() <= StalemateSearch.MAX_EXPANSIONS + 1);
    }
}
