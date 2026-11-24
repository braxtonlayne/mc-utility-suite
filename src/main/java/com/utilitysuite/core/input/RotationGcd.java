package com.utilitysuite.core.input;

/**
 * Mouse sensitivity quantization (GCD) matching vanilla {@code MouseHandler.turnPlayer} and {@code Entity.turn}.
 * <p>
 * Vanilla maps normalized {@code Options.sensitivity()} {@code s} via {@code f = s * 0.6f + 0.2f} to an angular
 * quantum of {@code f^3 * 8.0 * 0.15} degrees per raw mouse count. Real mouse rotations only emit integer multiples
 * of this step.
 */
public final class RotationGcd {

    /** Degrees per vanilla turn unit ({@code Entity.turn} scales mouse counts by {@code 0.15F}). */
    private static final double TURN_SCALE = 0.15;
    private static final double CURSOR_MULT = 8.0;

    private RotationGcd() {
    }

    /**
     * Angular step size (degrees) for a normalized vanilla mouse sensitivity, or {@code 0.0} when sensitivity is
     * non-finite (quantization disabled, e.g. in unit-test backends that do not specify a sensitivity).
     */
    public static double stepDeg(double sensitivity) {
        if (!Double.isFinite(sensitivity)) {
            return 0.0;
        }
        double s = sensitivity < 0.0 ? 0.0 : sensitivity;
        double f = s * (double) 0.6f + (double) 0.2f;
        return f * f * f * CURSOR_MULT * TURN_SCALE;
    }

    /**
     * Quantizes a rotation step from {@code (fromYaw, fromPitch)} to {@code (toYaw, toPitch)} to integer multiples of
     * {@code stepDeg} (no-op when {@code stepDeg <= 0}). When {@code maxStep > 0}, keeps the quantized step length at
     * most {@code maxStep} so quantization never exceeds {@link RotationLimits}. Writes the result into
     * {@code out[0..1]}.
     */
    public static void quantizeTarget(float fromYaw, float fromPitch, float toYaw, float toPitch,
                                      double stepDeg, double maxStep, float[] out) {
        if (stepDeg <= 0.0 || !Double.isFinite(stepDeg)) {
            out[0] = toYaw;
            out[1] = toPitch;
            return;
        }
        double dy = RotationCurve.wrapDegrees((double) toYaw - fromYaw);
        double dp = (double) toPitch - fromPitch;
        long sy = Math.round(dy / stepDeg);
        long sp = Math.round(dp / stepDeg);
        if (maxStep > 0.0) {
            double max2 = (maxStep + 1.0e-9) * (maxStep + 1.0e-9);
            while (sy != 0 || sp != 0) {
                double qy = sy * stepDeg;
                double qp = sp * stepDeg;
                if (qy * qy + qp * qp <= max2) {
                    break;
                }
                if (Math.abs(sy) >= Math.abs(sp)) {
                    sy += sy > 0 ? -1 : 1;
                } else {
                    sp += sp > 0 ? -1 : 1;
                }
            }
        }
        out[0] = (float) (fromYaw + sy * stepDeg);
        float pitch = (float) (fromPitch + sp * stepDeg);
        out[1] = pitch < -90f ? -90f : (pitch > 90f ? 90f : pitch);
    }

    /**
     * Quantizes an incremental delta {@code deltaDeg + carryDeg} onto integer multiples of {@code stepDeg}. Returns the
     * quantized delta and leaves the remainder update via {@link #remainder(double, double, double)}.
     */
    public static double quantizeDelta(double deltaDeg, double carryDeg, double stepDeg) {
        if (stepDeg <= 0.0 || !Double.isFinite(stepDeg)) {
            return deltaDeg;
        }
        double total = deltaDeg + carryDeg;
        return Math.round(total / stepDeg) * stepDeg;
    }

    /** Remainder carried into the next incremental delta after emitting {@code quantizedDeg}. */
    public static double remainder(double deltaDeg, double carryDeg, double quantizedDeg) {
        return (deltaDeg + carryDeg) - quantizedDeg;
    }
}
