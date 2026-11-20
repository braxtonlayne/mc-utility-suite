package com.utilitysuite.core.input;

import java.util.random.RandomGenerator;

/**
 * Pure camera-motion math (no Minecraft classes). Moves (yaw, pitch) from a start to a target along a cubic Bezier
 * with a slight sideways bend, eased acceleration/deceleration, a small overshoot past the target followed by a short
 * eased correction, and subtle value-noise micro-drift that fades to zero at both ends so the target is reached exactly.
 * <p>
 * Yaw takes the shortest arc: the end yaw is {@code startYaw + wrapDegrees(target - startYaw)}, so the produced yaw is
 * continuous (Minecraft yaw is not wrapped) and never turns the long way round. Pitch is clamped to [-90, 90].
 * <p>
 * {@link #start} and {@link #advance} allocate nothing; a combat module may retarget every tick.
 */
public final class RotationCurve {

    // ---- tunables ---------------------------------------------------------------------------------------------------
    private double baseSpeedDegPerSec = 240.0;
    /** Overshoot as a fraction of the move distance, before randomization (x0.5..1.5). */
    private double overshootFraction = 0.035;
    private double maxOvershootDeg = 2.0;
    /** Sideways bend of the Bezier control points as a fraction of the distance (signed random up to this). */
    private double curvatureFraction = 0.12;
    private double driftAmplitudeDeg = 0.12;
    private double driftFrequencyHz = 3.0;
    private double minDurationSec = 0.06;
    private double maxDurationSec = 2.5;

    private final RandomGenerator random;

    // ---- state ------------------------------------------------------------------------------------------------------
    private boolean active;
    private double elapsed;
    private double mainDuration;
    private double correctionDuration;
    private double p0y, p0p, p1y, p1p, p2y, p2p, p3y, p3p;
    private double targetYaw, targetPitch;
    private double yaw, pitch;
    private double noiseOffset;

    public RotationCurve(RandomGenerator random) {
        this.random = random;
    }

    // ---- configuration ----------------------------------------------------------------------------------------------

    public void setBaseSpeed(double degPerSec) {
        this.baseSpeedDegPerSec = Math.max(1.0, degPerSec);
    }

    public double baseSpeed() {
        return baseSpeedDegPerSec;
    }

    public void setMaxOvershoot(double deg) {
        this.maxOvershootDeg = Math.max(0.0, deg);
    }

    public double maxOvershoot() {
        return maxOvershootDeg;
    }

    public void setDriftAmplitude(double deg) {
        this.driftAmplitudeDeg = Math.max(0.0, deg);
    }

    public double driftAmplitude() {
        return driftAmplitudeDeg;
    }

    public void setCurvature(double fraction) {
        this.curvatureFraction = Math.max(0.0, fraction);
    }

    // ---- motion -----------------------------------------------------------------------------------------------------

    /**
     * Starts a motion from the current angles to the target. {@code speedScale} multiplies the base speed.
     */
    public void start(float fromYaw, float fromPitch, float toYaw, float toPitch, float speedScale) {
        double dy = wrapDegrees((double) toYaw - fromYaw);
        targetYaw = fromYaw + dy;
        targetPitch = clampPitch(toPitch);
        double dp = targetPitch - fromPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        yaw = fromYaw;
        pitch = fromPitch;
        elapsed = 0.0;
        if (dist < 1.0e-3) {
            yaw = targetYaw;
            pitch = targetPitch;
            active = false;
            mainDuration = 0.0;
            correctionDuration = 0.0;
            return;
        }
        double ux = dy / dist;
        double uy = dp / dist;
        double nx = -uy;
        double ny = ux;

        double overshoot = Math.min(maxOvershootDeg, dist * overshootFraction * (0.5 + random.nextDouble()));
        p0y = fromYaw;
        p0p = fromPitch;
        p3y = targetYaw + ux * overshoot;
        p3p = targetPitch + uy * overshoot;
        double bend = dist * curvatureFraction * (random.nextDouble() * 2.0 - 1.0);
        double cy = p3y - p0y;
        double cp = p3p - p0p;
        p1y = p0y + cy * 0.33 + nx * bend;
        p1p = p0p + cp * 0.33 + ny * bend;
        p2y = p0y + cy * 0.67 + nx * bend * 0.6;
        p2p = p0p + cp * 0.67 + ny * bend * 0.6;

        double speed = baseSpeedDegPerSec * (speedScale > 0f ? speedScale : 1f);
        mainDuration = clamp(minDurationSec + dist / speed, minDurationSec, maxDurationSec);
        correctionDuration = overshoot > 1.0e-4 ? clamp(0.04 + overshoot / (speed * 0.5), 0.04, 0.3) : 0.0;
        noiseOffset = random.nextDouble() * 1000.0;
        active = true;
    }

