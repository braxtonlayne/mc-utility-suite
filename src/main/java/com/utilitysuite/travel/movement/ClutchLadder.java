package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.IInputSmoother;

/**
 * Clutch option ladder (idea A6): ranks every way to break a fall that is possible right now, alongside the block and
 * water clutches of {@link FallClutchPlanner}:
 * <ul>
 *   <li>{@link Option#BLOCK}: the planner's placed clutch (water, powder snow, slime, ...);</li>
 *   <li>{@link Option#ELYTRA}: deploy a worn elytra with a jump tap (no turn needed);</li>
 *   <li>{@link Option#WATER_STEER}: air-steer (keys only) into existing water within reach of the fall;</li>
 *   <li>{@link Option#PEARL}: an ender pearl to a ledge; needs a smoothed turn, so it is offered only when the turn at
 *       {@link IInputSmoother#MAX_TURN_DEG_PER_SEC} plus the pearl's flight fit before impact.</li>
 * </ul>
 * Ranked by damage averted, then by time needed (less is better), then by ladder order (cheaper, retrievable first).
 * Engine free; nothing allocated per call: offer the candidates after {@link #begin}, then read {@link #best}.
 */
public final class ClutchLadder {

    public enum Option { BLOCK, WATER_STEER, ELYTRA, PEARL }

    public static final Option[] OPTIONS = Option.values();
    /** Ticks from the jump tap until the elytra has slowed the fall enough to land unhurt. */
    public static final int ELYTRA_DEPLOY_TICKS = 6;
    /** Air acceleration per tick with a strafe key (sprinting in the air). */
    public static final double AIR_ACCEL = 0.026;
    /** Horizontal drag per tick in the air. */
    public static final double AIR_DRAG = 0.91;

    private final boolean[] offered = new boolean[OPTIONS.length];
    private final int[] ticksNeeded = new int[OPTIONS.length];
    private final float[] damageAfter = new float[OPTIONS.length];
    private float landDamage;
    private int ticksToLand;
    private Option best;

    /** Starts a ranking for a fall that lands in {@code ticksToLand} ticks dealing {@code landDamage}. */
    public void begin(int ticksToLand, float landDamage) {
        this.ticksToLand = ticksToLand;
        this.landDamage = landDamage;
        best = null;
        java.util.Arrays.fill(offered, false);
    }

    /** Offers an option; ignored when it cannot finish before impact or does not reduce the damage. */
    public void offer(Option o, int ticks, float after) {
        if (ticks > ticksToLand || after >= landDamage) {
            return;
        }
        int i = o.ordinal();
        offered[i] = true;
        ticksNeeded[i] = ticks;
        damageAfter[i] = Math.max(0f, after);
        if (best == null || better(i, best.ordinal())) {
            best = o;
        }
    }

    private boolean better(int a, int b) {
        if (damageAfter[a] != damageAfter[b]) {
            return damageAfter[a] < damageAfter[b];
        }
        if (ticksNeeded[a] != ticksNeeded[b]) {
            return ticksNeeded[a] < ticksNeeded[b];
        }
        return a < b;
    }

    /** Offers the elytra deploy when one is worn: a jump tap now, gliding well before the ground. */
    public void offerElytra(boolean wornAndUsable) {
        if (wornAndUsable && ticksToLand >= ELYTRA_DEPLOY_TICKS + 1) {
            offer(Option.ELYTRA, 1, 0f);
        }
    }

    /**
     * Offers steering into water when the water's horizontal offset from the predicted landing is within the reach of
     * air strafing over the ticks left (the landing must also end in it: {@code waterDepthOk}).
     */
    public void offerWaterSteer(double waterOffset, boolean waterDepthOk) {
        if (!waterDepthOk) {
            return;
        }
        int ticks = steerTicks(waterOffset, ticksToLand);
        if (ticks >= 0) {
            offer(Option.WATER_STEER, ticks, 0f);
        }
    }

    /**
     * Offers an ender pearl to a ledge: the head turns {@code turnDeg} at no more than {@code degPerSec} (capped at
     * the core's 450 deg/s), one tick to throw, then {@code flightTicks} of pearl flight; all must end before impact.
     * {@code pearlDamage} is the pearl's own 5 damage (less with armor), which the ledge landing still costs.
     */
    public void offerPearl(boolean pearlInHotbar, double turnDeg, double degPerSec, int flightTicks,
                           float pearlDamage) {
        if (!pearlInHotbar) {
            return;
        }
        int ticks = pearlTicks(turnDeg, degPerSec, flightTicks);
        offer(Option.PEARL, ticks, pearlDamage);
    }

    /** Ticks a pearl clutch needs: the capped turn, an ease tick, the throw tick, and the flight. */
    public static int pearlTicks(double turnDeg, double degPerSec, int flightTicks) {
        double rate = Math.min(Math.max(1.0, degPerSec), IInputSmoother.MAX_TURN_DEG_PER_SEC) / 20.0;
        int turn = turnDeg <= 2.0 ? 0 : (int) Math.ceil(turnDeg / rate) + 1;
        return turn + 1 + Math.max(0, flightTicks);
    }

    /**
     * Ticks of strafing needed to shift the landing by {@code offset} blocks (from rest, sideways), or -1 when the
     * ticks left are not enough.
     */
    public static int steerTicks(double offset, int ticksLeft) {
        double need = Math.abs(offset);
        if (need < 0.3) {
            return 0;
        }
        double v = 0.0;
        double moved = 0.0;
        for (int t = 1; t <= ticksLeft; t++) {
            v = (v + AIR_ACCEL) * AIR_DRAG;
            moved += v;
            if (moved >= need) {
                return t;
            }
        }
        return -1;
    }

    /** The best option offered, or null. */
    public Option best() {
        return best;
    }

    public boolean offered(Option o) {
        return offered[o.ordinal()];
    }

    public int ticksNeeded(Option o) {
        return ticksNeeded[o.ordinal()];
    }

    public float damageAfter(Option o) {
        return damageAfter[o.ordinal()];
    }

    /** Damage the best option averts (0 when none). */
    public float damageAverted() {
        return best == null ? 0f : landDamage - damageAfter[best.ordinal()];
    }

    public float landDamage() {
        return landDamage;
    }

    public int ticksToLand() {
        return ticksToLand;
    }
}
