package com.utilitysuite.travel.movement;

/**
 * The per-tick decision of the movement assist, engine-free so tests (and automated drivers) can call it. It never
 * fights the user: it only adds a jump, a sneak tap on a landing, and only fills a key axis (forward/back, left/right)
 * the user is not pressing. It never writes a rotation.
 * <ul>
 *   <li><b>Edge jump</b> (on the ground, moving forward): jump on the last tick before walking off when that lands
 *       better than dropping ({@link JumpSearch#shouldEdgeJump}).</li>
 *   <li><b>Tick phase</b> ({@link TakeoffTiming}): one tick before that, jump early when jumping now at the current
 *       yaw beats jumping next tick at the yaw the user's mouse is turning to. Only with an intent target or a moving
 *       mouse, so a still mouse with no target behaves exactly as before.</li>
 *   <li><b>Sprint hop</b> (optional speed mode): on open flat ground a sprint jump covers more distance than
 *       sprinting; jump whenever the jump lands at the same height and gets farther.</li>
 *   <li><b>Air steer</b> (airborne): if the user's keys miss the landing, take damage, land much lower than the best
 *       keys would, or (with a target) miss the target block, switch to the best keys on the free axes.</li>
 *   <li><b>Intent target</b> ({@link #setIntentTarget}): when the intent estimator names a block, the search aims the
 *       landing at its top instead of at progress along the heading, and the brain searches the ideal takeoff yaw
 *       (a few candidates per tick) for the turn gain. Without a target nothing changes.</li>
 *   <li><b>Landing hold</b> ({@link LandingHold}, {@link #sneak()}, every control mode): on a narrow landing sneak goes
 *       down from the last air tick and every key held through the flight is latched off until released.</li>
 *   <li><b>Jump commitment</b> ({@link JumpCommit}, {@link #mode}): in GUIDE ("Co-pilot") and CARRY ("Takeover") a
 *       jump with a locked target is one commitment: free axes filled with no tick cap, stale keys withheld for the
 *       air brake ({@link FlightController}), a gradual hand-back. NUDGE ("Corrections") is the per-tick path.</li>
 *   <li><b>Jump buffer</b> ({@link JumpBuffer}, opt-in): a jump pressed a tick or two before the edge fires on the last
 *       ground tick.</li>
 * </ul>
 * Every correction asks the {@link AuthorshipBudget} first. Results also carry the predicted landing for the overlay.
 * Allocation free; at most {@link #MAX_SIM_TICKS} physics ticks simulated per decision.
 */
public final class MovementAssistBrain {

    /** Air steering corrects only when the best keys beat the user's by this much (blocks of score). */
    public static final double STEER_MARGIN = 0.75;
    /** Sprint hop jumps only when the jump gains at least this much progress over the same ticks. */
    public static final double HOP_GAIN = 0.15;
    public static final int KEEP = Integer.MIN_VALUE;
    /** Upper bound on physics ticks one {@link #decide} simulates (tested). */
    public static final int MAX_SIM_TICKS = 1800;
    /** Horizontal speed (blocks/tick) below which the body counts as stable on its landing. */
    public static final double STABLE_SPEED = SupportRegion.STABLE_SPEED;
    /** Bound on the commitment's own simulated ticks per decision, on top of {@link #MAX_SIM_TICKS} (tested). */
    public static final int MAX_COMMIT_SIM_TICKS = FlightController.MAX_SIM_TICKS;
    /** Mouse yaw rate (deg/tick) below which the tick-phase check is skipped when there is no target. */
    public static final double PHASE_MIN_RATE = 0.5;
    /** Predicted miss (blocks) below which no turn gain is asked for. */
    public static final double TURN_GAIN_MIN_MISS = 0.1;
    /** Yaw offsets (degrees, from the yaw to the target) tried for the ideal takeoff yaw, a couple per tick. */
    static final float[] YAW_OFFSETS = {0f, -2f, 2f, -4f, 4f, -7f, 7f, -11f, 11f, -16f, 16f, -1f, 1f, -22f, 22f};
    static final int YAW_CANDIDATES_PER_TICK = 2;

    public boolean edgeJump = true;
    public boolean airSteer = true;
    public boolean sprintHop = false;
    /** Follow the intent target when one is set (setting "Follow my intent"). */
    public boolean intentAssist = true;
    /**
     * Landing hold (setting {@code landing_hold}, on by default in the module). Off in a bare brain so the scripted
     * always-forward drivers (benchmark, parity with the earlier brain) keep their meaning: a driver that never
     * releases W would be held on the first narrow top, which is exactly what the hold is for.
     */
    public boolean landingHold;
    /** Control mode; NUDGE in a bare brain (the per-tick path the parity tests pin), CO_PILOT from the module. */
    public JumpCommit.Mode mode = JumpCommit.Mode.NUDGE;
    /** Tick-phase check before the edge. */
    public boolean tickPhase = true;
    /** Shape short takeoffs ({@link TakeoffShaper}): strafe key, sprint timing, a turn-gain yaw. Needs a target. */
    public boolean takeoffShaping = true;
    /**
     * Shapes that need the view turned may be offered (as a turn-gain ideal yaw only). The module sets it from the
     * turn-gain setting and the legit level (off on Legit and under automation); off by default.
     */
    public boolean shapeYawAllowed;
    /**
     * Sprint may be engaged for the user when they are not sprinting (the module clears it when vanilla could not
     * start a sprint: hunger, an item in use, blindness, sneaking).
     */
    public boolean sprintFillAllowed = true;
    /**
     * SafeWalk is on: an assisted jump is valid only if it lands without costing health (otherwise SafeWalk holds the
     * edge). The user pressing jump themselves always goes through: that is their call.
     */
    public boolean safeOnly;
    /** Set by decide: at an edge where neither walking off nor jumping lands (a block might still save a jump). */
    private boolean edgeNoLanding;

    private final JumpSearch search = new JumpSearch();
    private final MovementPhysics a = new MovementPhysics();
    private final MovementPhysics b = new MovementPhysics();
    private final MovementPhysics cont = new MovementPhysics();
    private final TakeoffTiming timing = new TakeoffTiming();
    private final TakeoffShaper shaper = new TakeoffShaper();
    private final AuthorshipBudget budget = new AuthorshipBudget();
    private final JumpBuffer jumpBuffer = new JumpBuffer();
    private final LandingHold hold = new LandingHold();
    private final JumpCommit commit = new JumpCommit();
    private final FlightController flight = new FlightController();
    /** Region of the top the body landed on (the hold's stability check). */
    private final SupportRegion landRegion = new SupportRegion();
    /** Region of the locked target's real top (flight plan, mark). */
    private final SupportRegion targetRegion = new SupportRegion();
    private boolean targetRegionValid;
    /** The target block's own top only (no neighbouring tops at the same height). */
    private final SupportRegion targetCell = new SupportRegion();
    /** Region the flight and approach plans land on: the whole top when it is wide, else the target's own top. */
    private SupportRegion planRegion = targetRegion;
    private boolean targetSafe;
    private boolean userSneak;
    private boolean externalAbort;
    private boolean lastPlanLands;
    private int lastPlanForward = KEEP;
    private int commitSimTicks;
    private int jumpsSeen;
    private int baseReactionTicks;

    // intent target (from the estimator or a test)
    private boolean intentSet;
    private int itx, ity, itz;
    private double intentConfidence;
    private double yawRate;

    // ideal takeoff yaw search state
    private boolean idealValid;
    private float idealYaw;
    private double idealScore = JumpSearch.NO_LANDING;
    private double idealMiss = JumpSearch.MISS_NO_LANDING;
    private int yawCursor;
    private boolean hasIdealYaw;
    private double missNow;

    // jump tracking (authorship, landing hold, commitment)
    private boolean prevOnGround = true;
    private boolean prevAssistedJump;
    private double prevFall;
    private boolean tookOff, touchedDown;

    // outputs
    private boolean jump;
    private boolean earlyJump;
    private int forward = KEEP;
    private int strafe = KEEP;
    private boolean corrected;
    private boolean sneak;
    private boolean withholdJump;
    private boolean sprint;
    private boolean shaped;
    private boolean hasLanding;
    private double landX, landY, landZ;
    private float landDamage;
    private boolean lethal;
    private int simTicks;

