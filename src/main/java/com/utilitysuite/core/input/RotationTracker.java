package com.utilitysuite.core.input;

import java.util.random.RandomGenerator;

/**
 * Pure camera-motion math (no Minecraft classes) for following a continuously moving look target, such as a strafing
 * opponent re-aimed every tick. Where {@link RotationCurve} plans one eased start-to-stop motion, this is a pursuit
 * controller that keeps the angular velocity continuous across target updates:
 * <ul>
 *   <li><b>Target motion estimate.</b> Successive samples (one per client tick) give the mean angular rate over the
 *       last interval, i.e. the rate at the interval's middle. The change between two such rates gives the target's
 *       angular acceleration (low-pass filtered, {@link #ACCEL_EST_ALPHA}, and capped at the controller's own
 *       acceleration cap); the rate is moved half an interval forward with it to the sample instant and clamped to the
 *       speed limit. A sample that jumps faster than twice the speed limit is a new target, not motion: the estimate
 *       resets.</li>
 *   <li><b>Reference.</b> Between samples the reference is extrapolated from the newest sample along the rate for at
 *       most {@link #HORIZON_SEC} (plus {@link #ACCEL_LEAD} of the acceleration over at most one tick), so frames
 *       between ticks move with the target instead of stepping once per tick. A tiny smooth value-noise drift
 *       ({@link #setDriftAmplitude}) sits on the reference as a human imperfection; it is C1-continuous, so it never
 *       adds a velocity step.</li>
 *   <li><b>Control.</b> Wanted velocity = feed-forward (the reference's own rate) + a correction along the error whose
 *       speed is {@code min(GAIN * err, sqrt(2 * BRAKE_FRACTION * accel * err))}: proportional near the target and
 *       capped by the braking distance further out, so an approach decelerates in time and does not overshoot. The
 *       wanted velocity is clamped to the speed limit (camera speed x speed scale, never above
 *       {@link RotationLimits#MAX_DEG_PER_SEC}); the actual velocity moves toward it by at most {@code accel * dt} per
 *       frame, so it never jumps.</li>
 * </ul>
 * The controller integrates from the live camera angle each frame (it owns only the velocity), so whatever later
 * limiting or the user does to the camera is taken as the new starting point. Yaw is unwrapped (continuous), pitch is
 * clamped to [-90, 90]. Nothing here allocates.
 */
public final class RotationTracker {

    /**
     * Angular acceleration cap per unit of speed limit (1/s): full speed from rest in 1/40 s. At 240 deg/s that is
     * 9600 deg/s^2, below the peak acceleration of a short {@link RotationCurve} turn (a 10 degree curve peaks near
     * 12000 deg/s^2).
     */
    static final double ACCEL_PER_SPEED = 40.0;
    /** Proportional gain on the error (1/s): a 63 % correction every 50 ms. */
    static final double GAIN = 20.0;
    /** Share of the acceleration cap the braking-distance limit plans with (margin against overshoot). */
    static final double BRAKE_FRACTION = 0.8;
    /** Weight of the newest sample in the filtered target acceleration. */
    static final double ACCEL_EST_ALPHA = 0.6;
    /** Share of the estimated target acceleration used to bend the extrapolated reference. */
    static final double ACCEL_LEAD = 0.5;
    /** Longest the reference is extrapolated past the newest sample (s); beyond it the target is treated as still. */
    static final double HORIZON_SEC = 0.1;
    /** The acceleration bend is used for at most one tick past the sample. */
    static final double ACCEL_HORIZON_SEC = 0.05;
    /** Samples further apart than this (s) carry no usable rate. */
    static final double MAX_SAMPLE_GAP_SEC = 0.25;
    static final double DEFAULT_DRIFT_DEG = 0.1;
    static final double DRIFT_FREQUENCY_HZ = 2.5;

    private final RandomGenerator random;

    private double maxSpeed = 240.0;
    private double accel = 240.0 * ACCEL_PER_SPEED;
    private double driftAmplitude = DEFAULT_DRIFT_DEG;

    // ---- target ------------------------------------------------------------------------------------------------------
    private boolean hasSample;
    private boolean hasRaw;
    private double sampleYaw;
    private double samplePitch;
    private double rawYaw;
    private double rawPitch;
    private double rateYaw;
    private double ratePitch;
    private double accYaw;
    private double accPitch;
    private double sinceSample;
    private double noiseT;

