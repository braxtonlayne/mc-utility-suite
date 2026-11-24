package com.utilitysuite.travel.movement;

/**
 * A jump as one commitment (design 2026-11-22, section 4): one locked target held from the approach through the
 * takeoff, the flight, the touchdown and the settle, then handed back over a visible ramp.
 * <pre>
 * IDLE -> ARMED -> TAKEOFF -> FLIGHT -> TOUCHDOWN -> SETTLE -> RELEASE -> IDLE
 *                                          \-> CHAIN (stub this volley: falls through to SETTLE)
 * any state -> ABORT -> RELEASE (2 ticks)
 * </pre>
 * Modes: {@link Mode#NUDGE} "Corrections" never commits (per-tick corrections plus the landing hold);
 * {@link Mode#GUIDE} "Co-pilot" fills free axes with no tick cap, may withhold only stale keys (down since before
 * TAKEOFF) and never replaces a fresh key, not even for a tick; {@link Mode#CARRY} "Takeover" drives the axes, the
 * first ticks of a fresh key are a trim, then the key is the user's.
 * <p>
 * Keys only this volley: no mode writes or asks for a yaw (owner decision D1 is open). Manual input wins: sneak, back
 * held against a forward plan, a turn far off the commit line, two axes taken, Stop-All, a screen, an EMERGENCY or
 * higher MOVEMENT lease, a teleport and a world change abort at once.
 * <p>
 * Key masks by {@code SimKey.ordinal()} ({@link LandingHold#FORWARD} ...). Engine-free, allocation free.
 */
public final class JumpCommit {

    public enum Mode { NUDGE, GUIDE, CARRY }

    public enum Phase { IDLE, ARMED, TAKEOFF, FLIGHT, TOUCHDOWN, SETTLE, CHAIN, RELEASE, ABORT }

    public static final int RELEASE_TICKS_GUIDE = 6;
    public static final int RELEASE_TICKS_CARRY = 8;
    public static final int RELEASE_TICKS_HARD = 2;
    /** GUIDE keeps a departure from the user's input at least this long (no flicker). */
    public static final int GUIDE_MIN_CHOICE_TICKS = 2;
    /** CARRY: the first ticks of a fresh key are a trim; the plan's key still applies. */
    public static final int CARRY_TRIM_TICKS = 2;
    public static final int KEEP = MovementAssistBrain.KEEP;

    public Mode mode = Mode.GUIDE;
    public final OverrideClassifier classifier = new OverrideClassifier();
    /** Per-level key-change limits (LegitPresets): 0 = unlimited. */
    public int maxKeyChanges;
    public int minChangeHoldTicks = 1;
    public int reactionTicks;
    /**
     * The locked top is narrow (a fence, wall or pane post or row, set by the brain each tick). In flight onto it and
     * while the hold settles the body on it, the mouse needs {@link #NARROW_YAW_SCALE} times the usual angle off the line to end the commitment: the user looks
     * down at the post (J19, 2026-11-23: a 24-degree glance aborted it, no brake, no hold, off the post).
     */
    public boolean narrowTarget;
    static final double NARROW_YAW_SCALE = 2.0;

    private Phase phase = Phase.IDLE;
    private boolean touchdownPassed;
    private boolean hardAbort;
    private float commitYaw;
    private int staleMask;
    private int prevKeys;
    private final int[] held = new int[4];
    private final int[] upTicks = new int[4];
    private int backTicks;
    private int axisOverrides; // bit 0 forward axis, bit 1 strafe axis
    private OverrideClassifier.Kind lastKind = OverrideClassifier.Kind.NOISE;
    private boolean overrideThisTick;
    private boolean hardThisTick;
    private int releaseLeft;
    private int releaseTotal;
    private float releaseFrom;
    private int airTicks;
    // key-change limiter / choice keeping
    private int changes;
    private int choiceF = KEEP;
    private int choiceS = KEEP;
    private int choiceAge;

