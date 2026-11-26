package com.utilitysuite.travel.movement;

/**
 * Rolls one {@link TechniqueTemplates.Candidate} through the exact-shape replica from the current body, records its
 * inputs and predicted positions into a {@link FlightScript}, validates it and scores it. Allocation free: one scratch
 * body, reused every roll.
 * <p>
 * A roll is valid when: the takeoff tick starts on the ground; no tick touches a wall (a touch zeroes the speed and
 * drops sprint); the body touches down on the target's real top inside its region; the landing costs no health with
 * {@code safeOnly} and never the player's life; the no-key stop point is on the top or within
 * {@link #HOLD_REACH} of it (the landing hold brakes and guards the rest); every view step stays within the level's
 * rate and the view never moves without {@code yawAllowed}; the takeoff yaw is reached by the takeoff tick.
 * <p>
 * The corner-cut family and the neo are rolled with {@link CoSteerModel}: the view follows each goal the way the
 * core's smooth follower does (a request on tick i is in the physics from tick i + 1, and closes the error gradually),
 * so their takeoff heading is a goal, not a condition. The other families still move the view at the full rate from the same
 * tick; the client cannot fly that, so their yaw variants need the same treatment before they are relied on.
 */
final class TechniqueRoller {

    /**
     * How far (blocks) the no-key stop point may lie past the top's edge for the landing hold (air brake already
     * spent, sneak guard and latch on the top) to keep the body on it. Stable landings always outrank these.
     */
    static final double HOLD_REACH = 1.0;
    /** Margin beyond which a landing counts as fully comfortable (scores stop growing). */
    static final double MARGIN_ENOUGH = 0.5;
    /** Margin score bucket: landings within one bucket compare by their other costs. */
    static final double MARGIN_BUCKET = 0.05;

    /**
     * A script must touch down at least this far inside the top's support region: the replay follows the predicted
     * line within {@link FlightScript#DRIFT_LIMIT}, so a touchdown nearer the rim than that is a miss waiting for the
     * first small difference (seen in the client on the fence corner, 2026-11-22).
     */
    static final double TOUCH_MARGIN = FlightScript.DRIFT_LIMIT;

    /** Score per block between the stop point and the next link (tie-breaker; 4 blocks = 2, under a key change's 3). */
    static final double NEXT_LINK_WEIGHT = 0.5;
    /** The next-link bias (null: none). */
    NextLinkBias nextLink;

    private static final double Y_EPS = 1.0e-6;
    private static final float YAW_EPS = 1.0e-3f;

    private final MovementPhysics body = new MovementPhysics();
    private final CoSteerModel follower = new CoSteerModel();
    /** The commitment's co-steer share before the takeoff and in the air (live: 0.3 / 0.6 Co-pilot, 0.5 / 1 Takeover). */
    float armedAuthority = 1f;
    float flightAuthority = 1f;
    /**
     * The commitment's key-change limits for the coming jump (the level's, see {@link KeyChangeLimiter}): the air keys
     * of a roll go through the same limiter the replay's go through ({@link JumpCommit#limit}), so the script holds
     * the keys the replay really delivers and its line is the line the body flies. A candidate that only lands with a
     * key change inside the reaction delay is simply rolled with that change late, and is valid or not as it comes out.
     */
    int keyMaxChanges;
    int keyMinHold = 1;
    int keyReaction;
    private final KeyChangeLimiter limiter = new KeyChangeLimiter();
    /** The candidates' goals are already capped by the level's total; the model's own allowance is left open. */
    private static final double FOLLOW_TOTAL = 1.0e6;

    /** Slack past one tick of travel within which a roll below the top may still step up onto it (blocks). */
    private static final double STEP_REACH = 0.05;

    /** Replica ticks of the last roll. */
    int ticks;
    /** Score of the last valid roll (higher is better). */
    double score;

