package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.api.SharedGrant;

/**
 * Decisions of the Combat Assistant's co-pilot ({@link CombatSettings.ControlMode#CO_PILOT}): the user drives the
 * movement keys and the mouse, the assistant only adds to that, through a {@link SharedGrant}:
 * <ul>
 *   <li><b>Aim co-steer</b> ({@link #steer()}): a low-authority yaw ease toward the target, {@value #AUTH_MIN} to
 *       {@value #AUTH_MAX} (the higher end only with the weapon charged and the target near reach). Only while the
 *       target is within {@value #STEER_CONE_DEG} degrees of where the user already looks and inside
 *       {@code spacingMax + }{@value #STEER_RANGE_EXTRA} blocks: it never turns the user round to something behind
 *       them, so the view stays along the way they move. At most {@value #WINDOW_BUDGET_DEG} degrees per swing cycle
 *       (or per {@value #WINDOW_MAX_TICKS} ticks). Hand motion against it of more than {@value #OPPOSE_DEG} degrees in
 *       {@value #OPPOSE_WINDOW} ticks lets go for {@value #HOLD_OFF_TICKS} ticks (manual input wins). Pitch is never
 *       touched, and the core refuses any co-steer on the LEGIT level.</li>
 *   <li><b>Spacing nudge</b> ({@link #forward()}): only subtracts. While the user holds W toward the target it holds W
 *       back for a few ticks when that would crowd the target or walk into the opponent's charged hit with our own
 *       weapon still charging; at most {@value #NUDGE_MAX_TICKS} ticks in a row, then {@value #NUDGE_REST_TICKS} free.
 *       A fresh W press during a nudge makes W the user's for {@value #NUDGE_YIELD_TICKS} ticks. It never presses a
 *       key the user did not.</li>
 *   <li><b>Sprint reset</b> ({@link #withholdSprint()}): after a swing in reach (the user's or ours), while the user
 *       is running at the target, sprint is withheld for 1 or 2 ticks so the next hit carries sprint knockback again.
 *       The user's own sprint comes back by itself.</li>
 *   <li><b>Swing</b> ({@link #swing()}): only when the user's own crosshair already rests on the engaged target in
 *       reach, the weapon is charged, no item is in use, no raised shield faces us (unless an axe is in hand), and the
 *       user is not clicking. A short reaction jitter; a rising hop waits for the fall (crit) up to
 *       {@value #MAX_CRIT_WAIT} ticks.</li>
 * </ul>
 * Engine-free: inputs are public fields set each tick, then {@link #decide()}. Allocation free.
 */
public final class CoPilotPlanner {

    /** Co-steer authority (the core's rate and gain scale): low, a hand on the user's, never a takeover. */
    public static final double AUTH_MIN = 0.3;
    public static final double AUTH_MAX = 0.6;
    /** Requested co-steer rate (deg/s at authority 1; the core caps it again and scales it by the authority). */
    public static final double STEER_DEG_PER_SEC = 120.0;
    /** Co-steer only toward a target this far past spacing max (blocks). */
    public static final double STEER_RANGE_EXTRA = 3.0;
    /** ... and within this many degrees of the current view yaw. */
    public static final double STEER_CONE_DEG = 60.0;
    /** Below this yaw error the view is on the target: nothing to add. */
    public static final double ON_TARGET_DEG = 0.5;
    /** Degrees the co-steer may add per window (a swing cycle, or {@link #WINDOW_MAX_TICKS} at the most). */
    public static final double WINDOW_BUDGET_DEG = 20.0;
    public static final int WINDOW_MAX_TICKS = 30;
    /** Hand motion against the co-steer within {@link #OPPOSE_WINDOW} ticks that hands the yaw back. */
    public static final double OPPOSE_DEG = 4.0;
    public static final int OPPOSE_WINDOW = 5;
    public static final int HOLD_OFF_TICKS = 40;
    /** The user moves at the target: the target within this many degrees of the view while W is held. */
    public static final double TOWARD_DEG = 45.0;
    public static final int NUDGE_MAX_TICKS = 6;
    public static final int NUDGE_REST_TICKS = 4;
    public static final int NUDGE_YIELD_TICKS = 20;
    public static final int MAX_CRIT_WAIT = 8;
    /** Charge that counts as "ready" before a swing and as "spent" after one (attack strength). */
    static final float SWING_SPENT = 0.5f;