    /**
     * One tick of the state machine. Call before {@link #resolve}.
     *
     * @param onGround    body on the ground this tick
     * @param tookOff     left the ground on this tick
     * @param touchedDown touched down on this tick
     * @param keys        the user's raw keys (mask, jump included)
     * @param yaw         camera yaw
     * @param yawPerTick  the hand's yaw this tick (degrees)
     * @param planLands   a target is locked and the plan lands on it
     * @param narrow      on the touchdown tick: the landing is narrow (the hold takes it)
     * @param holdActive  the landing hold is on
     * @param planForward forward axis of the plan this tick (for "back against a forward plan")
     * @param external    an outside abort: Stop-All, screen, lease, teleport, world change, module off
     */
    public void tick(boolean onGround, boolean tookOff, boolean touchedDown, int keys, boolean userSneak, float yaw,
                     double yawPerTick, boolean planLands, boolean narrow, boolean holdActive, int planForward,
                     boolean external) {
        overrideThisTick = false;
        hardThisTick = false;
        touchdownPassed = false;
        trackKeys(keys);
        if (mode == Mode.NUDGE) {
            phase = Phase.IDLE;
            prevKeys = keys;
            return;
        }
        if (phase != Phase.IDLE && phase != Phase.RELEASE && phase != Phase.ABORT) {
            int move = keys & LandingHold.MOVE_MASK;
            if ((keys & LandingHold.BACK) != 0 && (staleMask & LandingHold.BACK) == 0 && planForward > 0) {
                backTicks++;
            } else {
                backTicks = 0;
            }
            double off = Math.abs(wrap(yaw - commitYaw));
            boolean lineSet = phase != Phase.ARMED;
            OverrideClassifier.Kind k = classifier.hard(userSneak, backTicks, Integer.bitCount(axisOverrides),
                    external);
            if (lineSet) {
                double offLine = narrowTarget && (phase == Phase.TAKEOFF || phase == Phase.FLIGHT
                        || phase == Phase.TOUCHDOWN || phase == Phase.SETTLE)
                        ? off / NARROW_YAW_SCALE : off;
                k = OverrideClassifier.max(k, classifier.yaw(Math.abs(yawPerTick) * 4.0, offLine, Math.abs(yawPerTick),
                        0.0));
            } else if (userSneak || external) {
                k = OverrideClassifier.Kind.HARD_OVERRIDE;
            }
            // per-key axis overrides (fresh keys held long enough)
            for (int i = 0; i < 4; i++) {
                int bit = 1 << i;
                boolean down = (move & bit) != 0;
                OverrideClassifier.Kind kk = classifier.key(down, (staleMask & bit) != 0, held[i],
                        down && held[i] == 1 && upTicks[i] == 1, i >= 2, !onGround,
                        i == 0 && !down && (prevKeys & bit) != 0);
                if (kk == OverrideClassifier.Kind.AXIS_OVERRIDE && (phase == Phase.FLIGHT || phase == Phase.TAKEOFF)) {
                    int axis = i < 2 ? 1 : 2;
                    if ((axisOverrides & axis) == 0) {
                        axisOverrides |= axis;
                        overrideThisTick = true;
                        lastKind = kk;
                    }
                }
            }
            if (Integer.bitCount(axisOverrides) >= 2) {
                k = OverrideClassifier.Kind.HARD_OVERRIDE;
            }
            if (k == OverrideClassifier.Kind.HARD_OVERRIDE) {
                abort();
                prevKeys = keys;
                return;
            }
        } else if (external || userSneak) {
            if (phase == Phase.RELEASE || phase == Phase.ABORT) {
                abort();
            }
        }
        switch (phase) {
            case IDLE -> {
                if (onGround && planLands && !tookOff && wantsCommit(keys)) {
                    phase = Phase.ARMED;
                    commitYaw = yaw;
                } else if (tookOff && planLands && wantsCommit(prevKeys | keys)) {
                    startTakeoff(keys, yaw); // the jump came before the approach window saw it
                }
            }
            case ARMED -> {
                if (tookOff) {
                    startTakeoff(keys, yaw);
                } else if (!planLands || !onGround) {
                    phase = Phase.IDLE; // the target is lost or the plan no longer lands: no hold, nothing to hand back
                } else {
                    commitYaw = yaw;
                }
            }
            case TAKEOFF, FLIGHT -> {
                if (touchedDown || onGround) {
                    touchdown(narrow, holdActive);
                } else if (!planLands && phase == Phase.FLIGHT) {
                    abort();
                } else {
                    phase = Phase.FLIGHT;
                    airTicks++;
                }
            }
            case SETTLE -> {
                if (!holdActive) {
                    startRelease(releaseTicks());
                }
            }
            case RELEASE -> {
                if (--releaseLeft <= 0) {
                    phase = Phase.IDLE;
                    releaseLeft = 0;
                    clearJump();
                }
            }
            case ABORT -> {
                float from = releaseFrom;
                startRelease(RELEASE_TICKS_HARD);
                releaseFrom = from;
            }
            default -> {
            }
        }
        prevKeys = keys;
    }

