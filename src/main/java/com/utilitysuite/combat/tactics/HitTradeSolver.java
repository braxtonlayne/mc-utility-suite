package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.core.api.IBodySimProvider;

/**
 * Hit-trade solver: for each of the nine movement key combinations (forward/none/back x left/none/right), simulates
 * both fighters a few ticks ahead, knockback included, and scores hits landed minus hits taken. The best combination
 * replaces the fixed spacing band: step in right after the opponent swung, stay just out of reach while its attack is
 * charged. Keys only; it never asks for a rotation.
 * <p>
 * Model per simulated tick (see {@code integration/hit-trade-solver.notes.md}):
 * <ol>
 *   <li>Both fighters face each other (yaw from positions), then move: we hold the combination (sprinting when moving
 *   forward and not in a re-sprint delay), the opponent holds {@link TradeInput#oForward}/{@link TradeInput#oStrafe}.
 *   </li>
 *   <li>Cooldown and hurt-immunity counters count down.</li>
 *   <li>A fighter attacks when its attack is charged, the other is in reach (eye to nearest point of the 0.6 x 1.8
 *   box, as {@code CombatSnapshot.reachDistanceSq}) and the other is not in its 10-tick hurt immunity. When both
 *   could hit on the same tick we resolve ours first at half credit (it is a coin flip in the real game); the
 *   opponent's swing is held to the next tick and lands only if it still reaches after our knockback.</li>
 *   <li>A hit applies vanilla knockback (0.4 toward-source push, plus 0.5 along the attacker's facing for a sprint
 *   hit, after which the attacker is slowed to 0.6 and must re-sprint).</li>
 * </ol>
 * Score = discounted hits landed - {@link TradeInput#takenWeight} x discounted hits taken + small end-position terms;
 * combinations stepping into a flagged hazard are rejected. Allocation free after construction.
 */
public final class HitTradeSolver {

    /** Number of key combinations: index = (forward + 1) * 3 + (strafe + 1). */
    public static final int COMBOS = 9;

    /** Score of a rejected combination. */
    public static final double REJECTED = -1.0e6;

    /** Player eye height while standing. */
    static final double EYE = 1.62;
    static final double HALF_WIDTH = 0.3;
    static final double HEIGHT = 1.8;
    /** Ticks a hit victim ignores further hits (vanilla invulnerableTime 20, hits land only above 10). */
    static final int HURT_IMMUNITY = 10;
    /** A mob without a weapon cooldown hits at most this often. */
    static final int MOB_ATTACK_INTERVAL = 20;
    static final double KNOCKBACK = 0.4;
    static final double SPRINT_KNOCKBACK = 0.5;
    static final double SPRINT_HIT_SLOWDOWN = 0.6;
    /** Knockback strength of a hit blocked by a shield ({@code LivingEntity.blockedByShield}). */
    static final double SHIELD_KNOCKBACK = 0.5;
    /** Each later tick's hit counts this much less than the tick before (rollout uncertainty grows). */
    static final double HIT_DISCOUNT = 0.95;
    /** Credit of our hit when both would hit on the same tick. */
    static final double SIMULTANEOUS_CREDIT = 0.5;
    /** Weight of the end-position terms (well below one hit). */
    static final double POSITION_WEIGHT = 0.15;
    /** Width of the "just outside reach" band. */
    static final double SPACING_BAND = 0.6;
    /** Width of the band past a learned reach ({@link TradeInput#hoverRelative}) when ours is not longer. */
    static final double LEARNED_BAND = 0.25;
    /** "Nearly charged" at the end of the horizon: at most this many ticks until ready. */
    static final double NEARLY_READY = 2.0;
    /** Sprint closing speed (blocks per tick, rounded down) used to judge how far away our window still is. */
    static final double CLOSE_SPEED = 0.25;
    /** At most this much distance counts as closeable in our window. */
    static final double MAX_CLOSE = 2.0;
    static final double WALL_PENALTY = 0.2;
    static final double FALL_PENALTY = 1.0;
    /** A rollout that drops further than this (vanilla fall damage starts above 3 blocks) is rejected. */
    static final double FALL_REJECT = 3.0;
    /** A jump must beat the same keys without jumping by this much (jumping adds noise and loses control). */
    static final double JUMP_MARGIN = 0.1;
    /** The previous answer is kept while it scores within this of the best (stops key flicker). */
    static final double HYSTERESIS = 0.08;
    static final int MAX_HORIZON = 12;

