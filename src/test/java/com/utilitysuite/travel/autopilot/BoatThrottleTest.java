package com.utilitysuite.travel.autopilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatThrottleTest {

    private static final double INF = Double.POSITIVE_INFINITY;

    @Test
    void fullAheadOnAStraightOpenLeg() {
        assertEquals(1.0, BoatThrottle.throttle(0, INF, 0, 500, 0.8), 1e-9);
        assertEquals(1.0, BoatThrottle.throttle(9.9, INF, 0, 500, 0.8), 1e-9);
    }

    @Test
    void headingErrorReducesThrottleMonotonically() {
        double prev = 2.0;
        for (int e = 0; e <= 180; e += 5) {
            double t = BoatThrottle.throttle(e, INF, 0, 500, 0.5);
            assertTrue(t <= prev + 1e-12, "non-increasing at " + e);
            assertTrue(t >= BoatThrottle.MIN_TURN_THROTTLE - 1e-12);
            prev = t;
        }
        assertEquals(BoatThrottle.MIN_TURN_THROTTLE, BoatThrottle.throttle(-120, INF, 0, 500, 0.5), 1e-9);
    }

    @Test
    void deceleratesSmoothlyBeforeASharpCorner() {
        double speed = 0.6; // brake distance 18 blocks
        double brake = BoatThrottle.brakeDistance(speed);
        assertEquals(18.0, brake, 1e-9);
        assertEquals(1.0, BoatThrottle.throttle(0, brake + 1, 90, 500, speed), 1e-9, "no braking before");
        double prev = 1.0;
        for (double d = brake; d >= 0; d -= 1.0) {
            double t = BoatThrottle.throttle(0, d, 90, 500, speed);
            assertTrue(t <= prev + 1e-12, "throttle falls as the corner nears");
            assertTrue(prev - t < 0.06, "no sudden drop (smooth): " + (prev - t));
            prev = t;
        }
        assertEquals(BoatThrottle.MIN_TURN_THROTTLE, BoatThrottle.throttle(0, 0, 90, 500, speed), 1e-9);
    }

    @Test
    void sharpCornersSlowMoreThanGentleOnes() {
        double speed = 0.6;
        double gentle = BoatThrottle.throttle(0, 3, 20, 500, speed);
        double medium = BoatThrottle.throttle(0, 3, 50, 500, speed);
        double sharp = BoatThrottle.throttle(0, 3, 120, 500, speed);
        assertTrue(gentle > medium && medium > sharp, gentle + " > " + medium + " > " + sharp);
    }

    @Test
    void fasterBoatsStartBrakingEarlier() {
        assertTrue(BoatThrottle.throttle(0, 15, 90, 500, 1.0) < BoatThrottle.throttle(0, 15, 90, 500, 0.3));
    }

    @Test
    void slowsForArrival() {
        assertTrue(BoatThrottle.throttle(0, INF, 0, 3, 0.3) < BoatThrottle.throttle(0, INF, 0, 10, 0.3));
        assertEquals(BoatThrottle.MIN_ARRIVAL_THROTTLE, BoatThrottle.throttle(0, INF, 0, 0, 0.3), 1e-9);
    }

    @Test
    void dutyCycleAveragesToTheThrottle() {
        DutyCycle d = new DutyCycle();
        for (double throttle : new double[]{0.0, 0.25, 0.5, 0.8, 1.0}) {
            d.reset();
            int on = 0;
            for (int i = 0; i < 1000; i++) {
                if (d.next(throttle)) {
                    on++;
                }
            }
            assertEquals(throttle * 1000, on, 1.0, "throttle " + throttle);
        }
    }

    @Test
    void steeringPrefersTheBearingWhenClearAndOpenWaterOtherwise() {
        double[] clear = new double[BoatSteering.OFFSETS.length];
        java.util.Arrays.fill(clear, 24);
        assertEquals(30.0, BoatSteering.chooseHeading(30, clear, 24, 100), 1e-9);

        double[] bend = clear.clone();
        bend[0] = 6;   // the direct line hits the bank
        bend[1] = 8;   // -15
        bend[2] = 24;  // +15 is open
        assertEquals(45.0, BoatSteering.chooseHeading(30, bend, 24, 100), 1e-9);

        double[] near = clear.clone();
        near[0] = 10; // only 10 blocks of water, but the stop is 8 blocks away: go straight
        assertEquals(30.0, BoatSteering.chooseHeading(30, near, 24, 8), 1e-9);
    }
}