    // ---- inputs (set every tick before decide) ----------------------------------------------------------------------
    public boolean steerOn = true;
    public boolean spacingOn = true;
    public boolean swingOn = true;
    public CombatSettings.SprintReset sprintReset = CombatSettings.SprintReset.W_RELEASE;
    public boolean critSync = true;
    /** The core's tick counter (the clock of {@link #forwardPressTick}). */
    public long tick;
    /** View yaw and the yaw that faces the target's aim point. */
    public float yaw;
    public float aimYaw;
    /** Horizontal centre distance to the target, and the spacing band. */
    public double distance;
    public double spacingMin = 2.7;
    public double spacingMax = 3.1;
    public boolean lineOfSight = true;
    public boolean inReach;
    public boolean crosshairOnTarget;
    public float attackStrength;
    public boolean usingItem;
    public boolean screenOpen;
    public boolean onGround = true;
    /** Vertical speed (blocks per tick; above 0 while rising). */
    public double verticalSpeed;
    /** A crit is possible this tick or once falling: not sprinting, not in water, not climbing. */
    public boolean critPossible = true;
    public boolean fallingForCrit;
    /** The user holds W (hardware), and the tick of its last hardware press. */
    public boolean forwardHeld;
    public long forwardPressTick = -1L;
    /** The opponent's next hit is charged. */
    public boolean oppCharged;
    /** A raised shield of the target faces us and the held item is no axe: a swing only feeds the shield. */
    public boolean shieldFacing;
    /** The user's own attack or use click is active (the core's WORLD_INTERACT override). */
    public boolean manualClick;
    /** The grant's running sum of hand motion against the co-steer (0 without a grant). */
    public double opposingTotal;
    /** Reaction jitter draw for this tick, 0..2 ticks (used only when a swing or a reset arms). */
    public int jitter;

    // ---- outputs ----------------------------------------------------------------------------------------------------
    private boolean steer;
    private double authority;
    private double allowance;
    private int forward = SharedGrant.KEEP;
    private boolean withholdSprint;
    private boolean swing;

    // ---- state ------------------------------------------------------------------------------------------------------
    private float prevStrength;
    private boolean prevHitLikely;
    private double windowSpent;
    private int windowTicks;
    private final double[] opposingRing = new double[OPPOSE_WINDOW];
    private int opposingAt;
    private int holdOff;
    private int nudgeRun;
    private int nudgeRest;
    private long nudgeSince = -1L;
    private long nudgeYieldUntil = -1L;
    private int resetLeft;
    private boolean swingArmed;
    private int swingDelay;
    private int critWait;
    private int swingsSeen;
    private int nudgeTicks;

    /** A new engagement or a style switch: everything starts over. */
    public void reset() {
        steer = false;
        authority = 0.0;
        allowance = 0.0;
        forward = SharedGrant.KEEP;
        withholdSprint = false;
        swing = false;
        prevStrength = 0f;
        prevHitLikely = false;
        windowSpent = 0.0;
        windowTicks = 0;
        java.util.Arrays.fill(opposingRing, 0.0);
        opposingAt = 0;
        holdOff = 0;
        nudgeRun = 0;
        nudgeRest = 0;
        nudgeSince = -1L;
        nudgeYieldUntil = -1L;
        resetLeft = 0;
        swingArmed = false;
        swingDelay = 0;
        critWait = 0;
    }

    /** Degrees the co-steer added since the last tick (from the grant), counted against the window's budget. */
    public void noteSteerAdded(double deg) {
        if (deg > 0.0 && Double.isFinite(deg)) {
            windowSpent += deg;
        }
    }

    /** The grant started over (its opposing sum is back at 0): the override window starts over with it. */
    public void grantRestarted() {
        java.util.Arrays.fill(opposingRing, 0.0);
    }