    private boolean wantsCommit(int keys) {
        // CARRY commits while a movement key is held toward the target; GUIDE when the user jumps or runs at it
        return (keys & LandingHold.MOVE_MASK) != 0;
    }

    private void startTakeoff(int keys, float yaw) {
        phase = Phase.TAKEOFF;
        commitYaw = yaw;
        staleMask = prevKeys & keys & LandingHold.MOVE_MASK; // down before the takeoff tick and still down
        hardAbort = false;
        axisOverrides = 0;
        backTicks = 0;
        airTicks = 0;
        changes = 0;
        choiceF = KEEP;
        choiceS = KEEP;
        choiceAge = 0;
    }

    private void touchdown(boolean narrow, boolean holdActive) {
        touchdownPassed = true;
        phase = Phase.TOUCHDOWN;
        if (narrow && holdActive) {
            phase = Phase.SETTLE;
        } else {
            startRelease(releaseTicks());
        }
    }

    private void startRelease(int ticks) {
        releaseFrom = authority();
        phase = Phase.RELEASE;
        releaseLeft = ticks;
        releaseTotal = ticks;
    }

    /** Abort now: manual input wins. */
    public void abort() {
        if (phase == Phase.IDLE) {
            return;
        }
        hardThisTick = true;
        overrideThisTick = true;
        hardAbort = true;
        lastKind = OverrideClassifier.Kind.HARD_OVERRIDE;
        releaseFrom = phase == Phase.RELEASE || phase == Phase.ABORT ? authority() : flightAuthority();
        phase = Phase.ABORT;
        axisOverrides = 3;
    }

    private void clearJump() {
        staleMask = 0;
        axisOverrides = 0;
        backTicks = 0;
    }

    /** Forget everything (teleport, world change): no hand-back. */
    public void reset() {
        phase = Phase.IDLE;
        releaseLeft = 0;
        releaseTotal = 0;
        hardAbort = false;
        clearJump();
        prevKeys = 0;
        for (int i = 0; i < 4; i++) {
            held[i] = 0;
            upTicks[i] = 0;
        }
    }

    private void trackKeys(int keys) {
        for (int i = 0; i < 4; i++) {
            int bit = 1 << i;
            if ((keys & bit) != 0) {
                held[i]++;
            } else {
                if (held[i] > 0) {
                    upTicks[i] = 0;
                }
                held[i] = 0;
                upTicks[i]++;
                staleMask &= ~bit; // released: its next press is fresh
            }
        }
    }

    private int releaseTicks() {
        return mode == Mode.CARRY ? RELEASE_TICKS_CARRY : RELEASE_TICKS_GUIDE;
    }

    // ---- output resolution --------------------------------------------------------------------------------------------

    /**
     * The axis value to apply, from the user's axis and the plan's, under the mode's rules. Returns {@link #KEEP} when
     * the user's own value stands.
     *
     * @param axis 0 forward/back, 1 strafe
     * @param user the user's axis (-1, 0, 1)
     * @param plan the plan's axis, or {@link #KEEP} for no opinion
     */
    public int resolveAxis(int axis, int user, int plan) {
        if (plan == KEEP || !driving()) {
            return KEEP;
        }
        int axisBit = axis == 0 ? 1 : 2;
        if ((axisOverrides & axisBit) != 0) {
            return KEEP; // that axis is the user's for the rest of the jump
        }
        if (user == 0) {
            return plan == 0 ? KEEP : plan; // free axis: fill
        }
        int keyIdx = axis == 0 ? (user > 0 ? 0 : 1) : (user > 0 ? 2 : 3);
        boolean stale = (staleMask & (1 << keyIdx)) != 0;
        if (mode == Mode.GUIDE) {
            if (!stale || plan == user) {
                return KEEP; // a fresh key is never replaced, not for a tick
            }
            return 0; // a stale key may be withheld, never turned around
        }
        // CARRY
        if (!stale && held[keyIdx] > CARRY_TRIM_TICKS) {
            return KEEP; // from the third tick a fresh key is the user's
        }
        return plan == user ? KEEP : plan;
    }

