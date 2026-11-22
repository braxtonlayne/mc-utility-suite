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
        commitSimTicks = 0;
        trackJump(now);
        int keys = LandingHold.keys(userForward, userStrafe, userJump);
        // landing hold first (every mode): the latch decides which of the user's keys count this tick
        updateHold(now, yaw, sprinting, userForward, userStrafe, keys, w);
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
        if (now.onGround) {
            decideGround(now, yaw, sprinting, userForward, userStrafe, jumpIn, health, w);
            lastPlanLands = planOnGround;
            lastPlanForward = KEEP;
            releaseEdgeGuard(now, yaw, sprinting, rawForward, rawStrafe, w);
        } else if (mode != JumpCommit.Mode.NUDGE && commit.driving()) {
            decideAirCommitted(now, yaw, sprinting, userForward, userStrafe, health, w);
        } else {
            decideAir(now, yaw, sprinting, userForward, userStrafe, health, w);
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
        prevAssistedJump = jump && !jumpBuffer.fired();
        simTicks = search.tickCount();
    }

    // ---- landing hold -------------------------------------------------------------------------------------------------

    private void updateHold(MovementPhysics now, float yaw, boolean sprinting, int f, int s, int keys,
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
                    narrowAhead = narrowLanding(cont, f, s, yaw, sprinting, w);
                }
            } else {
                if (touchedDown) {
                    narrowHere = narrowLanding(now, f, s, yaw, sprinting, w);
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
                                  MovementPhysics.Voxels w) {
        int h = commit.edgeGuardHorizon();
        if (h <= 0 || (rawF == 0 && rawS == 0) || jump) {
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
        targetRegionValid = targetRegion.build(w, itx + 0.5, top, itz + 0.5);
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
            if (flight.plan(now, userForward, userStrafe, yaw, sprinting, allowBack, targetRegion, w)) {
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
        lastPlanForward = planF == KEEP ? userForward : planF;
        int f = commit.resolveAxis(0, userForward, planF);
        int s = commit.resolveAxis(1, userStrafe, planS);
        commit.limit(f, s, userForward, userStrafe);
        forward = commit.limitedForward();
        strafe = commit.limitedStrafe();
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
        shapeTakeoff(now, yaw, sprinting, userForward, userStrafe, jumpIn, health, w);
        if (shaped) {
            jump = shaper.jumpNow(); // the shape takes off on its own last supported tick
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
        if (!intentSet || x != itx || y != ity || z != itz) {
            idealValid = false;
            yawCursor = 0;
        }
        intentSet = true;
        itx = x;
        ity = y;
        itz = z;
        intentConfidence = confidence;
    }

    public void clearIntentTarget() {
        intentSet = false;
        idealValid = false;
        hasIdealYaw = false;
    }

    /** A target is set, confident enough, and "Follow my intent" is on. */
    public boolean intentActive() {
        return intentAssist && intentSet && intentConfidence >= IntentEstimator.MIN_CONFIDENCE;
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
        clearIntentTarget();
        budget.reset();
        jumpBuffer.reset();
        hold.reset();
        commit.reset();
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
