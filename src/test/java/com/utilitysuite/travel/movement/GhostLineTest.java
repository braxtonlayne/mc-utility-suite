package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.Packed;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class GhostLineTest {

    private static long[] straight(int n) {
        long[] c = new long[n];
        for (int i = 0; i < n; i++) {
            c[i] = Packed.pack(i, 64, 0);
        }
        return c;
    }

    @Test
    void straightRoutePlacesGhostAtSpeedTimesLead() {
        GhostLine g = new GhostLine();
        long[] cells = straight(20);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.2, 20); // 4 blocks ahead
        assertTrue(g.active());
        assertEquals(GhostLine.ROUTE, g.mode());
        assertEquals(4.5f, g.ghostX(), 1e-4);
        assertEquals(0.5f, g.ghostZ(), 1e-4);
        assertEquals(64f, g.ghostY(), 1e-4);
        // trail: the player, the cells passed, the ghost last
        assertEquals(0.5f, g.trailX(0), 1e-4);
        assertEquals(g.ghostX(), g.trailX(g.trailCount() - 1), 1e-4);
    }

    @Test
    void lShapedRouteTurnsTheCorner() {
        GhostLine g = new GhostLine();
        long[] cells = new long[11];
        for (int i = 0; i <= 5; i++) {
            cells[i] = Packed.pack(i, 64, 0);
        }
        for (int i = 1; i <= 5; i++) {
            cells[5 + i] = Packed.pack(5, 64, i);
        }
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.35, 20); // 7 blocks: 5 along x, 2 along z
        assertEquals(5.5f, g.ghostX(), 1e-4);
        assertEquals(2.5f, g.ghostZ(), 1e-4);
    }

    @Test
    void clampsAtTheEndOfTheRoute() {
        GhostLine g = new GhostLine();
        long[] cells = straight(6);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 1.0, 40);
        assertEquals(5.5f, g.ghostX(), 1e-4);
        assertEquals(5.5f, g.trailX(g.trailCount() - 1), 1e-4);
    }

    @Test
    void zeroSpeedUsesTheMinimumLead() {
        GhostLine g = new GhostLine();
        long[] cells = straight(20);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.0, 20);
        assertEquals(0.5 + GhostLine.MIN_LEAD, g.ghostX(), 1e-4);
    }

    @Test
    void ghostTooCloseToThePlayerIsHidden() {
        GhostLine g = new GhostLine();
        long[] cells = straight(2); // route ends 1 block ahead
        g.updateRoute(cells, cells.length, 0, 0.5, 64, 0.5, 0.0, 20);
        assertFalse(g.active());
        g.updateJump(0, 64, 0, 1.0, 64, 0.5);
        assertFalse(g.active());
    }

    @Test
    void jumpArcEndpointsAndApex() {
        GhostLine g = new GhostLine();
        g.updateJump(0, 64, 0, 4, 63, 0);
        assertEquals(GhostLine.JUMP, g.mode());
        assertEquals(4f, g.ghostX(), 1e-5);
        assertEquals(63f, g.ghostY(), 1e-5);
        int n = g.trailCount();
        assertEquals(GhostLine.ARC_SEGMENTS + 1, n);
        assertEquals(0f, g.trailX(0), 1e-5);
        assertEquals(64f, g.trailY(0), 1e-5);
        assertEquals(4f, g.trailX(n - 1), 1e-5);
        assertEquals(63f, g.trailY(n - 1), 1e-5);
        float apex = -1e9f;
        for (int i = 0; i < n; i++) {
            apex = Math.max(apex, g.trailY(i));
        }
        // the apex is ARC_RISE above the chord midpoint (63.5), shifted a little toward the higher takeoff end
        assertTrue(apex >= 63.5f + (float) GhostLine.ARC_RISE - 1e-4f && apex < 64.7f, "apex " + apex);
        assertEquals(63.5f + (float) GhostLine.ARC_RISE, g.trailY(n / 2), 1e-4);
    }

    @Test
    void interpolatesBetweenTicks() {
        GhostLine g = new GhostLine();
        long[] cells = straight(30);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.2, 20);
        g.updateRoute(cells, cells.length, 1, 1.5, 64, 0.5, 0.2, 20); // ghost moves 4.5 -> 5.5
        float[] out = new float[3];
        g.interpolate(0.5f, out);
        assertEquals(5.0f, out[0], 1e-4);
        g.interpolate(1f, out);
        assertEquals(5.5f, out[0], 1e-4);
    }

    @Test
    void updatesAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        GhostLine g = new GhostLine();
        long[] cells = straight(40);
        float[] out = new float[3];
        long a = AllocationProbe.measure(5_000, () -> {
            g.updateRoute(cells, cells.length, 2, 2.5, 64, 0.5, 0.25, 20);
            g.interpolate(0.3f, out);
        });
        assertTrue(a < 4096, "route allocated " + a);
        long b = AllocationProbe.measure(5_000, () -> g.updateJump(0, 64, 0, 4, 64, 1));
        assertTrue(b < 4096, "jump allocated " + b);
    }
}
