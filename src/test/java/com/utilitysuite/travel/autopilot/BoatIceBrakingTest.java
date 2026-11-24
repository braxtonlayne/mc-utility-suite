package com.utilitysuite.travel.autopilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatIceBrakingTest {

    private static final double D = BoatIceBraking.DECEL_ICE;

    @Test
    void waterIsNeverAffected() {
        assertEquals(BoatIceBraking.NONE, BoatIceBraking.command(false, 1.5, D, 2, 90));
    }

    @Test
    void gentleTurnsAreIgnored() {
        assertEquals(BoatIceBraking.NONE, BoatIceBraking.command(true, 1.5, D, 2, 30));
    }

    @Test
    void farCornerNeedsNoBraking() {
        double stop = BoatIceBraking.stoppingDistance(1.0, D) + BoatIceBraking.MARGIN;
        assertEquals(BoatIceBraking.NONE, BoatIceBraking.command(true, 1.0, D, stop + 0.5, 90));
    }

    @Test
    void releasesForwardWhenStoppingDistanceExceedsCornerDistance() {
        double stop = BoatIceBraking.stoppingDistance(1.0, D) + BoatIceBraking.MARGIN;
        assertEquals(BoatIceBraking.COAST, BoatIceBraking.command(true, 1.0, D, stop - 0.5, 90));
    }

    @Test
    void holdsBackWhenCoastingWouldOvershootBadly() {
        assertEquals(BoatIceBraking.COAST_AND_BACK, BoatIceBraking.command(true, 1.5, D, 5, 90));
        assertEquals(BoatIceBraking.COAST, BoatIceBraking.command(true, 0.1, D, 0.2, 90), "too slow for BACK");
    }

    @Test
    void preTurnBlendsContinuouslyToOutgoingLeg() {
        assertEquals(0.0, BoatIceBraking.preTurnHeading(0, 90, 20, 20), 1e-9);
        double mid = BoatIceBraking.preTurnHeading(0, 90, 10, 20);
        assertTrue(mid > 0 && mid < 90);
        assertEquals(90.0, BoatIceBraking.preTurnHeading(0, 90, 2, 20), 1e-9);
        assertEquals(-90.0, BoatIceBraking.preTurnHeading(0, -90, 0, 20), 1e-9);
    }
}
