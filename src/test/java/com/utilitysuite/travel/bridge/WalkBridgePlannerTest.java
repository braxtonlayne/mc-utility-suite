package com.utilitysuite.travel.bridge;

import com.utilitysuite.travel.Packed;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkBridgePlannerTest {

    /** Deck at y=64: solid for x <= 0 (the ledge the player walks off), air beyond. */
    private static final WalkBridgePlanner.Cells LEDGE = (x, y, z) -> !(y == 64 && x <= 0);

    @Test
    void walkingEastFillsCellsAheadInOrder() {
        WalkBridgePlanner p = new WalkBridgePlanner();
        // standing at x=0.7 (on the ledge's last block), walking east at sprint speed, 3 ticks + 0.6 ahead
        int n = p.plan(LEDGE, 0.7, 0.5, 0.28, 0, 64, 3, 0.6);
        assertTrue(n >= 2, "covers 0.84 + 0.6 blocks of travel");
        for (int i = 0; i < n; i++) {
            assertEquals(64, Packed.y(p.cell(i)));
            assertEquals(0, Packed.z(p.cell(i)));
            assertEquals(i + 1, Packed.x(p.cell(i)), "path order, one cell after another");
        }
    }

    @Test
    void legitEdgeOnlyTriggersNearTheEdge() {
        WalkBridgePlanner p = new WalkBridgePlanner();
        // walking east slowly with no lookahead and a 0.3 margin: nothing until the feet are within 0.3 of x=1
        assertEquals(0, p.plan(LEDGE, 0.5, 0.5, 0.1, 0, 64, 0, 0.3));
        assertEquals(1, p.plan(LEDGE, 0.75, 0.5, 0.1, 0, 64, 0, 0.3));
        assertEquals(1, Packed.x(p.cell(0)));
    }

    @Test
    void standingStillPlansNothingOnSolidGround() {
        WalkBridgePlanner p = new WalkBridgePlanner();
        assertEquals(0, p.plan(LEDGE, 0.5, 0.5, 0, 0, 64, 3, 0.6));
    }

    @Test
    void standingOverAirFillsTheCellUnderTheFeet() {
        WalkBridgePlanner p = new WalkBridgePlanner();
        assertEquals(1, p.plan(LEDGE, 1.2, 0.5, 0, 0, 64, 3, 0.6));
        assertEquals(1, Packed.x(p.cell(0)));
    }

    @Test
    void diagonalPathInsertsAFaceSharingCellAtEachCorner() {
        WalkBridgePlanner p = new WalkBridgePlanner();
        WalkBridgePlanner.Cells allAir = (x, y, z) -> !(x == 0 && z == 0);
        int n = p.plan(allAir, 0.5, 0.5, 0.2, 0.2, 64, 8, 0.6);
        assertTrue(n >= 3);
        int px = 0, pz = 0;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            long c = p.cell(i);
            assertTrue(seen.add(c), "no duplicates");
            int x = Packed.x(c), z = Packed.z(c);
            assertEquals(1, Math.abs(x - px) + Math.abs(z - pz), "each cell shares a face with the previous one");
            px = x;
            pz = z;
        }
    }

    @Test
    void neverExceedsTheBuffer() {
        WalkBridgePlanner p = new WalkBridgePlanner();
        WalkBridgePlanner.Cells allAir = (x, y, z) -> true;
        assertTrue(p.plan(allAir, 0.5, 0.5, 1.0, 0.3, 64, 40, 2) <= WalkBridgePlanner.MAX);
    }
}
