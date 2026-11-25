package com.utilitysuite.world.stronghold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrongholdMathTest {

    @Test
    void perpendicularRaysMeet() {
        StrongholdMath.Hit h = StrongholdMath.intersect(0, 0, 1, 0, 100, -50, 0, 1, 2);
        assertNotNull(h);
        assertEquals(100, h.x(), 1e-9);
        assertEquals(0, h.z(), 1e-9);
        assertEquals(100, h.t1(), 1e-9);
        assertEquals(50, h.t2(), 1e-9);
    }

    @Test
    void twoThrowsTowardAKnownStrongholdRecoverIt() {
        double sx = 1500;
        double sz = -900;
        // Throw spots 300 blocks apart, directions scaled differently (unnormalised input).
        double ax = 0;
        double az = 0;
        double bx = 300;
        double bz = 0;
        StrongholdMath.Hit h = StrongholdMath.intersect(ax, az, (sx - ax) * 0.01, (sz - az) * 0.01, bx, bz,
                (sx - bx) * 3, (sz - bz) * 3, 2);
        assertNotNull(h);
        assertEquals(sx, h.x(), 1e-6);
        assertEquals(sz, h.z(), 1e-6);
    }

    @Test
    void parallelAndNearParallelRaysAreRejected() {
        assertNull(StrongholdMath.intersect(0, 0, 1, 0, 0, 100, 1, 0, 2)); // exactly parallel
        double tiny = Math.tan(Math.toRadians(1.0)); // 1 degree apart, below the 2 degree limit
        assertNull(StrongholdMath.intersect(0, 0, 1, 0, 0, 100, 1, -tiny, 2));
        double ok = Math.tan(Math.toRadians(5.0));
        assertNotNull(StrongholdMath.intersect(0, 0, 1, 0, 0, 100, 1, -ok, 2));
    }

    @Test
    void raysMeetingBehindAThrowerAreRejected() {
        // Diverging rays: the lines cross behind the origins.
        assertNull(StrongholdMath.intersect(0, 0, 1, 1, 10, 0, 1, -1, 2));
        assertNull(StrongholdMath.intersect(0, 0, 0, 0, 10, 0, 1, -1, 2)); // zero direction
    }

    @Test
    void ringBandsMatchVanilla() {
        assertEquals(1280, StrongholdMath.ringMin(1), 1e-9);
        assertEquals(2816, StrongholdMath.ringMax(1), 1e-9);
        assertEquals(4352, StrongholdMath.ringMin(2), 1e-9);
        assertEquals(5888, StrongholdMath.ringMax(2), 1e-9);
        assertEquals(7424, StrongholdMath.ringMin(3), 1e-9);
        assertEquals(8960, StrongholdMath.ringMax(3), 1e-9);
    }

    @Test
    void radiusSnapsToNearestBand() {
        assertEquals(2000, StrongholdMath.snapRadius(2000), 1e-9); // inside ring 1
        assertEquals(1, StrongholdMath.nearestRing(2000));
        assertEquals(1280, StrongholdMath.snapRadius(400), 1e-9); // too close: inner edge of ring 1
        assertEquals(2816, StrongholdMath.snapRadius(3200), 1e-9); // gap, nearer ring 1
        assertEquals(2, StrongholdMath.nearestRing(4000));
        assertEquals(4352, StrongholdMath.snapRadius(4000), 1e-9); // gap, nearer ring 2
        assertEquals(8, StrongholdMath.nearestRing(1e6));
    }

    @Test
    void estimateSnapsAlongTheRadius() {
        StrongholdMath.Estimate e = StrongholdMath.estimate(3000, 0, true);
        assertTrue(e.snapped());
        assertEquals(2816, e.x(), 1e-9);
        assertEquals(0, e.z(), 1e-9);
        assertEquals(3000, e.rawX(), 1e-9);
        assertEquals(1, e.ring());

        StrongholdMath.Estimate diag = StrongholdMath.estimate(-3000, -3000, true); // r ~ 4243 -> ring 2 inner edge
        assertEquals(2, diag.ring());
        assertEquals(4352, diag.radius(), 1e-6);
        assertTrue(diag.x() < 0 && diag.z() < 0);

        StrongholdMath.Estimate raw = StrongholdMath.estimate(3000, 0, false);
        assertFalse(raw.snapped());
        assertEquals(3000, raw.x(), 1e-9);
    }
}