    // ---- output ------------------------------------------------------------------------------------------------------
    private double velYaw;
    private double velPitch;
    private double yaw;
    private double pitch;
    private double lastError;

    public RotationTracker(RandomGenerator random) {
        this.random = random;
    }

    // ---- configuration ----------------------------------------------------------------------------------------------

    /** Speed limit (degrees per second), never above the hard cap; the acceleration cap scales with it. */
    public void setMaxSpeed(double degPerSec) {
        maxSpeed = Math.min(RotationLimits.MAX_DEG_PER_SEC, Math.max(1.0, degPerSec));
        accel = maxSpeed * ACCEL_PER_SPEED;
    }

    public double maxSpeed() {
        return maxSpeed;
    }

    /** Angular acceleration cap, degrees per second squared. */
    public double maxAccel() {
        return accel;
    }

    /** Amplitude of the smooth reference drift (degrees, 0 = none; capped at 0.5). */
    public void setDriftAmplitude(double deg) {
        driftAmplitude = Math.min(0.5, Math.max(0.0, deg));
    }

    // ---- target samples ---------------------------------------------------------------------------------------------

    /** Starts a new target with no motion estimate (the first sample of a stream). */
    public void reset(float targetYaw, float targetPitch) {
        hasSample = true;
        sampleYaw = targetYaw;
        samplePitch = clampPitch(targetPitch);
        sinceSample = 0.0;
        clearMotion();
        noiseT = random.nextDouble() * 1000.0;
    }

    /**
     * A new target sample, {@code sinceLastSampleSec} after the previous one (0 = the same tick: the sample is
     * replaced and the motion estimate kept).
     */
    public void sample(float targetYaw, float targetPitch, double sinceLastSampleSec) {
        if (!hasSample) {
            reset(targetYaw, targetPitch);
            return;
        }
        double newYaw = sampleYaw + RotationCurve.wrapDegrees((double) targetYaw - sampleYaw);
        double newPitch = clampPitch(targetPitch);
        double dt = sinceLastSampleSec;
        if (dt > MAX_SAMPLE_GAP_SEC) {
            clearMotion();
        } else if (dt > 0.0) {
            double ry = (newYaw - sampleYaw) / dt;
            double rp = (newPitch - samplePitch) / dt;
            if (Math.sqrt(ry * ry + rp * rp) > 2.0 * maxSpeed) {
                clearMotion(); // a jump, not motion: a new target
            } else {
                if (hasRaw) {
                    double ay = (ry - rawYaw) / dt;
                    double ap = (rp - rawPitch) / dt;
                    double a = Math.sqrt(ay * ay + ap * ap);
                    if (a > accel) {
                        double k = accel / a;
                        ay *= k;
                        ap *= k;
                    }
                    accYaw += ACCEL_EST_ALPHA * (ay - accYaw);
                    accPitch += ACCEL_EST_ALPHA * (ap - accPitch);
                }
                rawYaw = ry;
                rawPitch = rp;
                hasRaw = true;
                // the mean rate belongs to the interval's middle: move it on to the sample instant
                rateYaw = ry + accYaw * dt * 0.5;
                ratePitch = rp + accPitch * dt * 0.5;
                double r = Math.sqrt(rateYaw * rateYaw + ratePitch * ratePitch);
                if (r > maxSpeed) {
                    double k = maxSpeed / r;
                    rateYaw *= k;
                    ratePitch *= k;
                }
            }
        }
        sampleYaw = newYaw;
        samplePitch = newPitch;
        sinceSample = 0.0;
    }

    private void clearMotion() {
        hasRaw = false;
        rawYaw = 0.0;
        rawPitch = 0.0;
        rateYaw = 0.0;
        ratePitch = 0.0;
        accYaw = 0.0;
        accPitch = 0.0;
    }

    /** Takes over the camera moving at the given angular velocity (deg/s), e.g. from a curve or the last frame. */
    public void engage(double currentVelYaw, double currentVelPitch) {
        velYaw = Double.isFinite(currentVelYaw) ? currentVelYaw : 0.0;
        velPitch = Double.isFinite(currentVelPitch) ? currentVelPitch : 0.0;
    }

