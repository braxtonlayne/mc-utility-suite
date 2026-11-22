package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.assistant.SwingGate;
import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.core.api.IBodySimProvider;

/**
 * Keeps a combo going: right after one of our hits is sent it predicts the opponent's knockback arc (where and when
 * it comes down) and picks the sprint reset (W-release or S-tap, or none) and the steps that put us in reach,
 * sprinting and at full attack charge on the tick the opponent's hurt immunity ends. Keys only; the aim stays on the
 * target. Engine free (bodies from {@link IBodySimProvider}), allocation free after construction.
 * <p>
 * Model (see {@code integration/combo-keeper.notes.md}):
 * <ol>
 *   <li>{@link #begin}: the hit's knockback is applied to a scratch copy of the opponent (0.4 toward-source push, plus
 *   0.5 along our facing for a sprint hit, exactly as {@link HitTradeSolver}); our own copy is slowed to 0.6 after a
 *   sprint hit.</li>
 *   <li>Each candidate (reset kind and length x strafe x a short hold before closing in) is rolled forward tick by
 *   tick against the opponent. The opponent holds no keys while knocked up and {@link TradeInput#oForward}/
 *   {@link TradeInput#oStrafe} toward us once it is back on the ground, as the solver assumes.</li>
 *   <li>The candidate's hit tick is the first tick at which our attack is charged, the swing would arrive as the
 *   opponent's immunity ends ({@link SwingGate#HURT_HOLD} ticks early) and the opponent is in reach.</li>
 *   <li>Score: earlier hit better; a hit with a fresh sprint (released W or tapped S, then W again) is worth
 *   {@link #SPRINT_HIT_VALUE} ticks; ticks spent inside the opponent's reach while its attack is charged cost
 *   {@link #EXPOSED_COST}; small costs on longer resets, holds and strafes act as tie-breaks. Candidates that move
 *   into a flagged hazard or drop more than three blocks are rejected.</li>
 * </ol>
 * {@link #step} then plays the stored keys and ends the plan when the hit was due, the live opponent left the
 * predicted arc by more than {@link #DEVIATION} blocks, or {@link #MAX_PLAN_TICKS} passed.
 */
public final class ComboKeeper {

    public static final int RESET_NONE = 0;
    /** Release W for {@link #resetTicks()} ticks, then sprint again. */
    public static final int RESET_W_RELEASE = 1;
    /** Tap S for {@link #resetTicks()} ticks (also opens the distance a little), then sprint again. */
    public static final int RESET_S_TAP = 2;
    /** A plan never runs longer than this (one full sword cooldown plus slack). */
    public static final int MAX_PLAN_TICKS = 20;

    /** The live opponent may leave the predicted arc by this much (blocks) before the plan is dropped. */
    static final double DEVIATION = 0.6;
    /** Value of a sprint (full-knockback) follow-up hit, in ticks of delay it is worth waiting for. */
    static final double SPRINT_HIT_VALUE = 4.0;
    /** Cost per tick spent inside the opponent's reach while its attack is charged (before our hit). */
    static final double EXPOSED_COST = 0.6;
    /** Ending a hair's breadth from the opponent (centers closer than this) means overrunning it. */
    static final double TOO_CLOSE = 0.8;
    static final double TOO_CLOSE_COST = 1.0;
    /** Tie-break costs: shorter resets, no hold and no strafe win when everything else is equal. */
    static final double RESET_TICK_COST = 0.05;
    static final double HOLD_TICK_COST = 0.04;
    static final double STRAFE_COST = 0.03;
    static final double WALL_COST = 0.5;
    static final double FALL_COST = 1.0;
    /** Dropping further than this (vanilla fall damage starts above 3 blocks) rejects a candidate. */
    static final double FALL_REJECT = 3.0;

    /** Candidate resets in tie-break order: (kind, ticks). */
    private static final int[] RESET_KIND = {RESET_NONE, RESET_W_RELEASE, RESET_S_TAP, RESET_W_RELEASE,
            RESET_S_TAP, RESET_W_RELEASE};
    private static final int[] RESET_LEN = {0, 1, 1, 2, 2, 3};
    /** Strafe options in tie-break order. */
    private static final int[] STRAFES = {0, 1, -1};
    /** Ticks of standing still (no forward key) after the reset before closing in. */
    private static final int[] HOLDS = {0, 2};