    /**
     * Applies the per-level key-change limits (at most {@link #maxKeyChanges} departures per jump, each kept at least
     * {@link #minChangeHoldTicks}, none before {@link #reactionTicks} air ticks) and GUIDE's minimum choice time to a
     * resolved pair (each {@link #KEEP} or a value). Read {@link #limitedForward()} / {@link #limitedStrafe()} after.
     * A kept earlier choice is re-resolved, so a fresh key still wins.
     */
    public void limit(int f, int s, int userF, int userS) {
        boolean depart = f != KEEP || s != KEEP;
        int minHold = Math.max(minChangeHoldTicks, mode == Mode.GUIDE ? GUIDE_MIN_CHOICE_TICKS : 1);
        if (!depart && (choiceF != KEEP || choiceS != KEEP) && choiceAge < minHold) {
            // keep the earlier choice a little longer, re-resolved so a fresh key still wins
            f = resolveAxis(0, userF, choiceF);
            s = resolveAxis(1, userS, choiceS);
            depart = f != KEEP || s != KEEP;
        } else if (depart && airTicks < reactionTicks && phase == Phase.FLIGHT) {
            f = KEEP; // the reaction delay of the level: nothing changes this early in the flight
            s = KEEP;
            depart = false;
        } else if (depart && (f != choiceF || s != choiceS)) {
            if (choiceAge < minHold && (choiceF != KEEP || choiceS != KEEP)) {
                f = resolveAxis(0, userF, choiceF);
                s = resolveAxis(1, userS, choiceS);
            } else if (maxKeyChanges > 0 && changes >= maxKeyChanges) {
                f = KEEP;
                s = KEEP;
                depart = false;
            } else {
                changes++;
                choiceAge = 0;
            }
        }
        if (depart) {
            choiceF = f;
            choiceS = s;
            choiceAge++;
        } else {
            choiceF = KEEP;
            choiceS = KEEP;
            choiceAge = 0;
        }
        outF = f;
        outS = s;
    }

    private int outF = KEEP;
    private int outS = KEEP;

    public int limitedForward() {
        return outF;
    }

    public int limitedStrafe() {
        return outS;
    }

    // ---- state ------------------------------------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** The commitment holds the axes: TAKEOFF and FLIGHT (fill/withhold rules apply). */
    public boolean driving() {
        return phase == Phase.TAKEOFF || phase == Phase.FLIGHT;
    }

    /** Anything to publish (not IDLE). */
    public boolean live() {
        return phase != Phase.IDLE;
    }

    /** The touchdown was passed on the last tick (TOUCHDOWN is a same-tick state). */
    public boolean touchdownPassed() {
        return touchdownPassed;
    }

    public boolean overrideThisTick() {
        return overrideThisTick;
    }

    public boolean hardOverrideThisTick() {
        return hardThisTick;
    }

    public OverrideClassifier.Kind lastOverride() {
        return lastKind;
    }

    public int staleMask() {
        return staleMask;
    }

    public float commitYaw() {
        return commitYaw;
    }

    public int releaseLeft() {
        return phase == Phase.RELEASE ? releaseLeft : 0;
    }

    public int releaseTotal() {
        return phase == Phase.RELEASE ? releaseTotal : 0;
    }

    public int keyChanges() {
        return changes;
    }

    private float flightAuthority() {
        return flightAuthorityOf(mode);
    }

    /** Co-steer share in the air for a mode (the core scales the follower by it). */
    public static float flightAuthorityOf(Mode m) {
        return m == Mode.CARRY ? 1.0f : 0.6f;
    }

    /** Co-steer share while ARMED (on the ground before the takeoff) for a mode. */
    public static float armedAuthorityOf(Mode m) {
        return flightAuthorityOf(m) * 0.5f;
    }

    /** Share of the control the assist holds, 0..1; ramps down smoothly through RELEASE. */
    public float authority() {
        switch (phase) {
            case IDLE:
                return 0f;
            case ARMED:
                return armedAuthorityOf(mode);
            case ABORT:
                return releaseFrom;
            case RELEASE: {
                if (releaseTotal <= 0) {
                    return 0f;
                }
                float t = (float) releaseLeft / releaseTotal;
                return releaseFrom * t * t * (3f - 2f * t);
            }
            default:
                return flightAuthority();
        }
    }

    /** Edge-guard horizon of the hand-back (design 5.3): 2 + 4a simulated ticks, 0 after a hard override. */
    public int edgeGuardHorizon() {
        if (phase != Phase.RELEASE || hardAbort) {
            return 0;
        }
        return (int) Math.round(2 + 4 * authority());
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