    public void decide() {
        float err = AimSolver.yawDelta(aimYaw, yaw);
        double absErr = Math.abs(err);

        // a swing happened (the user's or ours): the charge fell from ready to spent since the last tick
        boolean swung = prevStrength >= SwingGate.READY && attackStrength < SWING_SPENT;
        boolean hitLikely = prevHitLikely;
        prevStrength = attackStrength;
        prevHitLikely = inReach && crosshairOnTarget;
        if (swung) {
            swingsSeen++;
        }

        // ---- co-steer window and override --------------------------------------------------------------------------
        if (swung || ++windowTicks >= WINDOW_MAX_TICKS) {
            windowTicks = 0;
            windowSpent = 0.0;
        }
        double ago = opposingRing[opposingAt];
        opposingRing[opposingAt] = opposingTotal;
        opposingAt = opposingAt + 1 == OPPOSE_WINDOW ? 0 : opposingAt + 1;
        if (opposingTotal - ago > OPPOSE_DEG) {
            holdOff = HOLD_OFF_TICKS; // the user's hand says no: the yaw is theirs for a while
            java.util.Arrays.fill(opposingRing, opposingTotal); // the same flick never counts twice
        } else if (holdOff > 0) {
            holdOff--;
        }
        double near = spacingMax + STEER_RANGE_EXTRA;
        steer = steerOn && !screenOpen && lineOfSight && holdOff == 0 && distance <= near
                && absErr <= STEER_CONE_DEG && absErr > ON_TARGET_DEG && windowSpent < WINDOW_BUDGET_DEG;
        if (steer) {
            // more authority as the swing moment comes: weapon charging toward ready with the target near reach
            double charge = Math.max(0.0, Math.min(1.0, attackStrength));
            double closeness = distance <= spacingMax + 1.0 ? 1.0 : 0.0;
            authority = AUTH_MIN + (AUTH_MAX - AUTH_MIN) * charge * closeness;
            allowance = WINDOW_BUDGET_DEG - windowSpent;
        } else {
            authority = 0.0;
            allowance = 0.0;
        }

        // ---- spacing nudge (W only, subtract only) -----------------------------------------------------------------
        boolean toward = forwardHeld && absErr <= TOWARD_DEG;
        forward = SharedGrant.KEEP;
        if (nudgeRun > 0 && forwardPressTick > nudgeSince) {
            nudgeYieldUntil = tick + NUDGE_YIELD_TICKS; // pressed W again during the nudge: theirs
        }
        boolean want = spacingOn && toward && !screenOpen && lineOfSight
                && (distance < SpacingController.CROWD_DISTANCE
                || (oppCharged && !SwingGate.ready(attackStrength) && distance < spacingMin));
        if (want && tick >= nudgeYieldUntil && nudgeRest == 0 && nudgeRun < NUDGE_MAX_TICKS) {
            if (nudgeRun == 0) {
                nudgeSince = tick;
            }
            nudgeRun++;
            nudgeTicks++;
            forward = SharedGrant.OFF;
            if (nudgeRun == NUDGE_MAX_TICKS) {
                nudgeRest = NUDGE_REST_TICKS;
            }
        } else {
            if (nudgeRun > 0) {
                nudgeRun = 0;
                if (nudgeRest == 0) {
                    nudgeRest = NUDGE_REST_TICKS + 1; // counted down from this tick on
                }
            }
            if (nudgeRest > 0) {
                nudgeRest--;
            }
        }

        // ---- sprint reset ------------------------------------------------------------------------------------------
        if (swung && hitLikely && toward && sprintReset != CombatSettings.SprintReset.OFF) {
            resetLeft = 1 + Math.min(1, Math.max(0, jitter)); // 1 or 2 ticks
        }
        withholdSprint = resetLeft > 0;
        if (resetLeft > 0) {
            resetLeft--;
        }

        // ---- swing -------------------------------------------------------------------------------------------------
        swing = false;
        boolean can = swingOn && crosshairOnTarget && inReach && !usingItem && !screenOpen && !shieldFacing
                && !manualClick && SwingGate.ready(attackStrength);
        if (!can) {
            swingArmed = false;
            critWait = 0;
            return;
        }
        if (!swingArmed) {
            swingArmed = true;
            swingDelay = Math.max(0, Math.min(2, jitter));
        }
        if (swingDelay > 0) {
            swingDelay--;
            return;
        }
        if (critSync && critPossible && !onGround && !fallingForCrit && verticalSpeed > 0.0
                && critWait < MAX_CRIT_WAIT) {
            critWait++; // a rising hop: the hit lands as a crit once it falls
            return;
        }
        swing = true;
        swingArmed = false;
        critWait = 0;
    }

    /** Ease the yaw toward {@link #steerYaw()} this tick. */
    public boolean steer() {
        return steer;
    }

    public float steerYaw() {
        return aimYaw;
    }

    /** 0 when not steering, else {@value #AUTH_MIN}..{@value #AUTH_MAX}. */
    public double authority() {
        return authority;
    }

    /** Degrees the co-steer may still add in this window. */
    public double allowance() {
        return allowance;
    }

    /** {@link SharedGrant#KEEP}, or {@link SharedGrant#OFF} to hold the user's W back this tick. */
    public int forward() {
        return forward;
    }

    public boolean withholdSprint() {
        return withholdSprint;
    }

    /** Tap attack now (the caller gates it on its own lease). */
    public boolean swing() {
        return swing;
    }

    /** Ticks the co-steer is handed back after the user's hand opposed it. */
    public int holdOffTicks() {
        return holdOff;
    }

    /** Swings seen (charge fell from ready to spent), the user's and ours. */
    public int swingsSeen() {
        return swingsSeen;
    }

    /** Ticks a spacing nudge held W back. */
    public int nudgeTicks() {
        return nudgeTicks;
    }

    /** Anything to write to the grant this tick (steer, nudge or sprint withhold). */
    public boolean hasWrites() {
        return steer || forward != SharedGrant.KEEP || withholdSprint;
    }
}