    private static final double REJECTED = -1.0e6;

    private final BodySim self;
    private final BodySim opp;
    private final BodySim selfStart;
    private final BodySim oppStart;

    /** Predicted opponent feet position at plan tick t (index 0 = right after the knockback was applied). */
    private final double[] arcX = new double[MAX_PLAN_TICKS + 1];
    private final double[] arcY = new double[MAX_PLAN_TICKS + 1];
    private final double[] arcZ = new double[MAX_PLAN_TICKS + 1];
    /** Keys held during plan tick t (1-based). */
    private final int[] keyF = new int[MAX_PLAN_TICKS + 2];
    private final int[] keyS = new int[MAX_PLAN_TICKS + 2];
    private final boolean[] keySprint = new boolean[MAX_PLAN_TICKS + 2];

    // rollout results (scratch)
    private int rollHit;
    private boolean rollSprintHit;
    private int rollAir;

    // plan
    private boolean active;
    private int kind;
    private int resetLen;
    private int hitTick;
    private int elapsed;
    private int air;
    private double landX, landY, landZ;
    private int forward;
    private int strafe;
    private boolean sprint;

    public ComboKeeper(IBodySimProvider sims) {
        this.self = sims.newBody();
        this.opp = sims.newBody();
        this.selfStart = sims.newBody();
        this.oppStart = sims.newBody();
    }

    /**
     * Plans the follow-up to a hit that was just sent. {@code in} holds both fighters BEFORE the knockback is
     * applied; the keeper applies it itself (0.4, plus the 0.5 sprint bonus along our facing when
     * {@code sprintHit}) and rolls both bodies forward.
     *
     * @param allowBackTap false when a hazard is behind us (S-tap is then never chosen)
     * @return true when a plan exists; false = no follow-up worth steering (the caller keeps its default reset)
     */
    public boolean begin(TradeInput in, boolean sprintHit, boolean allowBackTap) {
        active = false;
        clearKeys();
        if (in.hazardFront) {
            return false; // every follow-up closes in; nothing to steer
        }
        selfStart.set(in.sx, in.sy, in.sz, in.svx, in.svy, in.svz, in.sOnGround);
        oppStart.set(in.ox, in.oy, in.oz, in.ovx, in.ovy, in.ovz, in.oOnGround);
        oppStart.knockback(HitTradeSolver.KNOCKBACK, in.sx - in.ox, in.sz - in.oz);
        if (sprintHit) {
            double yaw = Math.toRadians(in.sYaw);
            oppStart.knockback(HitTradeSolver.SPRINT_KNOCKBACK, Math.sin(yaw), -Math.cos(yaw));
            selfStart.scaleHorizontal(HitTradeSolver.SPRINT_HIT_SLOWDOWN);
        }

        int bestR = -1;
        int bestS = 0;
        int bestH = 0;
        double best = REJECTED;
        boolean backOk = allowBackTap && !in.hazardBack;
        for (int r = 0; r < RESET_KIND.length; r++) {
            if (RESET_KIND[r] == RESET_S_TAP && !backOk) {
                continue;
            }
            for (int si = 0; si < STRAFES.length; si++) {
                int s = STRAFES[si];
                if ((s > 0 && in.hazardLeft) || (s < 0 && in.hazardRight)) {
                    continue;
                }
                for (int hi = 0; hi < HOLDS.length; hi++) {
                    double sc = rollout(in, sprintHit, RESET_KIND[r], RESET_LEN[r], s, HOLDS[hi], false);
                    if (sc > best) {
                        best = sc;
                        bestR = r;
                        bestS = s;
                        bestH = HOLDS[hi];
                    }
                }
            }
        }
        if (bestR < 0 || best <= REJECTED * 0.5) {
            return false;
        }
        rollout(in, sprintHit, RESET_KIND[bestR], RESET_LEN[bestR], bestS, bestH, true);
        kind = RESET_KIND[bestR];
        resetLen = RESET_LEN[bestR];
        hitTick = rollHit;
        air = rollAir;
        landX = arcX[hitTick];
        landY = arcY[hitTick];
        landZ = arcZ[hitTick];
        elapsed = 0;
        active = true;
        applyKeys(1);
        return true;
    }