    /**
     * @param shift ticks of the candidate's timeline already played (0 = a fresh candidate rolled from now)
     * @return true when the roll is valid; {@code out} then holds the script from now (index 0 = next tick)
     */
    boolean roll(MovementPhysics now, float baseYaw, boolean sprinting, int userStrafe,
                 TechniqueTemplates.Candidate c, int shift, SupportRegion target, boolean yawAllowed,
                 float maxYawDegPerTick, boolean safeOnly, float health, MovementPhysics.Voxels w, FlightScript out) {
        ticks = 0;
        score = -Double.MAX_VALUE;
        out.clear();
        int jumpAt = c.jump - shift;
        if (jumpAt >= 0 && !now.onGround) {
            return false;
        }
        if (!yawAllowed && Math.abs(c.takeoffYaw - baseYaw) > YAW_EPS) {
            return false;
        }
        float rate = yawAllowed ? Math.max(0f, maxYawDegPerTick) : 0f;
        double top = target.top();
        body.copyFrom(now);
        out.name = TechniqueTemplates.name(c.template);
        out.takeoffIndex = jumpAt;
        float yawNow = baseYaw;
        // the corner cut and the neo are rolled with the real view dynamics; the other families still assume a view that moves at
        // the full rate (their yaw variants are not verified in the client, see the run note of 2026-11-22)
        boolean follow = (c.template == TechniqueTemplates.CUT || c.template == TechniqueTemplates.NEO) && rate > 0f;
        follower.reset();
        float maxStep = 0f;
        double yawTravel = 0.0;
        int departures = 0;
        int changes = 0;
        int prevStrafe = userStrafe;
        int prevForward = 1;
        boolean sprintKept = sprinting;
        double maxFall = 0.0;
        int land = -1;
        // the run-up strafe held into the takeoff (the replay notes the same to the commitment)
        int heldStrafe = KeyChangeLimiter.KEEP;
        int heldAge = 0;
        limiter.maxKeyChanges = keyMaxChanges;
        limiter.start(KeyChangeLimiter.KEEP, KeyChangeLimiter.KEEP, 0);
        for (int i = 0; i < FlightScript.MAX_TICKS; i++) {
            int strafe;
            int forward = 1;
            float goal;
            boolean jump = false;
            if (i <= jumpAt) {
                strafe = i > jumpAt - c.groundStrafeTicks ? c.groundStrafe
                        : (i < c.earlyEnd - shift ? c.earlyStrafe : userStrafe);
                goal = c.takeoffYaw;
                if (i == jumpAt) {
                    if (!body.onGround) {
                        return false; // walked off before the takeoff tick
                    }
                    jump = true;
                }
                if (strafe == userStrafe) {
                    heldAge = 0;
                } else {
                    heldAge = heldAge > 0 && strafe == heldStrafe ? heldAge + 1 : 1;
                    heldStrafe = strafe;
                }
                if (i == jumpAt) {
                    limiter.start(KeyChangeLimiter.KEEP, heldStrafe, heldAge);
                }
            } else {
                int a = i - jumpAt - 1;
                int k = 0;
                while (k < c.segments - 1 && a >= c.segTicks[k]) {
                    a -= c.segTicks[k];
                    k++;
                }
                forward = c.segForward[k];
                strafe = c.segStrafe[k];
                goal = c.segYaw[k];
                // the keys as the commitment lets them out (the user holds forward and the strafe of the run-up)
                int wantF = forward == 1 ? KeyChangeLimiter.KEEP : forward;
                int wantS = strafe == userStrafe ? KeyChangeLimiter.KEEP : strafe;
                int air = i - jumpAt - 1;
                // (a script is planned before its takeoff: its strafe key may go down with the jump, see the limiter)
                boolean inReaction = air < keyReaction && !(air == 0 && limiter.withTheJump(wantF, wantS));
                int d = limiter.decide(wantF, wantS, keyMinHold, inReaction);
                if (d == KeyChangeLimiter.HOLD) {
                    wantF = limiter.choiceF;
                    wantS = limiter.choiceS;
                } else if (d == KeyChangeLimiter.NONE) {
                    wantF = KeyChangeLimiter.KEEP;
                    wantS = KeyChangeLimiter.KEEP;
                }
                limiter.settle(wantF, wantS);
                forward = wantF == KeyChangeLimiter.KEEP ? 1 : wantF;
                strafe = wantS == KeyChangeLimiter.KEEP ? userStrafe : wantS;
            }
            float step = 0f;
            if (follow) {
                step = 0f; // this tick's physics uses the view as it is; the request turns it over the frames after
            } else if (rate > 0f && goal == goal) {
                step = goal - yawNow;
                if (step > rate) {
                    step = rate;
                } else if (step < -rate) {
                    step = -rate;
                }
            }
            yawNow += step;
            if (!follow && jump && Math.abs(yawNow - c.takeoffYaw) > YAW_EPS) {
                return false; // the view cannot reach the takeoff yaw at the co-steer rate in time
            }
            float absStep = Math.abs(step);
            if (absStep > maxStep) {
                maxStep = absStep;
            }
            yawTravel += absStep;
            if (strafe != userStrafe) {
                departures++;
            }
            if (forward != 1) {
                departures++;
            }
            if (strafe != prevStrafe || forward != prevForward) {
                changes++;
            }
            prevStrafe = strafe;
            prevForward = forward;
            // vanilla: no sprint without forward; once released the sprint stays off for the rest of the script
            if (forward <= 0) {
                sprintKept = false;
            }
            boolean sprint = sprintKept;
            out.forward[i] = (byte) forward;
            out.strafe[i] = (byte) strafe;
            out.jump[i] = jump;
            out.sprint[i] = sprint;
            out.yaw[i] = yawNow;
            out.goal[i] = follow ? goal : yawNow;
            double fallBefore = body.fallDistance;
            body.tick(forward, strafe, yawNow, sprint, jump, false, w);
            ticks++;
            out.x[i] = body.x;
            out.y[i] = body.y;
            out.z[i] = body.z;
            out.length = i + 1;
            if (follow) {
                // the co-steer follower of the core, modelled: smooth, so the view lags the goal by several ticks
                float before = yawNow;
                if (goal == goal) {
                    yawNow = follower.tick(yawNow, goal, rate * 20.0, FOLLOW_TOTAL,
                            i <= jumpAt ? armedAuthority : flightAuthority);
                } else {
                    follower.lapse();
                }
                float turned = Math.abs(yawNow - before);
                if (turned > maxStep) {
                    maxStep = turned;
                }
                yawTravel += turned;
            }
            // a neo may graze the pillar in the air: the replica slides along it and drops the sprint like vanilla on a
            // contact that is not minor, and the brute force of J36 found most of its landings that way
            if (body.horizontalCollision && (!c.airSlide || i <= jumpAt)) {
                return false;
            }
            if (i > jumpAt) {
                if (body.onGround) {
                    if (Math.abs(body.y - top) > Y_EPS || !target.contains(body.x, body.z)) {
                        return false;
                    }
                    if (target.margin(body.x, body.z) < TOUCH_MARGIN) {
                        return false; // a touchdown on the very rim: the replay's own tolerance would miss it
                    }
                    float damage = MovementPhysics.fallDamage(Math.max(maxFall, fallBefore));
                    if ((safeOnly && damage > 0f) || damage >= health) {
                        return false;
                    }
                    land = i;
                    break;
                }
                // below the top on the way down it can still land on it by a step-up on the landing tick (a stair's
                // low step, then its top: the slab-stair capture of 2026-11-26), but only within this tick's travel of
                // the top; more than a step below, or further out, it cannot
                if (body.vy < 0.0 && body.y < top - 1.0e-3 && (body.y < top - MovementPhysics.STEP_HEIGHT - 1.0e-3
                        || target.margin(body.x, body.z) < -(Math.abs(body.vx) + Math.abs(body.vz) + STEP_REACH))) {
                    return false;
                }
            }
            maxFall = Math.max(maxFall, fallBefore);
        }
        if (land < 0) {
            return false;
        }
        if (maxStep > rate + YAW_EPS) {
            return false;
        }
        target.computeStop(body.x, body.z, body.vx, body.vz, 0, 0, 0f, false, MovementPhysics.BASE_SPEED,
                body.slipperiness, false);
        double margin = target.margin(target.stopX(), target.stopZ());
        if (margin < -HOLD_REACH) {
            return false;
        }
        out.landIndex = land;
        out.margin = margin;
        out.maxYawStep = maxStep;
        out.usesYaw = yawTravel > YAW_EPS;
        double bucket = Math.floor(Math.min(margin, MARGIN_ENOUGH) / MARGIN_BUCKET);
        score = bucket * 1000.0 - departures * 2.0 - changes * 3.0 - yawTravel * 0.1 - (out.usesYaw ? 10.0 : 0.0);
        if (nextLink != null && nextLink.valid() && margin >= NextLinkBias.FLOOR) {
            // next-link bias (roadmap 2.1), a pure tie-breaker: inside one margin bucket, the stop point nearer the
            // next link of the chain (at most NEXT_LINK_WEIGHT * MAX_DIST, below one key change)
            score -= NEXT_LINK_WEIGHT * nextLink.distance(target.stopX(), target.stopZ());
        }
        return true;
    }
}
