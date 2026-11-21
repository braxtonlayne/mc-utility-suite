package com.utilitysuite.core.input;

/**
 * HARD RULE: no head snapping. Every look change the suite makes, whether on the visible camera (per rendered frame)
 * or on the angles the server sees (per movement packet, SILENT rotations and their release), moves at most
 * {@link #MAX_DEG_PER_SEC}. No setting, legit level, speed multiplier or module can exceed it: {@link InputSmoother}
 * applies this limiter to the final output of every rotation path, after the human-like curve. Only the user's own
 * mouse is exempt.
 * <p>
 * The cap sits at the fast end of deliberate human turning (a quick 180 takes 0.4 s). Larger instantaneous jumps are
 * what reads as a "snap" to the user watching and to other players watching the server-side head.
 */
public final class RotationLimits {

    /** Hard ceiling on angular speed, degrees per second (combined yaw and pitch). */
    public static final double MAX_DEG_PER_SEC = 450.0;
    /** The same cap per client tick (50 ms): the most a server-visible angle may change between two packets. */
    public static final double MAX_DEG_PER_TICK = MAX_DEG_PER_SEC * 0.05;

    private RotationLimits() {
    }

    /**
     * One limited step from (fromYaw, fromPitch) toward (toYaw, toPitch), at most {@code maxStep} degrees along the
     * straight line between them (yaw wrapped the short way). Writes the result into {@code out[0..1]}.
     *
     * @return true when the step reached the target
     */
    public static boolean step(float fromYaw, float fromPitch, float toYaw, float toPitch, double maxStep,
                               float[] out) {
        double dy = RotationCurve.wrapDegrees((double) toYaw - fromYaw);
        double dp = (double) toPitch - fromPitch;
        double d = Math.sqrt(dy * dy + dp * dp);
        if (d <= maxStep || d < 1.0e-6) {
            out[0] = (float) (fromYaw + dy);
            out[1] = toPitch;
            return true;
        }
        double k = maxStep / d;
        out[0] = (float) (fromYaw + dy * k);
        out[1] = (float) (fromPitch + dp * k);
        return false;
    }

    /** Angular distance (degrees, yaw wrapped) between two look directions. */
    public static double distance(float yawA, float pitchA, float yawB, float pitchB) {
        double dy = RotationCurve.wrapDegrees((double) yawB - yawA);
        double dp = (double) pitchB - pitchA;
        return Math.sqrt(dy * dy + dp * dp);
    }
}
