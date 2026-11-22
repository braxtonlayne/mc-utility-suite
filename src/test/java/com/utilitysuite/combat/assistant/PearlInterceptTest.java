package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.PearlSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Start, retarget and stop rules of the pearl intercept over a scripted pearl source and landing. */
public class PearlInterceptTest {

    /** A pearl of one thrower, in flight while {@link #flying}. */
    public static final class ScriptedPearls implements PearlSource {
        public int thrower = 10;
        public boolean flying;
        public int queries;

        @Override
        public boolean pearlOf(int throwerEntityId, double[] out) {
            queries++;
            if (!flying || throwerEntityId != thrower) {
                return false;
            }
            out[0] = 3;
            out[1] = 66;
            out[2] = 0;
            out[3] = 1.2;
            out[4] = 0.3;
            out[5] = 0;
            return true;
        }
    }

    /** Lands at a scripted point (or fails). */
    public static final class ScriptedLanding implements PearlIntercept.Landing {
        public boolean ok = true;
        public double x = 30, y = 64, z = 0;
        public int sims;

        @Override
        public boolean simulate(double px, double py, double pz, double vx, double vy, double vz, CombatVoxels w,
                                int maxTicks) {
            sims++;
            return ok;
        }

        @Override
        public double landX() {
            return x;
        }

        @Override
        public double landY() {
            return y;
        }

        @Override
        public double landZ() {
            return z;
        }
    }

    static final int TARGET = 10;
    static final double SPACING_MAX = 3.1;
    static final double RANGE = 16.0;
    static final CombatVoxels FLAT = ProFeelLayerTest.FLAT;

    PearlIntercept pi;
    ScriptedPearls pearls;
    ScriptedLanding landing;

    @BeforeEach
    void setUp() {
        landing = new ScriptedLanding();
        pearls = new ScriptedPearls();
        pi = new PearlIntercept(landing);
        pi.useSource(pearls);
        pi.useVoxels(FLAT);
    }

    boolean update(long tick, double tx) {
        return pi.update(TARGET, tick, 0, 64, 0, tx, 0, SPACING_MAX, RANGE);
    }

    @Test
    void startsWhenAPearlInFlightLandsFarAway() {
        assertFalse(update(1, 3), "no pearl");
        pearls.flying = true;
        assertTrue(update(2, 3));
        assertTrue(pi.active());
        assertEquals(1, pi.starts());
        assertEquals(30, pi.landX());
        assertTrue(pi.radiusNeeded(0, 64, 0) >= 30 + PearlIntercept.RADIUS_SLACK - 1e-9);
    }

    @Test
    void neverStartsForANearLandingAFailedSimOrWithoutVoxels() {
        pearls.flying = true;
        landing.x = SPACING_MAX + PearlIntercept.START_EXTRA - 0.1; // close: walk, do not run a route
        assertFalse(update(1, 3));
        landing.x = 30;
        landing.ok = false;
        assertFalse(update(2, 3));
        landing.ok = true;
        pi.useVoxels(null);
        assertFalse(update(3, 3));
        assertEquals(0, pi.starts());
        assertEquals(0.0, pi.radiusNeeded(0, 64, 0));
    }

    @Test
    void retargetsWhenTheLandingMoves() {
        pearls.flying = true;
        update(1, 3);
        landing.x = 30.5; // under the retarget distance
        update(2, 3);
        assertEquals(0, pi.retargets());
        assertEquals(30, pi.landX());
        landing.x = 34;
        update(3, 3);
        assertEquals(1, pi.retargets());
        assertEquals(34, pi.landX());
        landing.ok = false; // a failed sim mid-flight keeps the last landing
        assertTrue(update(4, 3));
        assertEquals(34, pi.landX());
    }

    @Test
    void theThrowerNearUsDuringTheFlightDoesNotStopTheRun() {
        pearls.flying = true;
        update(1, 2);
        for (int t = 2; t < 10; t++) {
            assertTrue(update(t, 2), "the thrower still stands next to us while its pearl flies");
        }
    }

    @Test
    void stopsOnceTheTargetIsInPursuitRangeAfterThePearlVanished() {
        pearls.flying = true;
        update(1, 3);
        pearls.flying = false; // landed: the thrower teleported
        assertTrue(update(2, 30), "far away at the landing: keep running");
        assertTrue(update(3, 20));
        assertFalse(update(4, RANGE - 1), "within normal pursuit range again");
        assertFalse(pi.active());
    }

    @Test
    void stopsSixtyTicksAfterThePearlVanished() {
        pearls.flying = true;
        update(1, 3);
        pearls.flying = false;
        for (int t = 2; t <= 1 + PearlIntercept.LINGER_TICKS; t++) {
            assertTrue(update(t, 40), "tick " + t);
        }
        assertFalse(update(2 + PearlIntercept.LINGER_TICKS, 40));
    }

    @Test
    void otherThrowersAreIgnored() {
        pearls.flying = true;
        pearls.thrower = 99;
        assertFalse(update(1, 3));
    }

    @Test
    void theProductionLandingStubNeverStarts() {
        PearlIntercept plain = new PearlIntercept();
        plain.useSource(pearls);
        plain.useVoxels(FLAT);
        pearls.flying = true;
        assertFalse(plain.update(TARGET, 1, 0, 64, 0, 3, 0, SPACING_MAX, RANGE));
    }
}
