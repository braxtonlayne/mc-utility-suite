package com.utilitysuite.travel.movement;

/**
 * Reads the user's input during a jump commitment (design 2026-11-22, section 4.1) as one of four things: noise
 * (ignored), a trim (the plan bends, the commitment holds), an axis override (that axis is the user's for the rest of
 * the jump) or a hard override (the commitment aborts now). Manual input wins: every threshold only decides when the
 * plan stops counting on an axis, never whether a fresh key may apply.
 * <p>
 * Thresholds scale with the override sensitivity: {@link Sensitivity#LOW} x1.5 (the assist holds on longer),
 * {@link Sensitivity#NORMAL} x1.0, {@link Sensitivity#HIGH} x0.6. Engine-free, allocation free, stateless.
 */
public final class OverrideClassifier {

    public enum Kind { NOISE, TRIM, AXIS_OVERRIDE, HARD_OVERRIDE }

    public enum Sensitivity {
        LOW(1.5), NORMAL(1.0), HIGH(0.6);

        public final double scale;

        Sensitivity(double scale) {
            this.scale = scale;
        }
    }

    /** Mouse yaw accumulated over 4 ticks below this is noise (degrees). */
    public static final double YAW_NOISE_DEG = 1.5;
    /** Mouse yaw more than this off the commit line is a hard override (degrees). */
    public static final double YAW_HARD_OFF_LINE_DEG = 25.0;
    /** Mouse yaw faster than this in one tick is a hard override (degrees). */
    public static final double YAW_HARD_PER_TICK_DEG = 20.0;
    /** Mouse yaw opposing the co-steer by more than this within 5 ticks takes the yaw axis (degrees). */
    public static final double YAW_OPPOSE_DEG = 6.0;
    /** A fresh key held this long becomes an axis override (2 on RELAXED). */
    public static final int AXIS_HOLD_TICKS = 3;
    public static final int AXIS_HOLD_TICKS_RELAXED = 2;
    /** Back held this long against a forward plan aborts. */
    public static final int BACK_ABORT_TICKS = 2;
    /** A strafe tap up to this long is a trim. */
    public static final int TAP_TICKS = 2;

    public Sensitivity sensitivity = Sensitivity.NORMAL;
    /** RELAXED legit level: the assist reacts faster, and so does the axis-override rule. */
    public boolean relaxed;

    private int ticks(int base) {
        return Math.max(1, (int) Math.round(base * sensitivity.scale));
    }

    private double deg(double base) {
        return base * sensitivity.scale;
    }

    public int axisHoldTicks() {
        return ticks(relaxed ? AXIS_HOLD_TICKS_RELAXED : AXIS_HOLD_TICKS);
    }

    /**
     * One movement key.
     *
     * @param down       the key is down this tick
     * @param stale      held without interruption since before TAKEOFF
     * @param heldTicks  ticks the current press has been down (0 when up)
     * @param bounce     released and pressed again within 1 tick
     * @param strafe     a strafe key (left/right)
     * @param airborne   the body is in the air
     * @param forwardReleasedNow the forward key went up this tick (in the air: a trim toward the near part)
     */
    public Kind key(boolean down, boolean stale, int heldTicks, boolean bounce, boolean strafe, boolean airborne,
                    boolean forwardReleasedNow) {
        if (!down) {
            return forwardReleasedNow && airborne ? Kind.TRIM : Kind.NOISE;
        }
        if (stale || bounce) {
            return Kind.NOISE;
        }
        if (heldTicks >= axisHoldTicks()) {
            return Kind.AXIS_OVERRIDE;
        }
        // a fresh press shorter than the override threshold: a strafe tap (up to TAP_TICKS) trims the aim toward that
        // side; any other short press is also only a trim until it has been held long enough
        return Kind.TRIM;
    }

    /**
     * The mouse.
     *
     * @param accum4      |yaw| the hand moved over the last 4 ticks (degrees)
     * @param offLine     |yaw - commit line| (degrees)
     * @param perTick     |yaw| the hand moved this tick (degrees)
     * @param opposing5   degrees the hand moved against the co-steer over the last 5 ticks (0 without co-steer)
     */
    public Kind yaw(double accum4, double offLine, double perTick, double opposing5) {
        if (offLine > deg(YAW_HARD_OFF_LINE_DEG) || perTick > deg(YAW_HARD_PER_TICK_DEG)) {
            return Kind.HARD_OVERRIDE;
        }
        if (opposing5 > deg(YAW_OPPOSE_DEG)) {
            return Kind.AXIS_OVERRIDE;
        }
        if (accum4 < deg(YAW_NOISE_DEG)) {
            return Kind.NOISE;
        }
        return Kind.TRIM;
    }

    /**
     * The rules that end a commitment at once.
     *
     * @param sneakPressed           the user pressed sneak (the documented "let go" key)
     * @param backAgainstForwardTicks ticks back has been held while the plan goes forward
     * @param axesOverridden         axes the user has taken this jump
     * @param external               Stop-All, a screen, the module or mode off, an EMERGENCY or higher MOVEMENT lease,
     *                               a teleport or a world change
     */
    public Kind hard(boolean sneakPressed, int backAgainstForwardTicks, int axesOverridden, boolean external) {
        if (external || sneakPressed || axesOverridden >= 2
                || backAgainstForwardTicks >= ticks(BACK_ABORT_TICKS)) {
            return Kind.HARD_OVERRIDE;
        }
        return Kind.NOISE;
    }

    /** Jump pressed in the air: a chain request, which is a trim. */
    public Kind jumpInAir(boolean freshPress) {
        return freshPress ? Kind.TRIM : Kind.NOISE;
    }

    public static Kind max(Kind a, Kind b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
