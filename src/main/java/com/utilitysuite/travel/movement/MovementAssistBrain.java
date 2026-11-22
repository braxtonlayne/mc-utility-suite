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
 *   <li><b>Stick the landing</b> ({@link #sneak()}): on the touchdown ticks of a 1-block landing that the user's keys
 *       would carry off the far edge, sneak until the body stops (vanilla's edge guard does the rest).</li>
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
    /** Ticks after touchdown the landing may be stuck with a sneak tap. */
    public static final int STICK_TICKS = 4;
    /** Ticks of the un-sneaked continuation checked for walking off the landing block. */
    public static final int STICK_LOOKAHEAD = 6;
    /** Horizontal speed (blocks/tick) below which the body counts as stable on its landing. */
    public static final double STABLE_SPEED = 0.03;
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
    /** Sneak tap on 1-block landings. */
    public boolean stickLanding = true;
    /** Tick-phase check before the edge. */
    public boolean tickPhase = true;
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
    private final AuthorshipBudget budget = new AuthorshipBudget();
    private final JumpBuffer jumpBuffer = new JumpBuffer();

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

    // jump tracking (authorship, sticking)
    private boolean prevOnGround = true;
    private boolean prevAssistedJump;
    private double prevFall;
    private int stickWindow;
    private int stickF, stickS;
    private boolean tookOff, touchedDown;

    // outputs
    private boolean jump;
    private boolean earlyJump;
    private int forward = KEEP;
    private int strafe = KEEP;
    private boolean corrected;
    private boolean sneak;
    private boolean withholdJump;
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
        trackJump(now);
        boolean targetOn = intentActive();
        if (targetOn) {
            search.setTarget(itx, ity, itz);
        } else {
            search.clearTarget();
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
        } else {
            stickWindow = 0;
            decideAir(now, yaw, sprinting, userForward, userStrafe, health, w);
        }
        prevAssistedJump = jump && !jumpBuffer.fired();
        simTicks = search.tickCount();
    }

    private void decideGround(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                              boolean jumpIn, float health, MovementPhysics.Voxels w) {
        if (edgeJump && !jumpIn && userForward > 0 && search.shouldEdgeJump(now, yaw, sprinting, health, w)
                && (!safeOnly || search.landDamage() <= 0f)) {
            jump = budget.allowance(AuthorshipBudget.FULL_HELP_MISS) > 0;
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
        edgeNoLanding = !jump && edgeJump && !jumpIn && userForward > 0
                && search.lastWalkScore() <= JumpSearch.NO_LANDING && search.lastJumpScore() <= JumpSearch.NO_LANDING;
        if (!jump) {
            sneak = stickLanding && !jumpIn && shouldStick(now, yaw, sprinting, userForward, userStrafe, w)
                    && budget.tryUse(AuthorshipBudget.FULL_HELP_MISS);
            if (sneak) {
                corrected = true;
            }
        }
        // predicted landing of a jump from here (the overlay's "if you jump now")
        double sc = search.evaluate(now, Math.max(userForward, 0), userStrafe, yaw, sprinting, true, health, w);
        capture(sc);
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
            stickWindow = STICK_TICKS;
        }
        prevOnGround = now.onGround;
        prevFall = now.fallDistance;
    }

    // ---- stick the landing ----------------------------------------------------------------------------------------

    private boolean shouldStick(MovementPhysics now, float yaw, boolean sprinting, int f, int s,
                                MovementPhysics.Voxels w) {
        if (stickWindow <= 0) {
            return false;
        }
        if (touchedDown) {
            stickF = f;
            stickS = s;
        } else if (f != stickF || s != stickS) {
            stickWindow = 0; // the user changed keys: their move now
            return false;
        }
        stickWindow--;
        double speed2 = now.vx * now.vx + now.vz * now.vz;
        if (speed2 < STABLE_SPEED * STABLE_SPEED) {
            stickWindow = 0;
            return false;
        }
        if (!smallLanding(now, w)) {
            return false;
        }
        cont.copyFrom(now);
        for (int t = 0; t < STICK_LOOKAHEAD; t++) {
            cont.tick(f, s, yaw, sprinting, false, w);
            if (!cont.supported(w)) {
                return true;
            }
        }
        return false;
    }

    /** The block under the feet has no standable neighbour in the direction of travel (a 1-block target). */
    private static boolean smallLanding(MovementPhysics now, MovementPhysics.Voxels w) {
        int cx = MovementPhysics.floor(now.x);
        int cy = MovementPhysics.floor(now.y - 0.01);
        int cz = MovementPhysics.floor(now.z);
        if (!w.solid(cx, cy, cz)) {
            return true; // hanging over the edge already
        }
        double speed = Math.sqrt(now.vx * now.vx + now.vz * now.vz);
        int sx = Math.abs(now.vx) >= 0.3 * speed ? (now.vx > 0 ? 1 : -1) : 0;
        int sz = Math.abs(now.vz) >= 0.3 * speed ? (now.vz > 0 ? 1 : -1) : 0;
        if (sx != 0 && standable(cx + sx, cy, cz, w)) {
            return false;
        }
        if (sz != 0 && standable(cx, cy, cz + sz, w)) {
            return false;
        }
        return !(sx != 0 && sz != 0 && standable(cx + sx, cy, cz + sz, w));
    }

    private static boolean standable(int x, int y, int z, MovementPhysics.Voxels w) {
        return w.solid(x, y, z) && !w.solid(x, y + 1, z) && !w.solid(x, y + 2, z);
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
        stickWindow = 0;
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

    /** Sneak this tick (stick the landing). Never set while the user holds jump. */
    public boolean sneak() {
        return sneak;
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
