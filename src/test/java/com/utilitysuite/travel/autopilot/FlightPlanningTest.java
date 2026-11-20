package com.utilitysuite.travel.autopilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rocket reserve / durability abort and landing-site scoring. */
class FlightPlanningTest {

    @Test
    void reserveAbortsCruise() {
        assertEquals(FlightReserve.Decision.CONTINUE, FlightReserve.evaluate(10, 3, 300, 20));
        assertEquals(FlightReserve.Decision.CONTINUE, FlightReserve.evaluate(4, 3, 21, 20));
        assertEquals(FlightReserve.Decision.ABORT_LOW_ROCKETS, FlightReserve.evaluate(3, 3, 300, 20));
        assertEquals(FlightReserve.Decision.ABORT_LOW_ROCKETS, FlightReserve.evaluate(0, 0, 300, 20));
        assertEquals(FlightReserve.Decision.ABORT_LOW_DURABILITY, FlightReserve.evaluate(10, 3, 20, 20));
        assertEquals(FlightReserve.Decision.ABORT_LOW_DURABILITY, FlightReserve.evaluate(0, 3, 5, 20),
                "durability takes precedence: the elytra is about to stop working");
    }

    @Test
    void reserveAbortHappensWhileRocketsRemainForLanding() {
        RocketPolicy policy = new RocketPolicy();
        // At the reserve the policy refuses to spend more and the reserve check aborts: both agree.
        assertTrue(!policy.shouldFire(50, 100, 0.2, false, 999, 3, 3));
        assertEquals(FlightReserve.Decision.ABORT_LOW_ROCKETS, FlightReserve.evaluate(3, 3, 300, 20));
    }

    private static LandingSiteScorer.Sample flat() {
        return new LandingSiteScorer.Sample(64, false, false, false, false, 0, 10);
    }

    @Test
    void rejectsUnsafeColumns() {
        double r = LandingSiteScorer.REJECT;
        assertEquals(r, LandingSiteScorer.score(new LandingSiteScorer.Sample(64, true, false, false, false, 0, 10),
                30, 10, 8), "water");
        assertEquals(r, LandingSiteScorer.score(new LandingSiteScorer.Sample(64, true, true, false, false, 0, 10),
                30, 10, 8), "lava");
        assertEquals(r, LandingSiteScorer.score(new LandingSiteScorer.Sample(64, false, false, true, false, 0, 10),
                30, 10, 8), "trees");
        assertEquals(r, LandingSiteScorer.score(new LandingSiteScorer.Sample(64, false, false, false, true, 0, 10),
                30, 10, 8), "magma/cactus/fire");
        assertEquals(r, LandingSiteScorer.score(new LandingSiteScorer.Sample(64, false, false, false, false, 2, 10),
                30, 10, 8), "uneven");
        assertEquals(r, LandingSiteScorer.score(new LandingSiteScorer.Sample(64, false, false, false, false, 0, 2),
                30, 10, 8), "no headroom");
        assertEquals(r, LandingSiteScorer.score(flat(), 100, 10, 8), "out of glide reach");
        assertEquals(r, LandingSiteScorer.score(flat(), 10, -3, 8), "above the glider");
    }

    @Test
    void prefersFlatNearbySitesOnAComfortableSlope() {
        double ideal = LandingSiteScorer.score(flat(), 30, 10, 8);       // slope 0.33
        double bumpy = LandingSiteScorer.score(
                new LandingSiteScorer.Sample(64, false, false, false, false, 1, 10), 30, 10, 8);
        double far = LandingSiteScorer.score(flat(), 75, 25, 8);         // same slope, farther
        double steep = LandingSiteScorer.score(flat(), 10, 30, 8);       // slope 3: a dive
        assertTrue(ideal > bumpy, "flat beats a one-block step");
        assertTrue(ideal > far, "closer wins at equal slope");
        assertTrue(ideal > steep, "a comfortable glide slope beats a dive");
        assertTrue(Double.isFinite(steep), "steep is penalised, not rejected (the glider can lose height)");
    }

    @Test
    void picksTheBestOfAGrid() {
        // A tiny search over hand-made samples: only one site is acceptable and flat.
        LandingSiteScorer.Sample[] row = {
                new LandingSiteScorer.Sample(64, true, false, false, false, 0, 10),
                new LandingSiteScorer.Sample(70, false, false, true, false, 0, 10),
                new LandingSiteScorer.Sample(64, false, false, false, false, 1, 10),
                new LandingSiteScorer.Sample(64, false, false, false, false, 0, 10),
        };
        int best = -1;
        double bestScore = LandingSiteScorer.REJECT;
        for (int i = 0; i < row.length; i++) {
            double s = LandingSiteScorer.score(row[i], 20 + i * 8, 20, 8);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        assertEquals(3, best);
    }

    @Test
    void safetyBudget() {
        assertEquals(SafetyBudget.Stop.NONE, SafetyBudget.check(20, 20, 8, 6));
        assertEquals(SafetyBudget.Stop.LOW_HEALTH, SafetyBudget.check(8, 20, 8, 6));
        assertEquals(SafetyBudget.Stop.LOW_FOOD, SafetyBudget.check(20, 6, 8, 6));
        assertEquals(SafetyBudget.Stop.NONE, SafetyBudget.check(1, 0, 0, 0), "0 disables both");
    }
}
