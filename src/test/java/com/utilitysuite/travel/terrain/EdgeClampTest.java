package com.utilitysuite.travel.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeClampTest {

    private static final double HALF = 0.3; // player box half width
    private final EdgeClamp clamp = new EdgeClamp();
    private final FootprintSupportProbe probe = new FootprintSupportProbe();

    /** Probe for a player centred at (cx, cz) with feet at y. */
    private SupportProbe player(HeightField world, double cx, double feetY, double cz, double minDrop) {
        return probe.configure(world, cx - HALF, feetY, cz - HALF, cx + HALF, cz + HALF, minDrop);
    }

    @Test
    void flatGroundIsNeverClamped() {
        HeightField flat = new HeightField(64);
        SupportProbe p = player(flat, 0.5, 64, 0.5, 1.5);
        assertFalse(clamp.clamp(0.3, -0.2, p));
        assertEquals(0.3, clamp.x(), 1e-9);
        assertEquals(-0.2, clamp.z(), 1e-9);
    }

    @Test
    void cliffStopsMovementWhileTheBoxStillOverlapsTheFloor() {
        // Floor at x <= 0 (top 64), a 10-block drop from x = 1 on.
        HeightField world = new HeightField(64).top(1, 20, -20, 20, 54);
        // Player overhanging the edge: box 0.9 .. 1.5 still touches column 0.
        SupportProbe p = player(world, 1.2, 64, 0.5, 1.5);
        assertTrue(clamp.clamp(0.2, 0.0, p));
        assertTrue(clamp.x() > 0.0 && clamp.x() < 0.1, "moved up to the edge only: " + clamp.x());
        assertEquals(0.0, clamp.z(), 1e-9);
        // After the clamped move the box still overlaps the floor column.
        assertTrue(p.hasSupport(clamp.x(), 0.0));
    }

    @Test
    void movingAwayFromTheEdgeIsUntouched() {
        HeightField world = new HeightField(64).top(1, 20, -20, 20, 54);
        SupportProbe p = player(world, 1.2, 64, 0.5, 1.5);
        assertFalse(clamp.clamp(-0.25, 0.1, p));
    }

    @Test
    void tinyStepTowardTheVoidIsZeroed() {
        HeightField world = new HeightField(64).top(1, 20, -20, 20, 54);
        // Box exactly at the edge (maxX of column 0 overlap = minX 0.99); any move off support is cut to zero.
        SupportProbe p = player(world, 1.299, 64, 0.5, 1.5);
        assertTrue(clamp.clamp(0.04, 0.0, p));
        assertEquals(0.0, clamp.x(), 1e-9);
    }

    @Test
    void oneBlockStepDownIsNotAnEdgeAboveTheMinimumDrop() {
        HeightField world = new HeightField(64).top(1, 20, -20, 20, 63); // one block lower
        SupportProbe lenient = player(world, 1.2, 64, 0.5, 1.5);
        assertFalse(clamp.clamp(0.3, 0.0, lenient), "1-block step allowed with min drop 1.5");
        SupportProbe vanillaLike = player(world, 1.2, 64, 0.5, 0.6);
        assertTrue(clamp.clamp(0.3, 0.0, vanillaLike), "1-block step is an edge with the vanilla 0.6 drop");
    }

    @Test
    void dropThresholdBoundary() {
        // Exactly 2 blocks down: an edge for min drop 1.9, not for 2.0.
        HeightField world = new HeightField(64).top(1, 20, -20, 20, 62);
        assertTrue(clamp.clamp(0.3, 0.0, player(world, 1.2, 64, 0.5, 1.9)));
        assertFalse(clamp.clamp(0.3, 0.0, player(world, 1.2, 64, 0.5, 2.0)));
    }

    @Test
    void outsideCornerClampsBothAxes() {
        // Floor only in the quadrant x <= 0, z <= 0.
        HeightField world = new HeightField(40).top(-20, 0, -20, 0, 64);
        SupportProbe p = player(world, 1.2, 64, 1.2, 1.5);
        assertTrue(clamp.clamp(0.2, 0.2, p));
        assertTrue(p.hasSupport(clamp.x(), clamp.z()), "diagonal result keeps support");
        assertTrue(clamp.x() < 0.1 && clamp.z() < 0.1);
    }

    @Test
    void edgeJumpTriggersOnlyOnTheLastSupportedTick() {
        HeightField world = new HeightField(64).top(1, 20, -20, 20, 54);
        // Sprinting at 0.28 blocks/tick toward the drop.
        assertFalse(EdgeClamp.atEdge(player(world, 0.0, 64, 0.5, 1.5), 0.28, 0.0, 1.5), "still far from the edge");
        assertTrue(EdgeClamp.atEdge(player(world, 1.0, 64, 0.5, 1.5), 0.28, 0.0, 1.5), "next ticks leave support");
        assertFalse(EdgeClamp.atEdge(player(world, 1.0, 64, 0.5, 1.5), 0.0, 0.0, 1.5), "standing still");
        assertFalse(EdgeClamp.atEdge(player(world, 1.0, 64, 0.5, 1.5), -0.28, 0.0, 1.5), "running away");
        assertFalse(EdgeClamp.atEdge(player(world, 2.0, 64, 0.5, 1.5), 0.28, 0.0, 1.5), "already airborne");
    }

    @Test
    void slabHeightSupportUsesCollisionExtent() {
        // A half-height collision under the feet (custom query): top at y + 0.5.
        CollisionQuery slab = (x, y, z, lo, hi) -> y == 63 && x <= 0 && 63.5 >= lo && 63 <= hi;
        FootprintSupportProbe p = new FootprintSupportProbe().configure(slab, 0.2, 63.5, 0.2, 0.8, 0.8, 0.6);
        assertTrue(p.hasSupport(0, 0));
        assertFalse(p.hasSupport(1.0, 0));
    }
}