    // ---- motion -----------------------------------------------------------------------------------------------------

    /** One frame from the live camera angle; afterwards read {@link #yaw()}/{@link #pitch()}. */
    public void advance(double dt, float camYaw, float camPitch) {
        yaw = camYaw;
        pitch = camPitch;
        if (!(dt > 0.0)) {
            lastError = errorFrom(camYaw, camPitch);
            return;
        }
        // error and feed-forward at the start of the frame (the camera angle given is the start-of-frame angle),
        // then the reference moves on by dt: comparing against the end-of-frame reference would lead by a frame
        double ffYaw = 0.0;
        double ffPitch = 0.0;
        if (sinceSample < HORIZON_SEC) {
            double bend = sinceSample < ACCEL_HORIZON_SEC ? ACCEL_LEAD * sinceSample : 0.0;
            ffYaw = rateYaw + accYaw * bend;
            ffPitch = ratePitch + accPitch * bend;
        }
        double ey = RotationCurve.wrapDegrees(refYaw() - camYaw);
        double ep = refPitch() - camPitch;
        double e = Math.sqrt(ey * ey + ep * ep);
        lastError = e;
        sinceSample += dt;
        noiseT += dt * DRIFT_FREQUENCY_HZ;

        double wantYaw = ffYaw;
        double wantPitch = ffPitch;
        if (e > 1.0e-9) {
            double c = Math.min(GAIN * e, Math.sqrt(2.0 * BRAKE_FRACTION * accel * e));
            wantYaw += c * ey / e;
            wantPitch += c * ep / e;
        }
        double w = Math.sqrt(wantYaw * wantYaw + wantPitch * wantPitch);
        if (w > maxSpeed) {
            double k = maxSpeed / w;
            wantYaw *= k;
            wantPitch *= k;
        }
        double dvy = wantYaw - velYaw;
        double dvp = wantPitch - velPitch;
        double dv = Math.sqrt(dvy * dvy + dvp * dvp);
        double maxDv = accel * dt;
        if (dv > maxDv) {
            double k = maxDv / dv;
            dvy *= k;
            dvp *= k;
        }
        velYaw += dvy;
        velPitch += dvp;
        yaw = camYaw + velYaw * dt;
        double p = camPitch + velPitch * dt;
        if (p < -90.0 || p > 90.0) {
            p = clampPitch(p);
            velPitch = 0.0;
        }
        pitch = p;
    }

    /** Extrapolated reference yaw (unwrapped, with drift). */
    public double refYaw() {
        return sampleYaw + lead(rateYaw, accYaw) + driftAmplitude * RotationCurve.valueNoise(noiseT);
    }

    /** Extrapolated reference pitch (with drift, clamped). */
    public double refPitch() {
        return clampPitch(samplePitch + lead(ratePitch, accPitch)
                + driftAmplitude * RotationCurve.valueNoise(noiseT + 517.3));
    }

    /** How far the reference has moved on from the newest sample along one axis. */
    private double lead(double rate, double acc) {
        double t = sinceSample < HORIZON_SEC ? sinceSample : HORIZON_SEC;
        double ta = sinceSample < ACCEL_HORIZON_SEC ? sinceSample : ACCEL_HORIZON_SEC;
        return rate * t + 0.5 * ACCEL_LEAD * acc * ta * ta;
    }

    /** Angular distance from the given angles to the current reference. */
    public double errorFrom(float camYaw, float camPitch) {
        double ey = RotationCurve.wrapDegrees(refYaw() - camYaw);
        double ep = refPitch() - camPitch;
        return Math.sqrt(ey * ey + ep * ep);
    }

    public float yaw() {
        return (float) yaw;
    }

    public float pitch() {
        return (float) pitch;
    }

    /** Current commanded angular velocity, degrees per second. */
    public double velYaw() {
        return velYaw;
    }

    public double velPitch() {
        return velPitch;
    }

    /** Error to the reference at the start of the last frame (degrees). */
    public double lastError() {
        return lastError;
    }

    /** Estimated target rate at the newest sample (deg/s). */
    public double rateYaw() {
        return rateYaw;
    }

    public double ratePitch() {
        return ratePitch;
    }

    private static double clampPitch(double p) {
        return p < -90.0 ? -90.0 : (p > 90.0 ? 90.0 : p);
    }
}