    /**
     * Advances the plan one tick against the live state and refreshes {@link #forward()}.. {@link #sprint()}.
     * Ends the plan (returns false) when the next hit was due, the opponent left the predicted arc by more than a
     * tolerance, or {@link #MAX_PLAN_TICKS} passed.
     */
    public boolean step(TradeInput in) {
        if (!active) {
            return false;
        }
        elapsed++;
        if (elapsed > hitTick || elapsed > MAX_PLAN_TICKS) {
            reset();
            return false;
        }
        double dx = in.ox - arcX[elapsed];
        double dy = in.oy - arcY[elapsed];
        double dz = in.oz - arcZ[elapsed];
        if (dx * dx + dy * dy + dz * dz > DEVIATION * DEVIATION) {
            reset();
            return false;
        }
        applyKeys(Math.min(elapsed + 1, hitTick));
        return true;
    }

    public boolean active() {
        return active;
    }

    public void reset() {
        active = false;
        elapsed = 0;
        clearKeys();
    }

    /** {@link #RESET_NONE}, {@link #RESET_W_RELEASE} or {@link #RESET_S_TAP}. */
    public int resetKind() {
        return active ? kind : RESET_NONE;
    }

    /** Ticks the reset is held (1..3). */
    public int resetTicks() {
        return active ? resetLen : 0;
    }

    /** Forward key this tick: +1, 0 or -1. */
    public int forward() {
        return forward;
    }

    /** Strafe this tick: +1 = left key, -1 = right key, 0 = none (same convention as {@link HitTradeSolver}). */
    public int strafe() {
        return strafe;
    }

    public boolean sprint() {
        return sprint;
    }

    /** Ticks from now until the planned next hit (0 = swing now if the swing gate agrees). */
    public int hitInTicks() {
        return active ? Math.max(0, hitTick - elapsed) : 0;
    }

    /** Where the opponent is predicted to be when it can be hit again (feet). */
    public double landX() {
        return landX;
    }

    public double landY() {
        return landY;
    }

    public double landZ() {
        return landZ;
    }

    /** Ticks from the hit until the opponent touches the ground again (0 when it never left it). */
    public int airTicks() {
        return air;
    }

    private void clearKeys() {
        forward = 0;
        strafe = 0;
        sprint = false;
    }

    private void applyKeys(int t) {
        forward = keyF[t];
        strafe = keyS[t];
        sprint = keySprint[t];
    }

