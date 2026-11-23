package com.utilitysuite.core.input;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The velocity-continuous pursuit controller: locks onto and follows a moving target sampled once per client tick
 * (20 Hz) while frames run faster, with speed and acceleration caps and no overshoot.
 */
class RotationTrackerTest {

    private static final double TICK = 0.05;
    private static final double EPS = 1.0e-6;

    /** Result of one simulated run. */
    private static final class Run {
        double maxErrAfterLock;
        double sumErrAfterLock;
        int framesAfterLock;
        double maxSpeed;
        double maxDv;
        double maxStepRatio;
        double maxOvershoot;

        double meanAfterLock() {
            return sumErrAfterLock / Math.max(1, framesAfterLock);
        }
    }

    /**
     * Frames at {@code fps}; the target is sampled at every tick boundary (before that frame, as the game ticks before
     * it renders) and the error is measured each frame against the true continuous target.
     */
    private static Run simulate(DoubleUnaryOperator yawAt, DoubleUnaryOperator pitchAt, float startYaw,
                                float startPitch, double speed, double fps, double seconds, double lockAfter,
                                long seed) {
        RotationTracker t = new RotationTracker(new Random(seed));
        t.setMaxSpeed(speed);
        Run r = new Run();
        float cy = startYaw;
        float cp = startPitch;
        double time = 0.0;
        double nextTick = 0.0;
        boolean first = true;
        double lastVy = 0.0;
        double lastVp = 0.0;
        double dt = 1.0 / fps;
        while (time < seconds) {
            if (time >= nextTick - 1e-9) {
                float ty = (float) yawAt.applyAsDouble(nextTick);
                float tp = (float) pitchAt.applyAsDouble(nextTick);
                if (first) {
                    t.reset(ty, tp);
                    t.engage(0.0, 0.0);
                    first = false;
                } else {
                    t.sample(ty, tp, TICK);
                }
                nextTick += TICK;
            }
            t.advance(dt, cy, cp);
            float ny = t.yaw();
            float np = t.pitch();
            double step = RotationLimits.distance(cy, cp, ny, np);
            r.maxStepRatio = Math.max(r.maxStepRatio, step / (RotationLimits.MAX_DEG_PER_SEC * dt));
            double vy = t.velYaw();
            double vp = t.velPitch();
            r.maxSpeed = Math.max(r.maxSpeed, Math.hypot(vy, vp));
            r.maxDv = Math.max(r.maxDv, Math.hypot(vy - lastVy, vp - lastVp) / dt);
            lastVy = vy;
            lastVp = vp;
            cy = ny;
            cp = np;
            time += dt;
            double err = RotationLimits.distance(cy, cp, (float) yawAt.applyAsDouble(time),
                    (float) pitchAt.applyAsDouble(time));
            if (time >= lockAfter) {
                r.maxErrAfterLock = Math.max(r.maxErrAfterLock, err);
                r.sumErrAfterLock += err;
                r.framesAfterLock++;
            }
        }
        return r;
    }

    private static double triangle(double t, double rate, double halfPeriod) {
        double phase = t % (2 * halfPeriod);
        return phase < halfPeriod ? rate * phase : rate * (2 * halfPeriod - phase);
    }

    @Test
    void constantYawRateIsLockedWithinAQuarterSecond() {
        for (double fps : new double[] {30, 60, 75, 144, 240}) {
            Run r = simulate(t -> 30 + 120 * t, t -> 5, 30f, 5f, 240, fps, 3.0, 0.25, 1);
            assertTrue(r.maxErrAfterLock < 2.0, fps + " fps: error after 0.25 s " + r.maxErrAfterLock);
        }
    }

    @Test
    void pitchSweepIsLockedWithinAQuarterSecond() {
        for (double fps : new double[] {60, 144}) {
            Run r = simulate(t -> -70, t -> -40 + 30 * t, -70f, -40f, 240, fps, 2.5, 0.25, 2);
            assertTrue(r.maxErrAfterLock < 2.0, fps + " fps: error after 0.25 s " + r.maxErrAfterLock);
        }
    }