    /**
     * @param userForward the user's forward axis this tick (-1, 0, 1)
     * @param userStrafe  the user's strafe axis (+1 left, -1 right, 0)
     */
    public void decide(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                       boolean userJump, float health, MovementPhysics.Voxels w) {
        search.resetTickCount();
        jump = false;
        earlyJump = false;
        forward = KEEP;
        strafe = KEEP;
        corrected = false;
        edgeNoLanding = false;
        sneak = false;
        withholdJump = false;
        sprint = false;
        shaped = false;
        sprintOff = false;
        coSteer = false;
        commitSimTicks = 0;
        trackJump(now);
        updateFreeze(now, commit.phase());
        // the user means to sprint: sprinting, or was sprinting until the assist's own eased tick / withheld sprint
        // (vanilla ends the sprint on a tick without forward; with the sprint key held it comes back with W)
        if (sprinting) {
            sprintIntent = true;
            sprintGap = 0;
        } else if (userForward <= 0 || !(approach.engaged() || commit.live() || scriptOn)
                || ++sprintGap > SPRINT_INTENT_TICKS) {
            sprintIntent = false; // a sprint that stays off longer than any eased or walking run-up was let go
        }
        boolean sprintKey = sprinting || sprintIntent;
        if (touchedDown) {
            landedSprinting = sprintKey;
        }
        int keys = LandingHold.keys(userForward, userStrafe, userJump);
        // landing hold first (every mode): the latch decides which of the user's keys count this tick
        updateHold(now, yaw, sprinting, userForward, userStrafe, keys, health, w);
        int rawForward = userForward;
        int rawStrafe = userStrafe;
        if (hold.latched() != 0) {
            userForward = hold.forwardAxis(keys);
            userStrafe = hold.strafeAxis(keys);
        }
        boolean targetOn = intentActive();
        if (targetOn) {
            search.setTarget(itx, ity, itz);
            updateTargetTop(w);
        } else {
            search.clearTarget();
            targetRegionValid = false;
            targetSafe = false;
        }
        commit.mode = mode;
        commit.narrowTarget = targetOn && targetRegionValid
                && (targetRegion.topBound(2) - targetRegion.topBound(0) < NARROW_TOP
                || targetRegion.topBound(3) - targetRegion.topBound(1) < NARROW_TOP);
        commit.reactionTicks = baseReactionTicks <= 0 ? 0 : baseReactionTicks - 1 + jumpsSeen % 3;
        boolean planOnGround = now.onGround && targetOn && targetSafe;
        commit.tick(now.onGround, tookOff, touchedDown, keys, userSneak, yaw, yawRate,
                now.onGround ? planOnGround : lastPlanLands && targetSafe, hold.startedThisTick(), hold.active(),
                lastPlanForward, externalAbort);
        userSneak = false;
        externalAbort = false;
        if (commit.phase() == JumpCommit.Phase.TAKEOFF) {
            jumpsSeen++;
        }
        // jump buffer (opt-in): a press just before the edge is held back to the last ground tick
        boolean jumpIn = userJump;
        if (jumpBuffer.enabled()) {
            int tte = now.onGround
                    ? search.ticksToEdge(now, userForward, userStrafe, yaw, sprinting, jumpBuffer.maxTicks(), w) : -1;
            boolean out = jumpBuffer.update(userJump, now.onGround, tte);
            withholdJump = userJump && !out;
            if (out && !userJump) {
                jump = true; // the buffered press fires after the key came back up
            }
            jumpIn = userJump || out || jumpBuffer.pending();
        }
        if (targetOn && (now.onGround || now.vy > 0)) {
            updateIdealYaw(now, yaw, sprinting, userForward, userStrafe, health, w);
        } else {
            hasIdealYaw = false;
            missNow = 0.0;
        }
        // approach and takeoff control: ARMED on the ground with a locked, safe target (GUIDE / CARRY only)
        boolean armed = mode != JumpCommit.Mode.NUDGE && now.onGround && commit.phase() == JumpCommit.Phase.ARMED
                && targetOn && targetRegionValid && targetSafe && userForward > 0 && hold.latched() == 0;
        userJumpNow = userJump;
        // a target whose straight line is blocked (a corner): no plain takeoff plan can land it, so the technique
        // search gets the approach ticks and the budget from the first ARMED tick
        techniqueFirst = armed && lineBlocked && techniques != TechniqueSource.NONE;
        if (armed && approachControl && !techniqueFirst) {
            int edge = approach.engaged() ? -1
                    : search.ticksToEdge(now, userForward, userStrafe, yaw, sprintKey, ApproachController.WINDOW_TICKS,
                    w);
            // SafeWalk: the assist's own takeoff only onto a top it reaches without fall damage
            boolean assistMayJump = edgeJump && (!safeOnly || planRegion.top() >= now.y - SAFE_DROP);
            approach.update(now, yaw, sprintKey, userForward, userStrafe, userJump, assistMayJump,
                    mode == JumpCommit.Mode.CARRY, sprintWithholdAvailable, commit.reactionTicks,
                    commit.maxKeyChanges, edge, planRegion, w);
            commitSimTicks += approach.simTicks();
        } else {
            approach.reset();
        }
        updateScript(now, yaw, sprintKey, userForward, userStrafe, armed, health, w);
        if (scriptOn) {
            if (!now.onGround || script.jump[Math.min(scriptIndex, script.length - 1)]) {
                heldPress = 0; // the held-back press is the script's takeoff now
            } else if (heldPress > 0) {
                heldPress++;
            }
            replayScript(now, sprinting, sprintKey, userForward, userStrafe, health, w);
            if (now.onGround && withholdJump && heldPress == 0) {
                heldPress = 1; // the script holds the user's press: counted, so a dropped script still lets it go
            }
            if (now.onGround && !jump && (heldPress > 0 || withholdJump)) {
                releaseBeforeSupportEnds(now, yaw, sprinting, userForward, userStrafe, w);
            }
        } else if (now.onGround) {
            decideGround(now, yaw, sprinting, userForward, userStrafe, jumpIn, health, w);
            lastPlanLands = planOnGround;
            lastPlanForward = KEEP;
            releaseEdgeGuard(now, yaw, sprinting, rawForward, rawStrafe, keys, w);
        } else if (mode != JumpCommit.Mode.NUDGE && commit.driving()) {
            heldPress = 0; // airborne: a held press can no longer take off (and must not fire on the next landing)
            decideAirCommitted(now, yaw, sprintKey, userForward, userStrafe, health, w);
        } else {
            heldPress = 0;
            decideAir(now, yaw, sprinting, userForward, userStrafe, health, w);
        }
        // co-steer while ARMED: the ideal takeoff yaw (the sprint-jump boost goes along the yaw of the takeoff tick)
        // (not while a script owns the view, and not at a blocked line: the straight aim at a target behind a wall
        // only moves the run-up the technique search is rolling from)
        if (coSteerAllowed && !coSteer && armed && hasIdealYaw && !scriptOn && !techniqueFirst) {
            coSteer = true;
            coSteerYaw = naturalYaw(idealYaw, now, yaw);
        }
        // the latch outranks every fill: a key held through the flight never moves the body off a narrow top
        if (hold.latched() != 0) {
            int lf = hold.latched() & (LandingHold.FORWARD | LandingHold.BACK);
            int ls = hold.latched() & (LandingHold.LEFT | LandingHold.RIGHT);
            if (lf != 0) {
                forward = userForward;
            }
            if (ls != 0) {
                strafe = userStrafe;
            }
            corrected = true;
        }
        if (hold.sneak()) {
            sneak = true;
            corrected = true;
        }
        // where the view was when this commitment first asked for a co-steer (the allowance is counted from there)
        if (!commit.live() && !coSteer) {
            steerBaseSet = false;
        } else if (coSteer && !steerBaseSet) {
            steerBaseSet = true;
            steerBaseYaw = yaw;
        }
        prevAssistedJump = jump && !jumpBuffer.fired();
        simTicks = search.tickCount();
    }

    // ---- landing hold -------------------------------------------------------------------------------------------------

    private void updateHold(MovementPhysics now, float yaw, boolean sprinting, int f, int s, int keys, float health,
                            MovementPhysics.Voxels w) {
        holdHealth = health;
        updateHoldState(now, yaw, sprinting, f, s, keys, w);
        // Owner, 2026-11-22: the hold is for risky landings only and must never leave a held key dead until it is
        // pressed again. A still-held key works again when carrying on with it is safe: at once when the user shows
        // they are going on (looking past this block), otherwise once the hold has run its settle time.
        if (hold.latched() != 0 && now.onGround && (!hold.active() || (holdStable && continuing(now, keys)))
                && (keys & hold.latched()) != 0 && carryOnIsSafe(now, yaw, sprinting || resumesSprint(), f, s, health,
                w)) {
            hold.release();
        }
    }

    /** The body was sprinting (or meant to) when it touched down for the current hold. */
    private boolean landedSprinting;

    /**
     * The hold itself ends the sprint: it latches W, and vanilla drops the sprint on a tick without forward. With the
     * sprint key still down the sprint is back one tick after W works again, so "is it safe to carry on" is judged
     * with the sprint the body landed with while the latched key is the forward key. Judged at walking speed, a strip
     * whose next jump needs the sprint never counted as safe and the held W stayed dead for good (a runner that never
     * lets go of W stood there: the benchmark course, segment 12, 2026-11-22).
     */
    private boolean resumesSprint() {
        return landedSprinting && (hold.latched() & LandingHold.FORWARD) != 0;
    }

    private float holdHealth = 20f;

    /**
     * The user clearly means to go on past the block they are landing on (owner, 2026-11-22: chain easy jumps, but
     * never force a series of jumps on someone who meant one): jump is held, or the intent target (where they are
     * looking) is a block beyond this landing. W alone is not enough.
     */
    private boolean continuing(MovementPhysics at, int keys) {
        if ((keys & LandingHold.JUMP) != 0) {
            return true;
        }
        if (chainContinues(at, keys)) {
            return true;
        }
        // where the user is looking now: the estimator's latest pick, not the target frozen for the commitment
        // (the confidence without the sole-candidate floor: W toward the only top ahead is not "going on")
        if (!intentAssist || !rawSet || rawLookConfidence < intentThreshold) {
            return false;
        }
        double dx = rtx + 0.5 - at.x;
        double dz = rtz + 0.5 - at.z;
        return dx * dx + dz * dz > CONTINUE_MIN_DIST * CONTINUE_MIN_DIST;
    }

    /** The intent target must be at least this far (blocks, horizontal) from the landing to count as going on. */
    static final double CONTINUE_MIN_DIST = 1.2;