    /** Tie-break order: neutral strafe first, then standing still before stepping. */
    private static final int[] ORDER = {4, 1, 7, 3, 5, 0, 2, 6, 8};

    private final BodySim self;
    private final BodySim opp;

    private boolean valid;
    private int forward;
    private int strafe;
    private boolean jump;
    private boolean sprint;
    private double score;
    private final double[] scores = new double[COMBOS];
    private final boolean[] jumps = new boolean[COMBOS];

    private int prevCombo = -1;

    public HitTradeSolver(IBodySimProvider sims) {
        this.self = sims.newBody();
        this.opp = sims.newBody();
    }

    /** Forgets the previous answer (new target or engagement), so the next solve has no hysteresis. */
    public void reset() {
        prevCombo = -1;
    }

    /**
     * Solves for the current tick. Afterwards {@link #valid()} tells whether any combination was acceptable; when it
     * is false the caller falls back to its spacing band.
     */
    public void solve(TradeInput in) {
        int horizon = Math.max(1, Math.min(MAX_HORIZON, in.horizon));
        for (int c = 0; c < COMBOS; c++) {
            int f = c / 3 - 1;
            int s = c % 3 - 1;
            if (rejected(in, f, s)) {
                scores[c] = REJECTED;
                jumps[c] = false;
                continue;
            }
            double walk = rollout(in, f, s, false, horizon);
            boolean j = false;
            if (in.allowJump) {
                double hop = rollout(in, f, s, true, horizon);
                if (hop > walk + JUMP_MARGIN) {
                    walk = hop;
                    j = true;
                }
            }
            scores[c] = walk;
            jumps[c] = j;
        }

        int best = -1;
        double bestScore = REJECTED;
        for (int k = 0; k < COMBOS; k++) {
            int c = ORDER[k];
            if (scores[c] > bestScore) {
                bestScore = scores[c];
                best = c;
            }
        }
        if (best < 0 || bestScore <= REJECTED * 0.5) {
            valid = false;
            forward = 0;
            strafe = 0;
            jump = false;
            sprint = false;
            score = REJECTED;
            prevCombo = -1;
            return;
        }
        if (prevCombo >= 0 && scores[prevCombo] > REJECTED * 0.5
                && scores[prevCombo] >= bestScore - HYSTERESIS) {
            best = prevCombo;
        }
        valid = true;
        forward = best / 3 - 1;
        strafe = best % 3 - 1;
        jump = jumps[best];
        sprint = forward > 0;
        score = scores[best];
        prevCombo = best;
    }

    private static boolean rejected(TradeInput in, int f, int s) {
        return (f > 0 && in.hazardFront) || (f < 0 && in.hazardBack)
                || (s > 0 && in.hazardLeft) || (s < 0 && in.hazardRight);
    }