    /**
     * Moves the target of a running motion without restarting its easing (for slowly moving targets, e.g. a tracked
     * entity). The Bezier tail is shifted by the target delta; the overshoot point moves with it. If no motion is
     * running this is the same as {@link #start} from the current angles at speed 1.
     */
    public void retarget(float toYaw, float toPitch) {
        if (!active) {
            start((float) yaw, (float) pitch, toYaw, toPitch, 1f);
            return;
        }
        double newYaw = targetYaw + wrapDegrees((double) toYaw - targetYaw);
        double newPitch = clampPitch(toPitch);
        double dy = newYaw - targetYaw;
        double dp = newPitch - targetPitch;
        p1y += dy * 0.33;
        p1p += dp * 0.33;
        p2y += dy * 0.67;
        p2p += dp * 0.67;
        p3y += dy;
        p3p += dp;
        targetYaw = newYaw;
        targetPitch = newPitch;
    }

    /** Advances the motion by {@code dtSeconds}; afterwards read {@link #yaw()}/{@link #pitch()}. */
    public void advance(double dtSeconds) {
        if (!active) {
            return;
        }
        if (dtSeconds > 0) {
            elapsed += dtSeconds;
        }
        if (elapsed < mainDuration) {
            double s = elapsed / mainDuration;
            double e = easeInOutCubic(s);
            double u = 1.0 - e;
            double b0 = u * u * u;
            double b1 = 3.0 * u * u * e;
            double b2 = 3.0 * u * e * e;
            double b3 = e * e * e;
            double envelope = Math.sin(Math.PI * s) * driftAmplitudeDeg;
            double t = noiseOffset + elapsed * driftFrequencyHz;
            yaw = b0 * p0y + b1 * p1y + b2 * p2y + b3 * p3y + envelope * valueNoise(t);
            pitch = clampPitch(b0 * p0p + b1 * p1p + b2 * p2p + b3 * p3p + envelope * valueNoise(t + 517.3));
        } else if (elapsed < mainDuration + correctionDuration) {
            double s = (elapsed - mainDuration) / correctionDuration;
            double e = 0.5 - 0.5 * Math.cos(Math.PI * s);
            yaw = p3y + (targetYaw - p3y) * e;
            pitch = clampPitch(p3p + (targetPitch - p3p) * e);
        } else {
            yaw = targetYaw;
            pitch = targetPitch;
            active = false;
        }
    }

    /** Jumps to the target and stops. */
    public void finish() {
        yaw = targetYaw;
        pitch = targetPitch;
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public float yaw() {
        return (float) yaw;
    }

    public float pitch() {
        return (float) pitch;
    }

    /** Target yaw, unwrapped onto the shortest arc from the start yaw. */
    public float targetYaw() {
        return (float) targetYaw;
    }

    public float targetPitch() {
        return (float) targetPitch;
    }

    /** Angular distance (degrees) between the current sample and the target. */
    public double remainingError() {
        double dy = wrapDegrees(targetYaw - yaw);
        double dp = targetPitch - pitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    /** Total planned duration (main motion plus correction), seconds. */
    public double totalDuration() {
        return mainDuration + correctionDuration;
    }

    // ---- math helpers -----------------------------------------------------------------------------------------------

    /** Wraps to [-180, 180). */
    public static double wrapDegrees(double deg) {
        double w = deg % 360.0;
        if (w >= 180.0) {
            w -= 360.0;
        }
        if (w < -180.0) {
            w += 360.0;
        }
        return w;
    }

    static double easeInOutCubic(double s) {
        return s < 0.5 ? 4.0 * s * s * s : 1.0 - Math.pow(-2.0 * s + 2.0, 3) / 2.0;
    }

    private static double clampPitch(double p) {
        return p < -90.0 ? -90.0 : (p > 90.0 ? 90.0 : p);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Smooth 1-D value noise in [-1, 1]. */
    static double valueNoise(double x) {
        double fl = Math.floor(x);
        long i = (long) fl;
        double f = x - fl;
        double s = f * f * (3.0 - 2.0 * f);
        double a = hashToUnit(i);
        double b = hashToUnit(i + 1);
        return a + (b - a) * s;
    }

    private static double hashToUnit(long n) {
        long h = n * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }
}
