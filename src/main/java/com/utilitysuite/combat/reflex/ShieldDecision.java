package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.api.IInputSmoother;

/**
 * When to raise and lower a shield. Pure and allocation free.
 * <p>
 * A vanilla shield blocks attacks from the front half-space and only after it has been raised for
 * {@value #RAISE_TICKS} ticks, so it goes up that early before the impact. Under Legit it only reacts to a threat that
 * is already inside the front cone; turning to face one is a less-legit option and the turn time (capped at
 * {@link IInputSmoother#MAX_TURN_DEG_PER_SEC}) is added to the lead. It comes down when the user's weapon is charged and
 * a swing is wanted, unless a hit lands within {@value #SWING_WINDOW_TICKS} ticks.
 */
public final class ShieldDecision {

    public enum Act { NONE, RAISE, HOLD, LOWER, TURN_AND_RAISE }

    /** Ticks a raised shield needs before it blocks (vanilla). */
    public static final int RAISE_TICKS = 5;
    /** Reaction margin added to the lead. */
    public static final int MARGIN_TICKS = 2;
    /** Keep blocking instead of swinging when a hit lands within this many ticks. */
    public static final int SWING_WINDOW_TICKS = 3;
    /** Legit front cone half-angle (vanilla blocks up to 90; a smaller cone reads like a player who saw it coming). */
    public static final double DEFAULT_FRONT_CONE_DEG = 70.0;
    /** Threats below this expected damage are ignored (snowballs, eggs). */
    public static final float MIN_DAMAGE = 1.0f;

    private ShieldDecision() {
    }

    /** Ticks a turn of {@code angleDeg} takes at the core's speed cap. */
    public static int turnTicks(double angleDeg) {
        return (int) Math.ceil(Math.abs(angleDeg) / IInputSmoother.MAX_TURN_DEG_PER_SEC * 20.0);
    }

    /**
     * @param hasShield      a shield is usable (offhand shield and a main hand that does nothing on use, or main hand)
     * @param raised         the shield is currently held up by this reflex
     * @param ticksToImpact  most urgent threat's arrival, {@code Integer.MAX_VALUE} for none
     * @param damage         its expected damage
     * @param relYawDeg      its direction relative to the view (0 ahead, NaN unknown)
     * @param frontConeDeg   legit cone half-angle
     * @param mayTurn        less-legit tuning: turning to face a threat outside the cone is allowed
     * @param wantSwing      the weapon is charged and a target is under the crosshair in reach
     */
    public static Act decide(boolean hasShield, boolean raised, int ticksToImpact, float damage, float relYawDeg,
                             double frontConeDeg, boolean mayTurn, boolean wantSwing) {
        boolean threat = ticksToImpact != Integer.MAX_VALUE && damage >= MIN_DAMAGE && !Float.isNaN(relYawDeg);
        if (!hasShield) {
            return raised ? Act.LOWER : Act.NONE;
        }
        if (wantSwing && (!threat || ticksToImpact > SWING_WINDOW_TICKS)) {
            return raised ? Act.LOWER : Act.NONE;
        }
        if (!threat) {
            return raised ? Act.LOWER : Act.NONE;
        }
        double off = Math.abs(relYawDeg);
        boolean inFront = off <= frontConeDeg;
        if (inFront) {
            if (raised) {
                return Act.HOLD;
            }
            return ticksToImpact <= RAISE_TICKS + MARGIN_TICKS ? Act.RAISE : Act.NONE;
        }
        if (mayTurn) {
            int lead = RAISE_TICKS + MARGIN_TICKS + turnTicks(off - frontConeDeg * 0.5);
            if (ticksToImpact <= lead) {
                return Act.TURN_AND_RAISE;
            }
        }
        return raised ? Act.LOWER : Act.NONE;
    }
}