    /** Simulates one combination; returns its score. */
    private double rollout(TradeInput in, int f, int s, boolean hold, int horizon) {
        self.set(in.sx, in.sy, in.sz, in.svx, in.svy, in.svz, in.sOnGround)
                .movement(in.sSpeed, in.sJumpBoost, in.sBodySprinting);
        opp.set(in.ox, in.oy, in.oz, in.ovx, in.ovy, in.ovz, in.oOnGround)
                .movement(in.oSpeed, in.oJumpBoost, in.oBodySprinting);

        double sCharge = in.sReadyIn;
        double sPeriod = Math.max(1.0, in.sCooldownTicks - 0.5); // vanilla: full strength 0.5 tick early
        double oCharge = in.oReadyIn;
        double oPeriod = in.oUsesCooldown ? Math.max(1.0, in.oCooldownTicks - 0.5) : MOB_ATTACK_INTERVAL;
        int sHurt = in.sHurtTicks;
        int oHurt = in.oHurtTicks;
        int sSprintWait = 0;
        int oSprintWait = 0;
        float sYaw = in.sYaw;
        float oYaw = in.sYaw + 180f;
        double startY = in.sy;
        double sReachSq = in.sReach * in.sReach;
        double oReachSq = in.oReach * in.oReach;

        double landed = 0.0;
        double taken = 0.0;
        boolean wall = false;
        double weight = 1.0;

        for (int t = 1; t <= horizon; t++) {
            double dx = opp.x() - self.x();
            double dz = opp.z() - self.z();
            if (dx * dx + dz * dz > 1.0e-8) {
                sYaw = yawToward(dx, dz);
                oYaw = yawToward(-dx, -dz);
            }
            boolean sSprint = f > 0 && sSprintWait <= 0;
            boolean oSprint = in.oSprinting && in.oForward > 0 && oSprintWait <= 0 && in.oUsesCooldown;
            self.tick(f, s, sYaw, sSprint, hold);
            opp.tick(in.oForward, in.oStrafe, oYaw, oSprint, false);
            if (self.horizontalCollision() && taken == 0.0) {
                wall = true; // only our own walking into a wall; being knocked into one is scored by the hits
            }
            sCharge -= 1.0;
            oCharge -= 1.0;
            if (sHurt > 0) sHurt--;
            if (oHurt > 0) oHurt--;
            if (sSprintWait > 0) sSprintWait--;
            if (oSprintWait > 0) oSprintWait--;

            boolean sCan = sCharge <= 0.0 && oHurt == 0
                    && reachSq(self.x(), self.y(), self.z(), opp.x(), opp.y(), opp.z()) <= sReachSq;
            boolean oCan = oCharge <= 0.0 && sHurt == 0
                    && reachSq(opp.x(), opp.y(), opp.z(), self.x(), self.y(), self.z()) <= oReachSq;

            if (sCan && in.oShieldUp) {
                // their raised shield eats the hit: no credit, our cooldown is spent, the shield's knockback applies
                shieldBlock(opp, self);
                sCharge = sPeriod;
            } else if (sCan) {
                landed += (oCan ? SIMULTANEOUS_CREDIT : 1.0) * weight;
                hit(opp, self, sYaw, sSprint);
                if (sSprint) {
                    sSprintWait = in.resprintTicks;
                }
                sCharge = sPeriod;
                oHurt = HURT_IMMUNITY;
                if (!oSprint && in.oHitStun > 0 && oCharge < in.oHitStun) {
                    oCharge = in.oHitStun; // its learned recovery: no answer for a few ticks, step in after the hit
                }
                // a simultaneous opponent swing stays charged and is re-checked next tick, after our knockback
                oCan = false;
            }
            if (oCan && in.sBlocking) {
                // our raised shield eats their hit (always frontal here: both face each other)
                shieldBlock(self, opp);
                oCharge = oPeriod;
            } else if (oCan) {
                taken += weight;
                hit(self, opp, oYaw, oSprint);
                if (oSprint) {
                    oSprintWait = in.resprintTicks;
                }
                oCharge = oPeriod;
                sHurt = HURT_IMMUNITY;
            }
            weight *= HIT_DISCOUNT;
        }

        double sc = landed - in.takenWeight * taken;
        sc += POSITION_WEIGHT * positionTerm(in, sCharge, oCharge);
        if (wall) {
            sc -= WALL_PENALTY;
        }
        double drop = Math.max(self.fallDistance(), startY - self.y());
        if (drop > FALL_REJECT) {
            return REJECTED; // falling into fall damage or a void: nothing to trade
        }
        if (drop > 1.0) {
            sc -= FALL_PENALTY;
        }
        return sc;
    }