    /**
     * Angle of a strafing opponent whose angular rate flips between +rate and -rate every {@code halfPeriod}, with the
     * rate changing the way a player's ground velocity does in Minecraft (each tick keeps 0.546 of the old velocity:
     * ground friction 0.6 x 0.91), integrated at 1 ms.
     */
    private static DoubleUnaryOperator playerStrafe(double rate, double halfPeriod, double seconds) {
        int n = (int) (seconds * 1000) + 2;
        double[] pos = new double[n];
        double v = 0.0;
        double p = 0.0;
        double k = -Math.log(0.546) / TICK;
        double keep = 1.0 - Math.exp(-k * 0.001);
        for (int i = 0; i < n; i++) {
            pos[i] = p;
            double want = ((int) (i * 0.001 / halfPeriod)) % 2 == 0 ? rate : -rate;
            v += (want - v) * keep;
            p += v * 0.001;
        }
        return t -> {
            double x = Math.max(0.0, Math.min(n - 1.001, t * 1000.0));
            int i = (int) x;
            return pos[i] + (pos[i + 1] - pos[i]) * (x - i);
        };
    }

    @Test
    void strafeReversalsStayCloseOnAverage() {
        // +-150 deg/s, reversing every 0.4 s (a strafing opponent at 2-3 blocks), player-like velocity changes
        DoubleUnaryOperator strafe = playerStrafe(150, 0.4, 7.0);
        for (double fps : new double[] {60, 144, 240}) {
            Run r = simulate(t -> 10 + strafe.applyAsDouble(t), t -> -5, 10f, -5f, 240, fps, 6.0, 0.5, 3);
            assertTrue(r.meanAfterLock() < 3.0, fps + " fps: mean error " + r.meanAfterLock()
                    + " (max " + r.maxErrAfterLock + ")");
        }
    }

    /**
     * An instant velocity reversal (+150 to -150 deg/s in zero time) is not physically what a player does, and it is
     * not trackable to 3 degrees at a 240 deg/s cap: the reversal shows only in the next 20 Hz sample, when the gap is
     * already ~15 degrees, and only 90 deg/s is left to close it. This bounds the worst case so it cannot regress.
     */
    @Test
    void instantReversalsStayBounded() {
        // measured 2026-11-23: 6.4 (60 fps) / 7.7 (144 fps) at 240 deg/s, 2.9 / 3.3 at the 450 deg/s hard cap
        for (double fps : new double[] {60, 144}) {
            Run r = simulate(t -> 10 + triangle(t, 150, 0.4), t -> 0, 10f, 0f, 240, fps, 6.0, 0.5, 3);
            assertTrue(r.meanAfterLock() < 8.5, fps + " fps: mean error " + r.meanAfterLock()
                    + " (max " + r.maxErrAfterLock + ")");
            Run fast = simulate(t -> 10 + triangle(t, 150, 0.4), t -> 0, 10f, 0f, 450, fps, 6.0, 0.5, 3);
            assertTrue(fast.meanAfterLock() < 4.0, fps + " fps at the 450 deg/s cap: mean error "
                    + fast.meanAfterLock());
        }
    }

