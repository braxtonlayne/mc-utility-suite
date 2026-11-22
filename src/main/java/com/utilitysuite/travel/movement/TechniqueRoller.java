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

    private static final double Y_EPS = 1.0e-6;
    private static final float YAW_EPS = 1.0e-3f;

    private final MovementPhysics body = new MovementPhysics();

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
        float maxStep = 0f;
        double yawTravel = 0.0;
        int departures = 0;
        int changes = 0;
        int prevStrafe = userStrafe;
        int prevForward = 1;
        boolean sprintKept = sprinting;
        double maxFall = 0.0;
        int land = -1;
        for (int i = 0; i < FlightScript.MAX_TICKS; i++) {
            int strafe;
            int forward = 1;
            float goal;
            boolean jump = false;
            if (i <= jumpAt) {
                strafe = i > jumpAt - c.groundStrafeTicks ? c.groundStrafe : userStrafe;
                goal = c.takeoffYaw;
                if (i == jumpAt) {
                    if (!body.onGround) {
                        return false; // walked off before the takeoff tick
                    }
                    jump = true;
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
            }
            float step = 0f;
            if (rate > 0f && goal == goal) {
                step = goal - yawNow;
                if (step > rate) {
                    step = rate;
                } else if (step < -rate) {
                    step = -rate;
                }
            }
            yawNow += step;
            if (jump && Math.abs(yawNow - c.takeoffYaw) > YAW_EPS) {
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
            double fallBefore = body.fallDistance;
            body.tick(forward, strafe, yawNow, sprint, jump, false, w);
            ticks++;
            out.x[i] = body.x;
            out.y[i] = body.y;
            out.z[i] = body.z;
            out.length = i + 1;
            if (body.horizontalCollision) {
                return false;
            }
            if (i > jumpAt) {
                if (body.onGround) {
                    if (Math.abs(body.y - top) > Y_EPS || !target.contains(body.x, body.z)) {
                        return false;
                    }
                    float damage = MovementPhysics.fallDamage(Math.max(maxFall, fallBefore));
                    if ((safeOnly && damage > 0f) || damage >= health) {
                        return false;
                    }
                    land = i;
                    break;
                }
                if (body.vy < 0.0 && body.y < top - 1.0e-3) {
                    return false; // below the top on the way down: it cannot land on it any more
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
        return true;
    }
}