    /** The jump-chain lookahead (set by the module; null in tests that do not use it). */
    private ChainLookahead chain;
    /** The view this tick (the module sets both before {@link #decide}). */
    public float viewYaw;
    public float viewPitch;
    /** The view must point within this of a link down the chain (yaw and pitch, degrees) to count as going on. */
    static final double CHAIN_LOOK_DEG = 15.0;
    /** Links past the next one the view may be on (a fast player looks two or three jumps ahead). */
    static final int CHAIN_LOOK_LINKS = 5;

    public void setChain(ChainLookahead c) {
        chain = c;
    }

    /**
     * Owner, 2026-11-23: chains slowed down because the hold latched W on every link: the estimator scores one jump
     * ahead, and a player looking two or three jumps on had no confident target there. Going on also counts when the
     * body lands on a link of the chain lookahead's path, W is down, and the view is on one of the next links: the
     * same evidence as the estimator's look, further out. The last link of a chain keeps the full hold.
     */
    private boolean chainContinues(MovementPhysics at, int keys) {
        if (!intentAssist || chain == null || !chain.ready() || (keys & LandingHold.FORWARD) == 0) {
            return false;
        }
        int j = chainNext(at);
        if (j < 0) {
            return false;
        }
        double eye = at.y + 1.62;
        int end = Math.min(chain.length(), j + CHAIN_LOOK_LINKS);
        for (int k = j; k < end; k++) {
            double dx = chain.linkX(k) + 0.5 - at.x;
            double dz = chain.linkZ(k) + 0.5 - at.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < CONTINUE_MIN_DIST) {
                continue;
            }
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            double pitchTo = Math.toDegrees(Math.atan2(eye - chain.linkTop(k), d));
            if (Math.abs(wrapDeg(bearing - viewYaw)) <= CHAIN_LOOK_DEG
                    && Math.abs(pitchTo - viewPitch) <= CHAIN_LOOK_DEG) {
                return true;
            }
        }
        return false;
    }

    /** Index of the next jump link of the chain from the top the body is on, or -1. */
    private int chainNext(MovementPhysics at) {
        if (chain == null || !chain.ready()) {
            return -1;
        }
        int i = chain.indexAt(at.x, at.y, at.z);
        return i < 0 ? -1 : chain.nextJump(i);
    }

    private static double wrapDeg(double d) {
        d %= 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** A narrow landing where the user means to go on and their keys lead safely into the next jump: no hold. */
    private boolean flowsOn(MovementPhysics at, float yaw, boolean sprinting, int f, int s, int keys,
                            MovementPhysics.Voxels w) {
        return continuing(at, keys) && carryOnIsSafe(at, yaw, sprinting, f, s, holdHealth, w);
    }

    /** Ticks of walking on that must stay supported for a held key to count as safe again. */
    static final int CARRY_ON_TICKS = 10;

    /**
     * Walking on with the user's raw keys stays on the ground for {@link #CARRY_ON_TICKS}, or reaches an edge where
     * the edge jump would fire (so the assist takes the next jump). Walking off a lone top into a drop is not safe.
     */
    private boolean carryOnIsSafe(MovementPhysics now, float yaw, boolean sprinting, int f, int s, float health,
                                  MovementPhysics.Voxels w) {
        if (f == 0 && s == 0) {
            return false;
        }
        int edge = search.ticksToEdge(now, f, s, yaw, sprinting, CARRY_ON_TICKS, w);
        if (edge < 0) {
            return true;
        }
        if (!edgeJump || f <= 0 || s != 0) {
            return false;
        }
        carry.copyFrom(now);
        for (int t = 0; t < edge; t++) {
            carry.tick(f, s, yaw, sprinting, false, w);
            commitSimTicks++;
        }
        // The carry-on jump goes where the user is going now: the estimator's latest pick, like continuing(), not the
        // target frozen for the commitment (during the flight and the settle that is the block being landed on, and a
        // jump "to" the block under the feet never looks worth taking). Before the freeze held from TAKEOFF as
        // designed, the estimator often dropped the target on the takeoff tick, the flight ran unfrozen and the lock
        // had moved on to the next block by the touchdown; this keeps that flow with the freeze in place.
        // decide() sets the search's target again right after the hold update.
        int link = rawSet && rawConfidence >= intentThreshold ? -1 : chainNext(now);
        if (rawSet && rawConfidence >= intentThreshold) {
            search.setTarget(rtx, rty, rtz);
        } else if (link >= 0 && chainContinues(now, LandingHold.FORWARD)) {
            // no confident pick, but the user looks down the chain: its next link is where the jump goes
            search.setTarget(chain.linkX(link), chain.linkBlockY(link), chain.linkZ(link));
        } else {
            search.clearTarget();
        }
        // the edge jump must continue the course (a landing no lower than this top, no damage), not drop off it
        double topY = carry.y;
        return search.shouldEdgeJump(carry, yaw, sprinting, health, w)
                && search.landY() >= topY - CARRY_ON_MAX_DROP && search.landDamage() <= 0f;
    }

    /** How much lower than the current top a carry-on edge jump may land (a step down still counts as the course). */
    static final double CARRY_ON_MAX_DROP = 0.6;

    private boolean holdStable;
    /** Scratch body for the carry-on check (separate from {@link #cont}, which the hold's air check uses). */
    private final MovementPhysics carry = new MovementPhysics();

    private void updateHoldState(MovementPhysics now, float yaw, boolean sprinting, int f, int s, int keys,
                                 MovementPhysics.Voxels w) {
        hold.enabled = landingHold;
        boolean narrowAhead = false;
        boolean narrowHere = false;
        boolean stable = false;
        boolean jumpHeld = (keys & LandingHold.JUMP) != 0;
        if (landingHold && !jumpHeld) {
            if (!now.onGround) {
                // does the user's own flight touch down next tick, and is that landing narrow?
                cont.copyFrom(now);
                cont.tick(f, s, yaw, sprinting, false, w);
                commitSimTicks++;
                if (cont.onGround) {
                    narrowAhead = narrowLanding(cont, f, s, yaw, sprinting, w)
                            && !flowsOn(cont, yaw, sprinting, f, s, keys, w);
                }
            } else {
                if (touchedDown) {
                    narrowHere = narrowLanding(now, f, s, yaw, sprinting, w)
                            && !flowsOn(now, yaw, sprinting, f, s, keys, w);
                }
                if (landRegion.count() > 0 && (hold.active() || narrowHere)) {
                    boolean freshHeld = (keys & LandingHold.MOVE_MASK & ~hold.latched()) != 0;
                    // with a fresh key down, that key's motion is the user's: stable once the landing slide is spent
                    stable = freshHeld
                            ? landRegion.momentumSpent(now.x, now.z, now.vx, now.vz, now.slipperiness)
                            : landRegion.stable(now.x, now.z, now.vx, now.vz, now.slipperiness);
                }
            }
        }
        // a body that has come to rest counts as settled even when the sneak guard leaves it hanging over the edge of
        // a 1-block top (otherwise the hold ran its full 60-tick cap there)
        if (!stable && now.onGround && hold.active()
                && now.vx * now.vx + now.vz * now.vz < STABLE_SPEED * STABLE_SPEED) {
            stable = true;
        }
        holdStable = stable;
        hold.update(now.onGround, keys, false, narrowAhead, narrowHere, stable);
    }

    /**
     * A touchdown state is narrow: the stop point of the user's keys (from the touchdown speed and the friction)
     * does not have a wide top around it. Leaves {@link #landRegion} over the landing and the stop point.
     */
    private boolean narrowLanding(MovementPhysics b, int f, int s, float yaw, boolean sprinting,
                                  MovementPhysics.Voxels w) {
        landRegion.computeStop(b.x, b.z, b.vx, b.vz, f, s, yaw, sprinting, b.speed, b.slipperiness, false);
        double sx = landRegion.stopX();
        double sz = landRegion.stopZ();
        if (!landRegion.build(w, b.x, b.z, sx, sz, b.y)) {
            return false; // no top under the body at this height (a step, water): nothing the hold can hold
        }
        return !landRegion.wide(sx, sz);
    }

    /**
     * RELEASE of a commitment on the ground (design 5.3): fill has ended; a stale key is withheld only while the
     * user's raw keys would step off within the shrinking edge-guard horizon of {@code 2 + 4a} ticks.
     */
    private void releaseEdgeGuard(MovementPhysics now, float yaw, boolean sprinting, int rawF, int rawS,
                                  int keys, MovementPhysics.Voxels w) {
        int h = commit.edgeGuardHorizon();
        if (h <= 0 || (rawF == 0 && rawS == 0) || jump
                || flowsOn(now, yaw, sprinting, rawF, rawS, keys, w)) {
            // (owner 2026-11-22) the user is going on and the next jump is clear: the stale key is theirs
            return;
        }
        int stale = commit.staleMask();
        int staleF = rawF > 0 ? stale & LandingHold.FORWARD : rawF < 0 ? stale & LandingHold.BACK : 0;
        int staleS = rawS > 0 ? stale & LandingHold.LEFT : rawS < 0 ? stale & LandingHold.RIGHT : 0;
        if (staleF == 0 && staleS == 0) {
            return;
        }
        cont.copyFrom(now);
        for (int t = 0; t < h; t++) {
            cont.tick(rawF, rawS, yaw, sprinting, false, w);
            commitSimTicks++;
            if (!cont.supported(w)) {
                if (staleF != 0) {
                    forward = 0;
                }
                if (staleS != 0) {
                    strafe = 0;
                }
                corrected = true;
                return;
            }
        }
    }

    // ---- commitment (GUIDE / CARRY) -------------------------------------------------------------------------------------

    /** The locked target's real top from its boxes: search target height and extent, region, fail-safe surfaces. */
    private void updateTargetTop(MovementPhysics.Voxels w) {
        double top = targetRegion.topOf(w, itx, ity, itz);
        if (Double.isNaN(top)) {
            targetRegionValid = false;
            targetSafe = false;
            return;
        }
        // the target block's own top: a narrow target level with the run-up (a pane beside the platform) must not count
        // a landing back on the platform as a landing on the target
        targetCell.clear();
        targetCell.setTop(top);
        targetCell.addTopBox(targetRegion.cellTopBound(0), targetRegion.cellTopBound(1),
                targetRegion.cellTopBound(2), targetRegion.cellTopBound(3));
        targetRegionValid = targetRegion.build(w, itx + 0.5, top, itz + 0.5);
        planRegion = targetRegionValid && targetRegion.wide(itx + 0.5, itz + 0.5) ? targetRegion : targetCell;
        if (targetRegionValid) {
            search.setTargetTop(top, targetRegion.cellTopBound(0), targetRegion.cellTopBound(1),
                    targetRegion.cellTopBound(2),
                    targetRegion.cellTopBound(3));
        }
        byte sf = w.surface(itx, ity, itz);
        // fail-safe (design 7.2): never commit onto physics the replica does not model, or onto a top that hurts
        targetSafe = targetRegionValid && sf != MovementPhysics.SURFACE_HARM && sf != MovementPhysics.SURFACE_STUCK
                && sf != MovementPhysics.SURFACE_UNMODELLED;
    }

    private void decideAirCommitted(MovementPhysics now, float yaw, boolean sprinting, int userForward,
                                    int userStrafe, float health, MovementPhysics.Voxels w) {
        double mine = search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
        double myMiss = search.lastMiss();
        boolean myBad = mine <= JumpSearch.NO_LANDING || search.landDamage() > 0;
        capture(mine);
        int planF = KEEP;
        int planS = KEEP;
        boolean lands = false;
        if (airSteer && targetRegionValid) {
            boolean allowBack = mode == JumpCommit.Mode.CARRY || userForward == 0;
            if (flight.plan(now, userForward, userStrafe, yaw, sprinting, allowBack, planRegion, w)) {
                lands = true;
                boolean alreadyFine = flight.userLands() && flight.userMargin() >= SupportRegion.STABLE_MARGIN;
                if (!(mode == JumpCommit.Mode.GUIDE && alreadyFine) && flight.brakeNow()) {
                    planF = flight.brakeForward(); // minimum intervention: nothing while the user's keys land it
                }
            }
            commitSimTicks += flight.simTicks();
        }
        if (airSteer && !lands && (myMiss > 0.0 || myBad) && search.steer(now, yaw, sprinting, health, w)) {
            // nothing in the brake family lands: the existing nine-combination search
            if (search.lastMiss() <= 0.0 || (myBad && search.score() > mine)) {
                planF = search.forward();
                planS = search.strafe();
                lands = search.lastMiss() <= 0.0;
            }
        } else if (airSteer && !lands && myMiss <= 0.0 && !myBad) {
            lands = true; // the user's own keys land on the target (no region to brake for)
        }
        lastPlanLands = lands || myMiss <= 0.0;
        if (trimRevert) {
            trimRevert = false;
            if (!lastPlanLands) {
                // the trim is out of reach from this flight: back to the top it was landing on
                itx = trimX;
                ity = trimY;
                itz = trimZ;
                intentConfidence = trimConfidence;
                idealValid = false;
                targetChanged();
                lastPlanLands = true;
            }
        }
        lastPlanForward = planF == KEEP ? userForward : planF;
        // co-steer from the flight plan: toward the target's centre when the keys at this yaw do not land it
        if (coSteerAllowed && targetRegionValid && !(flight.found() && flight.userLands())) {
            double dx = itx + 0.5 - now.x;
            double dz = itz + 0.5 - now.z;
            float aim = yaw + wrap((float) Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
            coSteer = true;
            coSteerYaw = naturalYaw(aim, now, yaw);
        }
        int f = commit.resolveAxis(0, userForward, planF);
        int s = commit.resolveAxis(1, userStrafe, planS);
        commit.limit(f, s, userForward, userStrafe);
        forward = commit.limitedForward();
        strafe = commit.limitedStrafe();
        commit.notePlan(planF, planS);
        if (forward != KEEP || strafe != KEEP) {
            int af = forward != KEEP ? forward : userForward;
            int as = strafe != KEEP ? strafe : userStrafe;
            capture(search.evaluate(now, af, as, yaw, sprinting, false, health, w));
            corrected = true;
        } else {
            search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
            capture(mine);
        }
    }

    /** The user pressed sneak: the documented "let go" key. Ends the commitment and the landing hold at once. */
    public void noteUserSneak() {
        userSneak = true;
        commit.abort();
        hold.reset();
    }

    /**
     * An outside abort: Stop-All, a screen, the module or mode off, an EMERGENCY or higher MOVEMENT lease. Ends the
     * commitment (fast hand-back) and the landing hold; the next {@link #decide} sees it.
     */
    public void abortCommitment() {
        externalAbort = true;
        commit.abort();
        hold.reset();
    }

    /** Per-level key-change limits (LegitPresets): changes per jump (0 = unlimited), hold ticks, reaction ticks. */
    public void setKeyChangeLimits(int maxChanges, int holdTicks, int reactionTicks) {
        commit.maxKeyChanges = Math.max(0, maxChanges);
        commit.minChangeHoldTicks = Math.max(1, holdTicks);
        baseReactionTicks = Math.max(0, reactionTicks);
    }

    public JumpCommit commit() {
        return commit;
    }

    public LandingHold hold() {
        return hold;
    }

    public FlightController flight() {
        return flight;
    }

    /** The locked target's top region (valid while {@link #targetRegionValid()}). */
    public SupportRegion targetRegion() {
        return targetRegion;
    }

    public boolean targetRegionValid() {
        return targetRegionValid;
    }

    /** Simulated ticks the commitment and the hold used in the last decision (bounded by {@link #MAX_COMMIT_SIM_TICKS}). */
    public int lastCommitSimTicks() {
        return commitSimTicks;
    }

    private void decideGround(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                              boolean jumpIn, float health, MovementPhysics.Voxels w) {
        if (heldPress > 0 && !techniqueFirst) {
            // a press held for a search that is gone goes before anything else can own the takeoff
            pressHeldBack(now, yaw, sprinting, userForward, userStrafe, w);
            capture(search.evaluate(now, Math.max(userForward, 0), userStrafe, yaw, sprinting, true, health, w));
            return;
        }
        if (approach.owns()) {
            // the approach controller owns the takeoff: its jump tick, its eased / walking run-up, nothing else
            jump = approach.jump();
            withholdJump = approach.withholdJump();
            forward = approach.forward();
            sprintOff = approach.sprintOff() && sprintWithholdAvailable;
            corrected = forward != KEEP || sprintOff || withholdJump
                    || (jump && approach.planCost() != 0);
            capture(search.evaluate(now, Math.max(forward == KEEP ? userForward : forward, 0), userStrafe, yaw,
                    sprinting && !sprintOff, true, health, w));
            return;
        }
        if (pressHeldBack(now, yaw, sprinting, userForward, userStrafe, w)) {
            capture(search.evaluate(now, Math.max(userForward, 0), userStrafe, yaw, sprinting, true, health, w));
            return;
        }
        shapeTakeoff(now, yaw, sprinting, userForward, userStrafe, jumpIn, health, w);
        if (shaped) {
            jump = shaper.jumpNow(); // the shape takes off on its own last supported tick
        } else if (techniqueFirst && search.ticksToEdge(now, userForward, userStrafe, yaw, sprinting, 1, w) != 0) {
            // the technique search is still looking for a way around: the assist's own jump waits for the last
            // supported tick instead of taking off early on a line that is known to be blocked
            jump = false;
        } else if (edgeJump && !jumpIn && userForward > 0 && search.shouldEdgeJump(now, yaw, sprinting, health, w)
                && (!safeOnly || search.landDamage() <= 0f)) {
            // a committed mode's takeoff is not budgeted (design 3: only the skill fade survives there)
            jump = mode != JumpCommit.Mode.NUDGE || budget.allowance(AuthorshipBudget.FULL_HELP_MISS) > 0;
        } else if (edgeJump && !jumpIn && userForward > 0 && tickPhase && (intentActive()
                || Math.abs(yawRate) >= PHASE_MIN_RATE)
                && search.ticksToEdge(now, 1, 0, yaw, sprinting, 1, w) == 1
                && timing.choose(now, yaw, yawRate, sprinting, health, safeOnly, search, w)
                == TakeoffTiming.Choice.NOW
                && budget.allowance(AuthorshipBudget.FULL_HELP_MISS) > 0) {
            jump = true;
            earlyJump = true;
        } else if (sprintHop && !jumpIn && sprinting && userForward > 0 && hopGains(now, yaw, w)) {
            jump = true;
        }
        edgeNoLanding = !jump && !shaped && edgeJump && !jumpIn && userForward > 0
                && search.lastWalkScore() <= JumpSearch.NO_LANDING && search.lastJumpScore() <= JumpSearch.NO_LANDING;
        // predicted landing of a jump from here (the overlay's "if you jump now")
        double sc = search.evaluate(now, Math.max(userForward, 0), userStrafe, yaw, sprinting, true, health, w);
        capture(sc);
    }

    /** Ticks the user's press has been held back for the technique search (0 = none pending). */
    private int heldPress;

    /**
     * The user's jump press while the search for a way around a blocked line is still running (no script yet): the
     * press is held back while the body is still supported on the next tick, at most
     * {@link ApproachController#MAX_WITHHOLD_TICKS}, so the search gets the last ground ticks and a script that takes
     * off on the last possible tick can still be flown. Then the press goes as pressed.
     *
     * @return true when this tick's jump decision was made here
     */
    private boolean pressHeldBack(MovementPhysics now, float yaw, boolean sprinting, int f, int s,
                                  MovementPhysics.Voxels w) {
        if (!techniqueFirst) {
            if (heldPress == 0) {
                return false;
            }
            // the search was dropped (target lost, the commitment gone) while a press was held: the press goes now,
            // never swallowed (playtest 2026-11-22 17:37: a dropped hold walked off the corner-fence edge)
            heldPress = 0;
            jump = !userJumpNow;
            withholdJump = false;
            return true;
        }
        if (heldPress == 0) {
            if (!userJumpNow) {
                return false;
            }
            heldPress = 1;
        } else {
            heldPress++;
        }
        carry.copyFrom(now);
        carry.tick(f, s, yaw, sprinting, false, w);
        commitSimTicks++;
        // hold only while the body is still standing after the next tick under the REAL collision box: onGround alone
        // still reports true on the tick the body steps past the edge (Y resolves before X/Z), so a hold judged by it
        // can outlive the support by a tick and depends on the tick phase against the edge
        if (heldPress <= ApproachController.MAX_WITHHOLD_TICKS && carry.onGround && carry.supported(w)) {
            withholdJump = userJumpNow;
            corrected = true;
            return true;
        }
        heldPress = 0;
        jump = !userJumpNow; // the press goes now (the key itself when it is still down)
        return true;
    }

    /**
     * A script that keeps the user's press waiting for its own takeoff: when this tick's keys (the script's where it
     * set them) would leave the ground before that takeoff comes (no jump possible next tick), the press goes now.
     * Judged by onGround, not {@link MovementPhysics#supported}: a script may take off on the step-off tick.
     */
    private void releaseBeforeSupportEnds(MovementPhysics now, float yaw, boolean sprinting, int userForward,
                                          int userStrafe, MovementPhysics.Voxels w) {
        int f = forward == KEEP ? userForward : forward;
        int s = strafe == KEEP ? userStrafe : strafe;
        carry.copyFrom(now);
        carry.tick(f, s, yaw, (sprinting && !sprintOff) || sprint, false, w);
        commitSimTicks++;
        if (carry.onGround) {
            return;
        }
        heldPress = 0;
        jump = true;
        withholdJump = false;
        corrected = true;
    }

    /**
     * Takeoff shaping ({@link TakeoffShaper}): with a target, forward held, the assist's own edge jump on and an edge
     * within {@link TakeoffShaper#WINDOW_TICKS}, when the user's run-up misses, fill the strafe key or engage sprint
     * (one corrected tick each tick it acts, all of the shape's ticks must fit the budget before it starts), or offer
     * a yaw shape as the turn-gain ideal yaw. Sets {@link #shaped} when keys were filled this tick.
     */
    private void shapeTakeoff(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                              boolean jumpIn, float health, MovementPhysics.Voxels w) {
        if (!takeoffShaping || !edgeJump || jumpIn || userForward <= 0 || budget.cap() <= 0 || !intentActive()
                || search.ticksToEdge(now, userForward, userStrafe, yaw, sprinting, TakeoffShaper.WINDOW_TICKS, w) < 0) {
            return;
        }
        shaper.evaluate(now, yaw, sprinting, userForward, userStrafe, yawRate, shapeYawAllowed,
                sprintFillAllowed && !sprinting, safeOnly, health, search, w);
        search.addTicks(shaper.lastTicks());
        if (!shaper.found()) {
            return;
        }
        double miss = shapeMiss();
        if (budget.remaining(miss) < shaper.cost()) {
            return; // the whole shape must fit: a shape cut off halfway is worse than the user's own run
        }
        if (shaper.yawShape()) {
            // turn gain only: the keys stay the user's until their own view gets there
            idealYaw = shaper.idealYaw();
            hasIdealYaw = true;
            missNow = Math.max(missNow, shaper.plainMiss());
            return;
        }
        if (!(shaper.strafeNow() || shaper.sprintNow()) || !budget.tryUse(miss)) {
            return; // sprint engages on a later tick: nothing to do yet
        }
        if (shaper.strafeNow()) {
            strafe = shaper.strafe();
        }
        sprint = shaper.sprintNow();
        shaped = true;
        corrected = true;
    }

    /** The miss the budget scales a shape by: the plain miss, or the margin still wanted on a narrow landing. */
    private double shapeMiss() {
        return shaper.plainMargin() >= 0.0 ? TakeoffShaper.SAFE_MARGIN - shaper.plainMargin() : shaper.plainMiss();
    }

    private void decideAir(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                           float health, MovementPhysics.Voxels w) {
        double mine = search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
        double myMiss = search.lastMiss();
        capture(mine);
        if (!airSteer) {
            return;
        }
        boolean targetOn = search.hasTarget();
        boolean bad = mine <= JumpSearch.NO_LANDING || search.landDamage() > 0;
        double myLandY = search.landY();
        if (!search.steer(now, yaw, sprinting, health, w)) {
            return; // nothing lands: keep the user's keys
        }
        double best = search.score();
        double bestMiss = search.lastMiss();
        boolean muchLower = mine > JumpSearch.NO_LANDING && search.landY() - myLandY >= 1.0;
        boolean safety = bad || muchLower;
        // precision: the user's keys land safely but beside the target, and the best keys land on it
        boolean precision = !safety && targetOn && myMiss > 0.0 && bestMiss <= 0.0;
        if (!(safety || precision) || (safety && best < mine + STEER_MARGIN)) {
            search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
            capture(mine);
            return;
        }
        int bf = search.forward();
        int bs = search.strafe();
        // fill only the free axes; if the best needs an axis the user is holding differently, re-check the blend
        int f = userForward != 0 ? userForward : bf;
        int s = userStrafe != 0 ? userStrafe : bs;
        double blended = search.evaluate(now, f, s, yaw, sprinting, false, health, w);
        boolean blendOk = precision ? search.lastMiss() <= 0.0 : blended >= mine + STEER_MARGIN * 0.5;
        // a precision trim starts only when the budget can carry it to the landing (a trim cut halfway can leave the
        // player worse off than their own keys); a safety correction takes what the budget gives
        if (!blendOk || (f == userForward && s == userStrafe) || !(precision
                ? budget.remaining(myMiss) >= search.landTicks() && budget.tryUse(myMiss)
                : budget.tryUse(AuthorshipBudget.FULL_HELP_MISS))) {
            search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
            capture(mine);
            return;
        }
        capture(blended);
        forward = f != userForward ? f : KEEP;
        strafe = s != userStrafe ? s : KEEP;
        corrected = forward != KEEP || strafe != KEEP;
    }

    // ---- jump tracking --------------------------------------------------------------------------------------------

    private void trackJump(MovementPhysics now) {
        tookOff = prevOnGround && !now.onGround;
        touchedDown = !prevOnGround && now.onGround;
        if (tookOff) {
            budget.onTakeoff(prevAssistedJump);
        } else if (touchedDown) {
            boolean clean = search.hasTarget()
                    ? search.missDistance(now.x, now.y, now.z) <= 0.0
                    : MovementPhysics.fallDamage(prevFall) <= 0f;
            budget.onTouchdown(clean);
        }
        prevOnGround = now.onGround;
        prevFall = now.fallDistance;
    }

    // ---- intent target / ideal takeoff yaw --------------------------------------------------------------------------

    /**
     * The block the player means to land on (the solid block; feet at y + 1), from the intent estimator. Below
     * {@link IntentEstimator#MIN_CONFIDENCE} it is ignored.
     */
    public void setIntentTarget(int x, int y, int z, double confidence) {
        rawSet = true;
        rawMissTicks = 0;
        rtx = x;
        rty = y;
        rtz = z;
        rawConfidence = confidence;
        rawLookConfidence = Double.isNaN(pendingLook) ? confidence : pendingLook;
        pendingLook = Double.NaN;
        if (!targetFrozen && !lockDeferred()) {
            lockFromRaw();
        }
    }

    /**
     * The estimator's straight roll toward the target ran into a wall (see {@link IntentEstimator#targetBlocked()}):
     * the target needs a technique. Kept until the locked target changes.
     */
    public void setIntentBlocked(boolean blocked) {
        rawBlocked = blocked;
    }

    /**
     * The estimator's confidence without its sole-candidate floor ({@link IntentEstimator#lookConfidence()}), for the
     * next {@link #setIntentTarget}. The landing hold's "the user is going on" reads this one, so that holding W
     * toward the only top ahead never chains a jump (owner, 2026-11-22: W alone is not enough). Not set: the
     * target's own confidence.
     */
    public void setIntentLook(double lookConfidence) {
        pendingLook = lookConfidence;
    }

    private double pendingLook = Double.NaN;
    private double rawLookConfidence;
    private boolean rawBlocked;
    private boolean lineBlocked;
    private boolean techniqueFirst;
    private boolean userJumpNow;

    public void clearIntentTarget() {
        rawSet = false;
        rawMissTicks++;
        if (!targetFrozen && !lockDeferred()) {
            lockFromRaw();
        }
    }

    /**
     * While ARMED the estimator's pick of this tick is applied in {@link #decide} ({@code updateFreeze}), after the
     * takeoff is known: on the takeoff tick the body is already in the air and close to the target, where the
     * estimator may drop or change its pick, and the armed target is the one the jump was planned for (design 9.6:
     * the target is frozen at TAKEOFF). On every other ARMED tick the result is the same as applying it here.
     */
    private boolean lockDeferred() {
        return mode != JumpCommit.Mode.NUDGE && intentSet && commit.phase() == JumpCommit.Phase.ARMED;
    }

    // raw intent (the estimator's latest pick; where the user is looking) vs the locked target the plans use
    private boolean rawSet;
    private int rtx, rty, rtz;
    private double rawConfidence;
    /** From TAKEOFF until the commitment ends the locked target ignores the estimator (design 9.6). */
    private boolean targetFrozen;
    private boolean trimUsed;

    /**
     * Ticks the estimator has had no pick in a row. An ARMED target behind a wall sits right at the commit threshold
     * (the wall hides most of it from the look), and live on course J36 it dropped out for a few ticks in the middle
     * of the run-up, so the commitment ended where the neo had to be planned: a few missing ticks keep it.
     */
    private int rawMissTicks;
    static final int ARMED_MISS_GRACE = 6;
    /** The same for an open line while the approach owns the takeoff (only the last ground ticks need it). */
    static final int ARMED_OPEN_MISS_GRACE = 3;
    /** A verified planned takeoff this close (ticks) keeps its target against a new estimator pick. */
    static final int COMMITTED_TAKEOFF_TICKS = 4;

    private void lockFromRaw() {
        if (!rawSet) {
            if (intentSet && mode != JumpCommit.Mode.NUDGE && commit.phase() == JumpCommit.Phase.ARMED
                    && lineBlocked && rawMissTicks <= ARMED_MISS_GRACE) {
                return;
            }
            // an open line keeps the target a few ticks while the approach owns its takeoff: close to a post one gap
            // out the estimator drops it on the last ground ticks (under its minimum range, the look fading), which
            // reset the approach and its planned takeoff, and the body walked off the edge (course J23 pane post,
            // 2026-11-23)
            if (intentSet && mode != JumpCommit.Mode.NUDGE && commit.phase() == JumpCommit.Phase.ARMED
                    && !lineBlocked && approach.owns() && rawMissTicks <= ARMED_OPEN_MISS_GRACE) {
                return;
            }
            if (intentSet) {
                intentSet = false;
                idealValid = false;
                hasIdealYaw = false;
                targetChanged();
            }
            return;
        }
        if (intentSet && (rtx != itx || rty != ity || rtz != itz) && mode != JumpCommit.Mode.NUDGE
                && commit.phase() == JumpCommit.Phase.ARMED && approach.hasPlan()
                && approach.ticksToTakeoff() <= COMMITTED_TAKEOFF_TICKS) {
            // a verified takeoff a few ticks out is this jump: a post one gap out drops under the estimator's range
            // there and its pick moved to the pad behind it, which reset the approach a tick before the edge and the
            // body walked off (course J23, Takeover, 2026-11-23 17:55)
            return;
        }
        if (intentSet && rty == ity && (rtx != itx || rtz != itz) && targetRegionValid
                && targetRegion.contains(rtx + 0.5, rtz + 0.5)) {
            // another cell of the same top (the strip behind the neo's pillar: the pick walks z4 -> z5 -> z6 as the
            // body runs up) is the same landing: the plan and its script stay (CourseNeoIntentTest, pitch 15)
            intentConfidence = rawConfidence;
            lineBlocked |= rawBlocked;
            return;
        }
        if (!intentSet || rtx != itx || rty != ity || rtz != itz) {
            idealValid = false;
            yawCursor = 0;
            targetChanged();
        }
        intentSet = true;
        itx = rtx;
        ity = rty;
        itz = rtz;
        intentConfidence = rawConfidence;
        lineBlocked |= rawBlocked;
    }

    private void targetChanged() {
        lineBlocked = false;
        if (approach.holdsPress()) {
            // the approach held the user's press for its takeoff: the press goes on the next ground decision
            // (pressHeldBack), never swallowed with the plan
            heldPress = Math.max(heldPress, 1);
        }
        approach.reset();
        techniques.reset();
        scriptOn = false;
    }

    /** A top narrower than this on either axis (blocks) is a post or a row: fence 0.25, wall 0.5, pane 0.125. */
    static final double NARROW_TOP = 0.75;
    /** The one in-flight trim reverts on the next air plan when that plan no longer lands. */
    private boolean trimRevert;
    private int trimX, trimY, trimZ;
    private double trimConfidence;

    /**
     * Target freeze (design 9.6): from TAKEOFF until the commitment ends (SETTLE included, RELEASE not, so the next
     * jump's target is free during the hand-back) the locked target ignores the estimator, except one trim before the
     * apex when the user's look sweeps to another top (or forward is released and the estimator picks a nearer one).
     */
    private void updateFreeze(MovementPhysics now, JumpCommit.Phase before) {
        boolean frozen = mode != JumpCommit.Mode.NUDGE && intentSet
                && (before == JumpCommit.Phase.TAKEOFF || before == JumpCommit.Phase.FLIGHT
                || before == JumpCommit.Phase.TOUCHDOWN || before == JumpCommit.Phase.SETTLE
                || before == JumpCommit.Phase.CHAIN || (before == JumpCommit.Phase.ARMED && tookOff));
        if (!frozen && mode != JumpCommit.Mode.NUDGE && intentSet && before == JumpCommit.Phase.ARMED && scriptOn) {
            // a technique script is flying to the locked target and turns the view itself (the neo swings ~20
            // degrees off the line): the estimator's look fades with that turn, and dropping the target dropped the
            // script on its takeoff tick (course J36, 2026-11-23). It holds; only a confident other pick replaces it.
            targetFrozen = false;
            if (rawSet && rawConfidence >= intentThreshold && (rtx != itx || rty != ity || rtz != itz)) {
                lockFromRaw();
            }
            return;
        }
        if (!frozen) {
            targetFrozen = false;
            trimUsed = false;
            trimRevert = false;
            lockFromRaw();
            return;
        }
        targetFrozen = true;
        // no trim under a technique script: it is already flying its own line to the locked top, and a re-pick (the
        // far end of the same strip, course J36) dropped it mid-air
        if (!trimUsed && !scriptOn && rawSet && rawConfidence >= intentThreshold && !now.onGround && now.vy > 0.0
                && (rtx != itx || rty != ity || rtz != itz)) {
            trimUsed = true; // the one trim of this flight
            // kept only if this flight still lands on the new top (checked by the air plan this tick): over a fence
            // post the pick moved on to the pad behind it, out of reach, the commitment aborted and the body slid off
            // the post with no brake and no hold (J19, 2026-11-23)
            trimRevert = lastPlanLands;
            trimX = itx;
            trimY = ity;
            trimZ = itz;
            trimConfidence = intentConfidence;
            lockFromRaw();
        }
    }

    /** The last plan lands on the locked target (diagnostics). */
    public boolean planLands() {
        return lastPlanLands;
    }

    /**
     * A commitment in the air whose plan lands on its frozen target: the jump is the brain's (brake, trim, co-steer),
     * so the block assist must not start a placement on top of it. Its planner runs the user's raw keys and would
     * see an overshoot the brain is already braking out of (live trace 2026-11-23, J19 reverse: the placement turned
     * the camera down, its key rotation dropped the brake and the body sailed over the fence post).
     */
    public boolean commitOwnsLanding() {
        JumpCommit.Phase ph = commit.phase();
        return lastPlanLands && (ph == JumpCommit.Phase.TAKEOFF || ph == JumpCommit.Phase.FLIGHT
                || ph == JumpCommit.Phase.TOUCHDOWN);
    }

    /** The locked target is frozen for the running commitment. */
    public boolean targetFrozen() {
        return targetFrozen;
    }

    /** The locked target block (the one the plans use; valid while {@link #intentActive()}). */
    public int targetX() {
        return itx;
    }

    public int targetY() {
        return ity;
    }

    public int targetZ() {
        return itz;
    }

    /** A target is set, confident enough, and "Follow my intent" is on. */
    public boolean intentActive() {
        // Corrections has no technique search: a target that needs a way around is not a target there
        return intentAssist && intentSet && intentConfidence >= intentThreshold
                && !(lineBlocked && mode == JumpCommit.Mode.NUDGE);
    }

    /** The user's mouse yaw rate (deg/tick, + = yaw increasing), for the tick-phase check. */
    public void setYawRate(double degPerTick) {
        yawRate = Double.isFinite(degPerTick) ? degPerTick : 0.0;
    }

    private void updateIdealYaw(MovementPhysics now, float yaw, boolean sprinting, int f, int s, float health,
                                MovementPhysics.Voxels w) {
        double dx = itx + 0.5 - now.x;
        double dz = itz + 0.5 - now.z;
        float toTarget = (float) Math.toDegrees(Math.atan2(-dx, dz));
        toTarget = yaw + wrap(toTarget - yaw); // the same turn as the camera's yaw, no 360 wrap jumps
        double sc = search.edgeRun(now, f, s, yaw, sprinting, health, w);
        missNow = search.lastMiss();
        float bestYaw = yaw;
        double bestScore = sc;
        double bestMiss = missNow;
        if (idealValid) {
            double ps = search.edgeRun(now, f, s, idealYaw, sprinting, health, w);
            if (ps > bestScore) {
                bestScore = ps;
                bestYaw = idealYaw;
                bestMiss = search.lastMiss();
            }
        }
        for (int i = 0; i < YAW_CANDIDATES_PER_TICK; i++) {
            float cand = toTarget + YAW_OFFSETS[yawCursor];
            yawCursor = (yawCursor + 1) % YAW_OFFSETS.length;
            double cs = search.edgeRun(now, f, s, cand, sprinting, health, w);
            if (cs > bestScore + 1.0e-9) {
                bestScore = cs;
                bestYaw = cand;
                bestMiss = search.lastMiss();
            }
        }
        idealValid = bestScore > JumpSearch.NO_LANDING;
        idealYaw = bestYaw;
        idealScore = bestScore;
        idealMiss = bestMiss;
        hasIdealYaw = idealValid && bestMiss <= 0.0;
    }

    private boolean steerBaseSet;
    private float steerBaseYaw;

    /**
     * What is left of the level's co-steer total for this commitment, in whole degrees: the core counts every degree
     * it has added since the commitment's first request, so a script planned after the view was already turned (a
     * few ticks of aiming before the line was known to be blocked, an earlier script) may only ask for the rest. A
     * script that asked for the full total again stopped short of its goal in the client and left its line.
     */
    float coSteerLeft(float yaw) {
        float used = steerBaseSet ? Math.abs(wrap(yaw - steerBaseYaw)) : 0f;
        return (float) Math.floor(Math.max(0f, coSteerMaxTotalDeg - used));
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

    /** A yaw from which the jump (from the coming edge, or the current flight) lands on the target. */
    public boolean hasIdealTakeoffYaw() {
        return hasIdealYaw;
    }

    public float idealTakeoffYaw() {
        return idealYaw;
    }

    /** Predicted target miss (blocks) at the current yaw; 0 without a target or when it lands. */
    public double missDistance() {
        return missNow;
    }

    /**
     * How strongly turn gain may pull toward {@link #idealTakeoffYaw()} this tick (0 = do not ask): only with a target
     * and an ideal yaw, while the current yaw misses by more than {@link #TURN_GAIN_MIN_MISS}, on the approach or before
     * the apex, and while the authorship budget allows. Scaled by the miss and the player's skill.
     */
    public double turnGainHelp(MovementPhysics now) {
        if (!hasIdealYaw || missNow <= TURN_GAIN_MIN_MISS || !(now.onGround || now.vy > 0)
                || !budget.allows(missNow)) {
            return 0.0;
        }
        return budget.help(missNow);
    }

    /** The core reported this many degrees of turn gain last tick: a corrected tick for the budget. */
    public void noteTurnGain(double assistedYawDeg) {
        if (assistedYawDeg > 1.0e-3) {
            budget.noteUse();
        }
    }

    /** Forget jump history (world change, teleport). */
    public void reset() {
        targetFrozen = false;
        trimUsed = false;
        clearIntentTarget();
        approach.reset();
        scriptOn = false;
        sprintIntent = false;
        budget.reset();
        jumpBuffer.reset();
        hold.reset();
        commit.reset();
        // (the clear above is deferred while ARMED: apply it now that the commitment is gone)
        rawSet = false;
        lockFromRaw();
        rawBlocked = false;
        lineBlocked = false;
        techniqueFirst = false;
        heldPress = 0;
        landedSprinting = false;
        steerBaseSet = false;
        pendingLook = Double.NaN;
        lastPlanLands = false;
        lastPlanForward = KEEP;
        prevOnGround = true;
        prevAssistedJump = false;
        yawRate = 0.0;
    }

    // ---- sprint hop -------------------------------------------------------------------------------------------------

    /** A sprint jump vs. sprinting on, over the jump's own airtime, on ground that stays level. */
    private boolean hopGains(MovementPhysics now, float yaw, MovementPhysics.Voxels w) {
        a.copyFrom(now);
        b.copyFrom(now);
        a.tick(1, 0, yaw, true, true, w);
        b.tick(1, 0, yaw, true, false, w);
        int t = 1;
        while (!a.onGround && t < 20) {
            a.tick(1, 0, yaw, true, false, w);
            b.tick(1, 0, yaw, true, false, w);
            t++;
        }
        if (!a.onGround || a.y != now.y || !b.onGround || b.y != now.y || a.horizontalCollision) {
            return false;
        }
        double yawRad = Math.toRadians(yaw);
        double hx = -Math.sin(yawRad);
        double hz = Math.cos(yawRad);
        double ja = (a.x - now.x) * hx + (a.z - now.z) * hz;
        double jb = (b.x - now.x) * hx + (b.z - now.z) * hz;
        return ja > jb + HOP_GAIN;
    }

    private void capture(double score) {
        hasLanding = score > JumpSearch.NO_LANDING;
        lethal = !hasLanding;
        landX = search.landX();
        landY = search.landY();
        landZ = search.landZ();
        landDamage = hasLanding ? search.landDamage() : 0f;
    }

    public boolean jump() {
        return jump;
    }

    /**
     * Report what was actually applied this tick (the module calls it after {@link #decide}): whether the takeoff the
     * body is about to make was the assist's own jump. Without the call the brain assumes its own {@link #jump()}.
     */
    void noteApplied(boolean assistedJump) {
        prevAssistedJump = assistedJump;
    }

    /** The jump was the tick-phase check's early takeoff. */
    public boolean earlyJump() {
        return earlyJump;
    }

    /** Forward axis to apply, or {@link #KEEP}. */
    public int forward() {
        return forward;
    }

    /** Strafe axis to apply (+1 left, -1 right), or {@link #KEEP}. */
    public int strafe() {
        return strafe;
    }

    /** Sneak this tick (the landing hold's guard). Never set while the user holds jump. */
    public boolean sneak() {
        return sneak;
    }

    /**
     * Engage sprint this tick (takeoff shaping's sprint timing). Only ever set while the user is not sprinting: the
     * module turns sprint on, never off.
     */
    public boolean sprint() {
        return sprint;
    }

    // ---- approach control, technique scripts, co-steer clamp ----------------------------------------------------------

    /**
     * Confidence an intent target needs to count. The module sets it to the estimator's commit threshold:
     * {@link IntentEstimator#GUIDE_THRESHOLD}, or {@link IntentEstimator#CARRY_THRESHOLD} in Takeover.
     */
    public double intentThreshold = IntentEstimator.MIN_CONFIDENCE;
    /** Approach and takeoff control in GUIDE and CARRY ({@link ApproachController}); on by default. */
    public boolean approachControl = true;
    private final ApproachController approach = new ApproachController();
    private boolean sprintIntent;
    private int sprintGap;
    /** Ticks a sprint may stay off and still count as meant (the longest walking run-up plus the takeoff tick). */
    static final int SPRINT_INTENT_TICKS = 10;
    /** A drop (blocks) a jump can take without fall damage, with room to spare (damage starts past 3). */
    static final double SAFE_DROP = 2.5;
    /** NATURAL look style: a co-steer ideal stays within this many degrees of the horizontal velocity direction. */
    public static final float NATURAL_CLAMP_DEG = 30f;
    /** FREE look style lifts the NATURAL clamp (the module sets it from the look-style setting; NATURAL by default). */
    public boolean freeLook;

    private final FlightScript script = new FlightScript();
    private boolean scriptOn;
    private int scriptIndex;

    public ApproachController approach() {
        return approach;
    }

    /** A technique script is being replayed. */
    /** Diagnostics: the line to the target is blocked and the technique library is searched first. */
    public boolean techniqueFirst() {
        return techniqueFirst;
    }

    /** Diagnostics: the estimator's latest pick (x, y, z, confidence, look confidence), or false when none. */
    public boolean rawTarget(double[] out) {
        if (!rawSet) {
            return false;
        }
        out[0] = rtx;
        out[1] = rty;
        out[2] = rtz;
        out[3] = rawConfidence;
        out[4] = rawLookConfidence;
        return true;
    }

    public boolean scriptActive() {
        return scriptOn;
    }

    /** The script being replayed (valid while {@link #scriptActive()}). */
    public FlightScript activeScript() {
        return script;
    }

    /** Index of the script tick the last decision applied (-1 when none). */
    public int scriptIndex() {
        return scriptOn ? scriptIndex - 1 : -1;
    }

    /**
     * Drops a script that no longer holds (W released, an override, off its predicted line, ended, the commitment
     * gone) and asks the technique source for one when ARMED with a locked, safe target and neither the user's plan
     * nor the approach family lands. The source's replica ticks count into the commitment budget.
     */
    private void updateScript(MovementPhysics now, float yaw, boolean sprintKey, int userForward, int userStrafe,
                              boolean armed, float health, MovementPhysics.Voxels w) {
        if (scriptOn) {
            JumpCommit.Phase ph = commit.phase();
            boolean phaseOk = ph == JumpCommit.Phase.ARMED || ph == JumpCommit.Phase.TAKEOFF
                    || ph == JumpCommit.Phase.FLIGHT;
            // the user's own jump press: held back while the script's takeoff is at most a few ticks later (like the
            // approach controller's held-back press); a press further ahead of it is the user's jump, not the script's
            int untilJump = script.takeoffIndex - scriptIndex;
            int room = ApproachController.MAX_WITHHOLD_TICKS - heldPress;
            boolean pressAhead = now.onGround && (userJumpNow || heldPress > 0) && scriptIndex < script.length
                    && !script.jump[scriptIndex] && (untilJump < 0 || untilJump > room);
            if (pressAhead || userForward <= 0 || !phaseOk || commit.overrideThisTick() || scriptIndex >= script.length
                    || (scriptIndex > 0 && !script.onLine(scriptIndex - 1, now.x, now.y, now.z,
                    script.usesYaw && scriptIndex > script.takeoffIndex + 1 ? FlightScript.AIR_YAW_DRIFT_LIMIT
                            : FlightScript.DRIFT_LIMIT))
                    || (script.usesYaw && !coSteerAllowed)) {
                scriptOn = false;
            }
        }
        // (the first valid script is followed; swapping in a better one found later on the ground was tried and
        // made the corner lanes worse, see the run note of 2026-11-22)
        if (scriptOn || !armed || !(approach.exhausted() || techniqueFirst)
                || commitSimTicks + TechniqueSource.MAX_SIM_TICKS_PER_CALL > MAX_COMMIT_SIM_TICKS) {
            return;
        }
        techniques.setMaxTurn(coSteerAllowed ? coSteerLeft(yaw) : 0f);
        techniques.setAuthority(JumpCommit.armedAuthorityOf(mode), JumpCommit.flightAuthorityOf(mode));
        techniques.setLineBlocked(techniqueFirst);
        // a blocked line (a neo around a pillar) needs the sprint jump: a walking run-up is rolled with the sprint the
        // replay fills in for the user where vanilla allows it (live J36, 2026-11-23: the user walked after J35's hold)
        boolean planSprint = sprintKey || (techniqueFirst && sprintFillAllowed && now.onGround);
        boolean got = techniques.plan(now, yaw, planSprint, userForward, userStrafe, planRegion, coSteerAllowed,
                coSteerMaxDegPerTick, safeOnly, holdHealth, w);
        commitSimTicks += techniques.lastSimTicks();
        if (got) {
            FlightScript s = techniques.script();
            if (!s.isEmpty() && (!s.usesYaw || coSteerAllowed)) {
                script.copyFrom(s);
                scriptOn = true;
                scriptIndex = 0;
            }
        }
    }

    /** One tick of the replayed script: its keys (in the air under the commitment's rules), sprint, jump and yaw. */
    private void replayScript(MovementPhysics now, boolean sprinting, boolean sprintKey, int userForward,
                              int userStrafe, float health, MovementPhysics.Voxels w) {
        int i = scriptIndex++;
        int sf = script.forward[i];
        int ss = script.strafe[i];
        if (now.onGround) {
            forward = sf == userForward ? KEEP : sf;
            strafe = ss == userStrafe ? KEEP : ss;
            jump = script.jump[i];
            withholdJump = userJumpNow && !jump; // the takeoff is a few ticks later: the press waits for it
        } else {
            int f = commit.resolveAxis(0, userForward, sf == userForward ? KEEP : sf);
            int s = commit.resolveAxis(1, userStrafe, ss == userStrafe ? KEEP : ss);
            commit.limit(f, s, userForward, userStrafe);
            forward = commit.limitedForward();
            strafe = commit.limitedStrafe();
        }
        commit.notePlan(sf, ss); // a held key equal to the script's own is not an override (J34)
        boolean wantSprint = script.sprint[i];
        if (!wantSprint && sprintKey && sf > 0 && sprintWithholdAvailable) {
            sprintOff = true;
        } else if (wantSprint && !sprinting && sprintFillAllowed) {
            sprint = true;
        }
        if (script.usesYaw && coSteerAllowed && script.goal[i] == script.goal[i]) {
            coSteer = true;
            coSteerYaw = script.goal[i]; // NaN: no request this tick, the view holds
        }
        corrected = true;
        lastPlanLands = true; // a verified script: the commitment holds while the body stays on its line
        lastPlanForward = sf;
        int af = forward == KEEP ? userForward : forward;
        int as = strafe == KEEP ? userStrafe : strafe;
        capture(search.evaluate(now, Math.max(af, 0), as, script.yaw[i], sprintKey, now.onGround, health, w));
    }

    /**
     * NATURAL look style (design 6.2): the ideal yaw is clamped to within {@link #NATURAL_CLAMP_DEG} of the horizontal
     * velocity direction (the camera yaw when the body is nearly still), expressed as the same turn as the camera's.
     */
    float naturalYaw(float ideal, MovementPhysics now, float yaw) {
        float out = yaw + wrap(ideal - yaw);
        if (freeLook) {
            return out;
        }
        double h2 = now.vx * now.vx + now.vz * now.vz;
        float ref = h2 > 1.0e-4 ? (float) Math.toDegrees(Math.atan2(-now.vx, now.vz)) : yaw;
        ref = yaw + wrap(ref - yaw);
        float d = wrap(out - ref);
        if (d > NATURAL_CLAMP_DEG) {
            d = NATURAL_CLAMP_DEG;
        } else if (d < -NATURAL_CLAMP_DEG) {
            d = -NATURAL_CLAMP_DEG;
        }
        return ref + d;
    }

    // ---- seams between the brain and the module (contracts of the 2026-11-22 control-model volley) -----------------

    /**
     * The module can drop the user's sprint for a tick (it holds a shared grant whose {@code withholdSprint} works).
     * False in a bare brain: approach control then eases the forward key only, which also ends a sprint in vanilla.
     */
    public boolean sprintWithholdAvailable;
    /**
     * Yaw co-steer may be asked for (owner decision D1: BALANCED and above, Co-pilot and Takeover, never on LEGIT,
     * never under automation). Set by the module every tick; false in a bare brain.
     */
    public boolean coSteerAllowed;
    /** View change per tick a plan may ask of the co-steer at the current legit level (degrees). */
    public float coSteerMaxDegPerTick = 6f;

    private TechniqueSource techniques = TechniqueSource.NONE;
    private boolean sprintOff;
    private boolean coSteer;
    private float coSteerYaw;
    private float coSteerTotalDeg = 45f;
    /**
     * The most the module lets the co-steer turn the view over one commitment (the level's total: 25 degrees for
     * Co-pilot on Balanced). A technique script that needs more would fall off its line when the turn stops, so the
     * planner is told the cap. Set by the module every tick.
     */
    public float coSteerMaxTotalDeg = TechniqueTemplates.DEFAULT_MAX_TURN;

    /** The technique planner (corner, neo, headhitter, 45-degree); {@link TechniqueSource#NONE} until one is set. */
    public void setTechniques(TechniqueSource source) {
        techniques = source != null ? source : TechniqueSource.NONE;
    }

    public TechniqueSource techniques() {
        return techniques;
    }

    /**
     * Withhold sprint this tick (approach and takeoff control: a slower run-up for a near, narrow landing). Only ever
     * set while {@link #sprintWithholdAvailable}; the module passes it to {@code SharedGrant.withholdSprint()}.
     */
    public boolean sprintOff() {
        return sprintOff;
    }

    /** The brain wants the view eased toward {@link #coSteerYaw()} this tick. Never set unless {@link #coSteerAllowed}. */
    public boolean wantsCoSteer() {
        return coSteer && coSteerAllowed;
    }

    /** Ideal view yaw (degrees) for the co-steer; valid while {@link #wantsCoSteer()}. */
    public float coSteerYaw() {
        return coSteerYaw;
    }

    /** Most degrees the co-steer may add over this commitment. */
    public float coSteerTotalDeg() {
        return coSteerTotalDeg;
    }

    /** Takeoff shaping filled keys this tick (strafe and/or sprint; the jump, if any, is the shape's). */
    public boolean takeoffShaped() {
        return shaped;
    }

    /** The takeoff shaper: the last evaluation's shape, predicted gain and whether it was needed. */
    public TakeoffShaper shaper() {
        return shaper;
    }

    /** The jump buffer is holding the user's jump key back this tick (clear the jump input). */
    public boolean withholdJump() {
        return withholdJump;
    }

    public boolean corrected() {
        return corrected;
    }

    public boolean hasLanding() {
        return hasLanding;
    }

    /** True when the trajectory lands nowhere survivable within the horizon. */
    public boolean lethal() {
        return lethal;
    }

    public double landX() {
        return landX;
    }

    public double landY() {
        return landY;
    }

    public double landZ() {
        return landZ;
    }

    public float landDamage() {
        return landDamage;
    }

    /** At an edge where neither walking off nor jumping lands: only a placed block could save the run. */
    public boolean edgeNoLanding() {
        return edgeNoLanding;
    }

    /** The body left the ground / touched down on this decision. */
    public boolean tookOff() {
        return tookOff;
    }

    public boolean touchedDown() {
        return touchedDown;
    }

    /** Physics ticks the last decision simulated. */
    public int lastSimTicks() {
        return simTicks;
    }

    /** The shared search, for callers that want more (reach maps, automated drivers). */
    public JumpSearch search() {
        return search;
    }

    public AuthorshipBudget budget() {
        return budget;
    }

    public JumpBuffer jumpBuffer() {
        return jumpBuffer;
    }

    public TakeoffTiming timing() {
        return timing;
    }
}