    /**
     * Rolls one candidate forward from the post-knockback bodies. Sets {@link #rollHit}, {@link #rollSprintHit} and
     * {@link #rollAir}; with {@code record} the opponent arc and the keys are stored for {@link #step}.
     */
    private double rollout(TradeInput in, boolean sprintHit, int resetKind, int resetTicks, int s, int hold,
                           boolean record) {
        self.copyFrom(selfStart);
        opp.copyFrom(oppStart);
        if (record) {
            arcX[0] = opp.x();
            arcY[0] = opp.y();
            arcZ[0] = opp.z();
        }
        double sPeriod = Math.max(1.0, in.sCooldownTicks - 0.5); // vanilla: full strength 0.5 tick early
        // the hit just sent restarted our cooldown; a caller that already counted it down passes the larger value
        double sCharge = Math.max(in.sReadyIn, sPeriod);
        double oCharge = in.oReadyIn;
        int earliest = Math.max(1, HitTradeSolver.HURT_IMMUNITY - SwingGate.HURT_HOLD);
        double reachSq = in.sReach * in.sReach;
        double oReachSq = in.oReach * in.oReach;
        double startY = in.sy;
        // after a sprint hit the sprint must be re-pressed; without a reset it does not come back
        boolean fresh = !sprintHit;
        boolean leftGround = !in.oOnGround || opp.vy() > 0.0;
        int airT = 0;
        boolean wall = false;
        int exposed = 0;
        rollHit = -1;
        rollSprintHit = false;

        for (int t = 1; t <= MAX_PLAN_TICKS; t++) {
            double dx = opp.x() - self.x();
            double dz = opp.z() - self.z();
            float sYaw = in.sYaw;
            float oYaw = in.sYaw + 180f;
            if (dx * dx + dz * dz > 1.0e-8) {
                sYaw = HitTradeSolver.yawToward(dx, dz);
                oYaw = HitTradeSolver.yawToward(-dx, -dz);
            }
            int f;
            boolean sp;
            if (t <= resetTicks) {
                f = resetKind == RESET_S_TAP ? -1 : 0;
                sp = false;
                if (sprintHit) {
                    fresh = true; // released (or reversed) the forward key: the next W press sprints again
                }
            } else if (t <= resetTicks + hold) {
                f = 0;
                sp = false;
            } else {
                f = 1;
                sp = fresh;
            }
            if (record) {
                keyF[t] = f;
                keyS[t] = s;
                keySprint[t] = sp;
            }
            self.tick(f, s, sYaw, sp, false);

            boolean grounded = opp.onGround() && opp.vy() <= 0.0;
            if (grounded) {
                boolean oSprint = in.oSprinting && in.oForward > 0;
                opp.tick(in.oForward, in.oStrafe, oYaw, oSprint, false);
            } else {
                opp.tick(0, 0, oYaw, false, false);
            }
            if (!opp.onGround()) {
                leftGround = true;
            } else if (leftGround && airT == 0) {
                airT = t;
            }
            if (record) {
                arcX[t] = opp.x();
                arcY[t] = opp.y();
                arcZ[t] = opp.z();
            }
            if (self.horizontalCollision()) {
                wall = true;
            }
            sCharge -= 1.0;
            oCharge -= 1.0;

            double drop = Math.max(self.fallDistance(), startY - self.y());
            if (drop > FALL_REJECT) {
                return REJECTED;
            }
            if (t >= earliest && sCharge <= 0.0
                    && HitTradeSolver.reachSq(self.x(), self.y(), self.z(), opp.x(), opp.y(), opp.z()) <= reachSq) {
                rollHit = t;
                rollSprintHit = sp && f > 0;
                break;
            }
            if (oCharge <= 0.0 && t >= in.sHurtTicks
                    && HitTradeSolver.reachSq(opp.x(), opp.y(), opp.z(), self.x(), self.y(), self.z()) <= oReachSq) {
                exposed++;
            }
        }
        if (rollHit < 0) {
            rollAir = airT;
            return REJECTED;
        }
        double endOx = opp.x();
        double endOz = opp.z();
        if (record && leftGround && airT == 0) {
            // still in the air at the hit: keep its arc going (no keys while airborne) to find the landing tick
            for (int t = rollHit + 1; t <= MAX_PLAN_TICKS && airT == 0; t++) {
                opp.tick(0, 0, in.sYaw + 180f, false, false);
                if (opp.onGround()) {
                    airT = t;
                }
            }
            if (airT == 0) {
                airT = MAX_PLAN_TICKS;
            }
        }
        rollAir = airT;
        if (record) {
            // the swing tick keeps the closing keys
            for (int t = rollHit + 1; t < keyF.length; t++) {
                keyF[t] = keyF[rollHit];
                keyS[t] = keyS[rollHit];
                keySprint[t] = keySprint[rollHit];
            }
        }
        double sc = -rollHit;
        if (rollSprintHit) {
            sc += SPRINT_HIT_VALUE;
        }
        sc -= EXPOSED_COST * exposed;
        double cx = endOx - self.x();
        double cz = endOz - self.z();
        if (cx * cx + cz * cz < TOO_CLOSE * TOO_CLOSE) {
            sc -= TOO_CLOSE_COST;
        }
        sc -= RESET_TICK_COST * resetTicks + HOLD_TICK_COST * hold + (s != 0 ? STRAFE_COST : 0.0);
        if (wall) {
            sc -= WALL_COST;
        }
        if (Math.max(self.fallDistance(), startY - self.y()) > 1.0) {
            if (!self.onGround()) {
                return REJECTED; // still falling at the hit: the bottom is unknown (a pit or a ledge)
            }
            sc -= FALL_COST;
        }
        return sc;
    }
}