    @Test
    void speedAccelerationAndStepStayCapped() {
        for (double speed : new double[] {160, 240, 450, 100_000}) {
            // erratic target: fast sweeps, reversals and occasional large jumps (target switches)
            Random rnd = new Random(11);
            double[] jumps = new double[40];
            for (int i = 0; i < jumps.length; i++) {
                jumps[i] = rnd.nextDouble() * 300 - 150;
            }
            DoubleUnaryOperator yaw = t -> jumps[(int) (t / 0.35) % jumps.length] + 400 * Math.sin(t * 3.1);
            DoubleUnaryOperator pitch = t -> 60 * Math.sin(t * 2.3) + jumps[(int) (t / 0.7) % jumps.length] * 0.15;
            for (double fps : new double[] {30, 144, 500}) {
                RotationTracker probe = new RotationTracker(new Random(0));
                probe.setMaxSpeed(speed);
                Run r = simulate(yaw, pitch, 0f, 0f, speed, fps, 8.0, 0.0, 4);
                assertTrue(r.maxSpeed <= probe.maxSpeed() + EPS, "speed " + r.maxSpeed + " > " + probe.maxSpeed());
                assertTrue(r.maxSpeed <= RotationLimits.MAX_DEG_PER_SEC + EPS, "hard cap " + r.maxSpeed);
                assertTrue(r.maxDv <= probe.maxAccel() * (1 + 1e-9) + EPS,
                        "acceleration " + r.maxDv + " > " + probe.maxAccel());
                assertTrue(r.maxStepRatio <= 1.0 + 1e-4, "step ratio to RotationLimits " + r.maxStepRatio);
            }
        }
    }

    @Test
    void approachToAStillTargetDoesNotOvershoot() {
        for (double dist : new double[] {3, 12, 29, 90}) {
            RotationTracker t = new RotationTracker(new Random(5));
            t.setMaxSpeed(240);
            t.setDriftAmplitude(0.0);
            t.reset((float) dist, 0f);
            t.engage(0.0, 0.0);
            float cy = 0f;
            float cp = 0f;
            double maxYaw = 0;
            for (int i = 0; i < 240; i++) {
                t.advance(1.0 / 120.0, cy, cp);
                cy = t.yaw();
                cp = t.pitch();
                maxYaw = Math.max(maxYaw, cy);
            }
            assertTrue(maxYaw <= dist + 0.05, dist + ": overshoot to " + maxYaw);
            assertEquals(dist, cy, 0.05, dist + ": arrives");
        }
    }

    @Test
    void takesOverAMovingCameraWithoutAVelocityJump() {
        RotationTracker t = new RotationTracker(new Random(6));
        t.setMaxSpeed(240);
        t.reset(20f, 0f);
        t.engage(-300.0, 0.0); // camera still swinging the other way at handover
        double dt = 1.0 / 144.0;
        t.advance(dt, 0f, 0f);
        assertTrue(Math.abs(t.velYaw() - -300.0) <= t.maxAccel() * dt + EPS, "first frame dv " + t.velYaw());
    }

    @Test
    void driftIsSmallAndAStillTargetIsHeld() {
        RotationTracker t = new RotationTracker(new Random(8));
        t.setMaxSpeed(240);
        t.reset(45f, 10f);
        t.engage(0.0, 0.0);
        float cy = 45f;
        float cp = 10f;
        double maxErr = 0;
        for (int i = 0; i < 1000; i++) {
            if (i % 3 == 0) {
                t.sample(45f, 10f, TICK);
            }
            t.advance(1.0 / 60.0, cy, cp);
            cy = t.yaw();
            cp = t.pitch();
            maxErr = Math.max(maxErr, RotationLimits.distance(cy, cp, 45f, 10f));
        }
        assertTrue(maxErr < 0.3, "drift error " + maxErr);
        assertTrue(maxErr > 0.0, "a little drift remains");
    }

    @Test
    void wrapsYawTheShortWay() {
        RotationTracker t = new RotationTracker(new Random(9));
        t.setMaxSpeed(240);
        t.setDriftAmplitude(0.0);
        t.reset(-175f, 0f);
        t.engage(0.0, 0.0);
        float cy = 175f; // 10 degrees away across the wrap
        for (int i = 0; i < 120; i++) {
            t.advance(1.0 / 60.0, cy, 0f);
            assertTrue(t.yaw() >= cy - EPS, "turns the short way (positive)");
            cy = t.yaw();
        }
        assertEquals(185f, cy, 0.05);
    }
}