    /** End-of-horizon spacing preference in [-1, 1]. */
    private double positionTerm(TradeInput in, double sCharge, double oCharge) {
        double sDist = Math.sqrt(reachSq(self.x(), self.y(), self.z(), opp.x(), opp.y(), opp.z()));
        double oDist = Math.sqrt(reachSq(opp.x(), opp.y(), opp.z(), self.x(), self.y(), self.z()));
        boolean sReady = sCharge <= NEARLY_READY;
        boolean oReady = oCharge <= NEARLY_READY;
        if (sReady && !oReady) {
            // our window: be inside our reach, or close enough to sprint in before their attack is back
            double closeable = Math.min(MAX_CLOSE, CLOSE_SPEED * (oCharge - Math.max(0.0, sCharge)));
            double over = sDist - in.sReach - closeable;
            return over <= 0.0 ? 1.0 : -Math.min(1.0, over);
        }
        // their attack is (nearly) charged, or neither is: hover just outside their reach
        if (oDist <= in.oReach) {
            return oReady ? -1.0 : -0.25;
        }
        // learned reach: the band ends at our own reach when theirs is shorter (we sit where only we can hit), and
        // LEARNED_BAND past theirs otherwise (the edge, one step from a hit)
        double band = in.hoverRelative ? Math.max(LEARNED_BAND, in.sReach - in.oReach) : SPACING_BAND;
        double over = oDist - in.oReach - band;
        return over <= 0.0 ? 1.0 : 1.0 - Math.min(2.0, over);
    }

    /** Applies one landed melee hit from {@code attacker} (facing {@code attackerYaw}) to {@code victim}. */
    private static void hit(BodySim victim, BodySim attacker, float attackerYaw, boolean sprintHit) {
        victim.knockback(KNOCKBACK, attacker.x() - victim.x(), attacker.z() - victim.z());
        if (sprintHit) {
            double yaw = Math.toRadians(attackerYaw);
            victim.knockback(SPRINT_KNOCKBACK, Math.sin(yaw), -Math.cos(yaw));
            attacker.scaleHorizontal(SPRINT_HIT_SLOWDOWN);
        }
    }

    /**
     * A melee hit blocked by {@code holder}'s raised shield: no damage, no hurt immunity, no attacker knockback; the
     * shield's own knockback as vanilla 1.21.1 applies it ({@code LivingEntity.blockUsingShield} ->
     * {@code attacker.blockedByShield(holder)} -> {@code holder.knockback(0.5, holder.x - attacker.x, ...)}).
     */
    private static void shieldBlock(BodySim holder, BodySim attacker) {
        holder.knockback(SHIELD_KNOCKBACK, holder.x() - attacker.x(), holder.z() - attacker.z());
    }

    /** Minecraft yaw (0 = +Z, 90 = -X) looking along (dx, dz). */
    static float yawToward(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Squared distance from the attacker's eye to the nearest point of the victim's box. */
    public static double reachSq(double ax, double ay, double az, double vx, double vy, double vz) {
        double eyeY = ay + EYE;
        double ddx = Math.max(vx - HALF_WIDTH - ax, Math.max(0.0, ax - (vx + HALF_WIDTH)));
        double ddy = Math.max(vy - eyeY, Math.max(0.0, eyeY - (vy + HEIGHT)));
        double ddz = Math.max(vz - HALF_WIDTH - az, Math.max(0.0, az - (vz + HALF_WIDTH)));
        return ddx * ddx + ddy * ddy + ddz * ddz;
    }

    public boolean valid() {
        return valid;
    }

    /** Best forward axis: +1 forward key, -1 back, 0 none. */
    public int forward() {
        return forward;
    }

    /** Best strafe axis: +1 left key, -1 right, 0 none. */
    public int strafe() {
        return strafe;
    }

    public boolean jump() {
        return jump;
    }

    public boolean sprint() {
        return sprint;
    }

    /** Score of the best combination: hits landed minus weighted hits taken over the horizon, plus position terms. */
    public double score() {
        return score;
    }

    /** Score of one combination (see {@link #COMBOS} for the index); very negative when rejected. */
    public double scoreOf(int combo) {
        return scores[combo];
    }

    public static int combo(int forward, int strafe) {
        return (forward + 1) * 3 + (strafe + 1);
    }
}
