package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.SwingGate;

/**
 * Chooses the kind of the next hit a few ticks before the attack is charged, then drives the swing, the hop and the
 * sprint key toward it. Engine free (no Minecraft imports) and allocation free: set the context fields, call
 * {@link #decide} once per engaged tick, read the outputs, call {@link #onSwing} when the swing was sent.
 *
 * <h2>Vanilla rules (NeoForge 21.1.265 sources, {@code Player.attack} and {@code LocalPlayer.aiStep})</h2>
 * <ul>
 *   <li>Sprint knockback: charge above 0.9 and sprinting (no ground check).</li>
 *   <li>Critical hit: charge above 0.9, {@code fallDistance > 0}, not on ground, not climbing, not in water, not
 *       blind, not riding, target living, and <b>not sprinting</b>.</li>
 *   <li>Sweep: charge above 0.9, not a sprint-knockback hit, on ground, horizontal walk distance this tick below the
 *       movement speed attribute ({@code walkDist - walkDistO < getSpeed()}: about 0.167 blocks per tick at walking
 *       speed 0.1), and a main hand that can {@code SWORD_SWEEP}. Every living entity in the target's box grown by
 *       1 / 0.25 / 1 within reach is hit, neutral mobs, villagers and pets included (only team allies and marker armour
 *       stands are skipped).</li>
 *   <li>Client sprint persists in the air while forward is held: it only stops when forward impulse is gone (or a
 *       wall, water or hunger stops it); with the sprint key down it restarts in the air as soon as forward is back.</li>
 * </ul>
 * So a crit needs forward (and sprint) released before the swing tick: the planner releases forward from the apex
 * until the swing, which also serves as the sprint reset for the next hit, and swings on the first falling tick with
 * sprint off.
 *
 * <h2>Default policy (AUTO)</h2>
 * Arena read toward lava or a drop: SPRINT_KB. Opener and every re-engage: SPRINT_KB. A live combo plan: SPRINT_KB.
 * Two or more hostiles in the sweep box with a sword and nothing protected there: SWEEP. Otherwise CRIT when a crit is
 * worth it (walled or cornered target, target eating, no combo follow-up, out-damaged, or a high-health mob that does
 * not launch) and a hop is allowed; else SPRINT_KB. No ground swing that would sweep ever happens while something
 * protected is in the box.
 */
public final class HitPlanner {

    public enum Kind { SPRINT_KB, CRIT, SWEEP, PLAIN }

    /** The "Hit choice" setting. */
    public enum Policy { AUTO, KNOCKBACK, CRITS, PLAIN }

    /** Ticks from the jump press to the first falling tick: a full jump (open sky), and under a 3-block ceiling. */
    public static final int HOP_LEAD_FULL = 6;
    public static final int HOP_LEAD_LOW = 5;
    /** Headroom (open blocks above the floor) at which a full jump fits. */
    public static final int FULL_JUMP_HEADROOM = 4;
    /** Headroom at or below which no hop is made. */
    public static final int LOW_HEADROOM = 2;
    /** The kind is chosen freely until the attack is this many ticks from charged, then held until the swing. */
    public static final int LATCH_TICKS = 8;
    /** Ticks at full charge spent waiting for a crit fall before a plain swing. */
    public static final int MAX_CRIT_WAIT = 8;
    /** Ticks at full charge spent waiting for sprint before a plain swing. */
    public static final int MAX_KB_WAIT = 2;
    /** Ticks at full charge spent waiting to slow down for a sweep. */
    public static final int MAX_SWEEP_WAIT = 3;
    /** Ticks out of reach (plus {@link #REENGAGE_EXTRA}) after which the next hit counts as a re-engage. */
    public static final int REENGAGE_TICKS = 20;
    public static final double REENGAGE_EXTRA = 1.0;
    /** A hop is only started this close to reach (eye to box) so it does not hop at a target walking away. */
    public static final double HOP_REACH_EXTRA = 0.6;
    /** Vertical speed below which forward (and sprint) are released for a crit: the apex and the fall. */
    public static final double APEX_VY = 0.12;
    /**
     * Horizontal speed (blocks per tick) at or above which a ground swing can not sweep: vanilla's walk distance is
     * 0.6 x the horizontal move and has to stay below the 0.1 movement speed.
     */
    public static final double SWEEP_SAFE_SPEED = 0.17;
    /** Speed under which a planned sweep swings (a margin below the vanilla limit). */
    public static final double SWEEP_SLOW_SPEED = 0.15;
    /** Gear ratio from which we count as out-damaged. */
    public static final double OUT_DAMAGED_RATIO = 1.15;
    /**
     * Gear ratio from which only crits count against a player: plain and knockback hits on that armour deal less
     * than the opponent regenerates, so the opener aside every hit is a crit, combo plan or not, and the hop is made
     * even while the opponent's hit is charged (they out-trade us on the ground just the same).
     */
    public static final double CRIT_OR_NOTHING_RATIO = 2.5;
    /** Mob health (points) from which crits are worth more than knockback. */
    public static final float HIGH_HEALTH_MOB = 20f;
    /** Opponent within this horizontal distance counts as in its reach (3 blocks plus its box). */
    public static final double OPP_REACH = 3.6;
    /**
     * Approach crit: closing speed (blocks per tick, eye to box, smoothed) from which an opponent counts as walking
     * into us. A walking player alone closes at about 0.22, both sprinting at about 0.55.
     */
    public static final double APPROACH_MIN_CLOSING = 0.18;
    /** The target's own speed toward us from which it is the one walking in (a walk is about 0.22 per tick). */
    public static final double APPROACH_TARGET_SPEED = 0.1;
    /** Predicted ticks to reach entry, relative to the hop lead, inside which the approach hop is pressed. */
    public static final int APPROACH_EARLY = 1;
    public static final int APPROACH_LATE = 2;
    /** Smoothing of the closing speed (weight of the newest tick). */
    public static final double CLOSING_ALPHA = 0.5;
    /** Per-tick distance changes beyond this are a teleport or a new target, not motion. */
    public static final double CLOSING_JUMP = 1.0;

    // ---- context (set by the caller before decide) --------------------------------------------------------------
    public Policy policy = Policy.AUTO;
    /** The "Critical-hit timing" setting: off, AUTO never picks CRIT. */
    public boolean critTiming = true;
    public boolean targetIsPlayer;
    /** The combo keeper has a live plan that reaches the next hit. */
    public boolean comboLive;
    /** The gap ambush holds us at the lip of a drop: no hop. */
    public boolean holdGap;
    /** The opponent's attack is charged (cooldown read) and it is in reach: being airborne is a liability. */
    public boolean oppCharged;
    /** {@link GearRead#ratio()}: above {@link #OUT_DAMAGED_RATIO} we are out-damaged. */
    public double gearRatio = 1.0;
    /**
     * Forward and sprint are held (by us) but sprint is not on yet: a SPRINT_KB swing waits up to
     * {@link #MAX_KB_WAIT} ticks for it. Without it the swing is not delayed (a plain hit now beats a late one).
     */
    public boolean sprintPending;
    /**
     * The "Crits only against fast healers" setting: crit-or-nothing ({@link #critOrNothing}) also needs
     * {@link #oppOutHeals}, and while out-damaged by a player that does not out-heal plain hits every charged in-reach
     * tick swings (sprint knockback; a crit only on a walled or eating target, without waiting for the fall on the
     * ground). False (default) keeps the gear-ratio-only rule.
     */
    public boolean healGate;
    /**
     * Observed healing of the opponent outpaces our plain hits ({@link #outHeals}): only crits make progress.
     */
    public boolean oppOutHeals;
    /**
     * The "Crit on approach" setting: when the gap closes with our attack charged, hop so the first falling tick
     * meets the opponent's entry into reach, and make that first hit a crit (it overrides the opener's sprint hit
     * and the "its hit is charged" hop veto: the hop is made before it is in its reach).
     */
    public boolean approachCrit = true;

    // ---- outputs of the last decide -----------------------------------------------------------------------------
    private boolean swing;
    private boolean jump;
    private boolean releaseForward;
    private boolean holdSprint;
    private boolean dropSprint;

    // ---- state ---------------------------------------------------------------------------------------------------
    private Kind kind = Kind.SPRINT_KB;
    private Kind lastSwingKind = Kind.PLAIN;
    private boolean latched;
    private boolean hopped;
    private boolean wasAirborne;
    private int waitTicks;
    private int swingsThisEngagement;
    private int outOfReachTicks;
    private boolean reengage;
    private double prevReachDist = -1.0;
    private double closing;
    private boolean approachHop;
    private int approachHops;

    /** A new engagement: the next hit is an opener. */
    public void reset() {
        prevReachDist = -1.0;
        closing = 0.0;
        approachHop = false;
        kind = Kind.SPRINT_KB;
        lastSwingKind = Kind.PLAIN;
        latched = false;
        hopped = false;
        wasAirborne = false;
        waitTicks = 0;
        swingsThisEngagement = 0;
        outOfReachTicks = 0;
        reengage = false;
        clearOutputs();
    }

    private void clearOutputs() {
        swing = false;
        jump = false;
        releaseForward = false;
        holdSprint = false;
        dropSprint = false;
    }

    /** Ticks until the attack reaches {@link SwingGate#READY} at {@code attackSpeed} attacks per second. */
    public static int ticksUntilReady(float attackStrength, double attackSpeed) {
        if (attackStrength >= SwingGate.READY) {
            return 0;
        }
        double period = 20.0 / Math.max(0.1, attackSpeed);
        return (int) Math.ceil((SwingGate.READY - attackStrength) * period);
    }

    /** Ticks from the jump press to the first falling tick under this much headroom. */
    public static int hopLead(int headroom) {
        return headroom >= FULL_JUMP_HEADROOM ? HOP_LEAD_FULL : HOP_LEAD_LOW;
    }

    /** True when being airborne now costs nothing it should not: every "no hop" rule. */
    public boolean hopAllowed(CombatSnapshot s) {
        return critTiming && !holdGap && !s.hazardBack && !dropBehind(s)
                && s.headroom > LOW_HEADROOM && !s.inWater && !s.climbing
                && !s.usingItem
                && !(targetIsPlayer && oppCharged && !critOrNothing() && s.horizontalDistance() <= OPP_REACH);
    }

    /** Depth (open blocks) from which a drop behind us vetoes a hop. */
    public static final int DROP_BEHIND_DEPTH = 4;

    /**
     * A real drop lies up to 6 blocks behind us on the line from the target: a hit taken in the air (no ground
     * friction) carries us 3-5 blocks, so no hop here (arena benchmark 2026-11-23: two of three losses were crit hops
     * at the reach edge, hit mid-air and knocked over the edge).
     */
    public static boolean dropBehind(CombatSnapshot s) {
        return s.selfKnockDepth >= DROP_BEHIND_DEPTH;
    }

    /** Heavily out-geared by a player ({@link #CRIT_OR_NOTHING_RATIO}): only crits make progress. */
    public boolean critOrNothing() {
        return critTiming && targetIsPlayer && gearRatio >= CRIT_OR_NOTHING_RATIO && (!healGate || oppOutHeals);
    }

    /**
     * Out-damaged by a player whose healing does not outpace our plain hits ({@link #healGate} on): tempo beats crit
     * damage, every charged tick in reach is a hit (each tick held in its reach is a free swing for it).
     */
    public boolean tempo() {
        return healGate && targetIsPlayer && !oppOutHeals && gearRatio >= OUT_DAMAGED_RATIO;
    }

    /** Share of our plain-hit DPS that its healing must reach before only crits count (we land about half our swings). */
    public static final double OUT_HEAL_SHARE = 0.5;

    /**
     * Its observed healing ({@code OpponentModel.healRate}, points per second) outpaces our plain hits landing on
     * about half the charged swings: {@code healRate >= 0.5 x plainHit x 20 / cooldownTicks}.
     */
    public static boolean outHeals(double healRate, double plainHit, double cooldownTicks) {
        double dps = Math.max(0.0, plainHit) * 20.0 / Math.max(1.0, cooldownTicks);
        return healRate > 0.0 && healRate >= OUT_HEAL_SHARE * dps;
    }

    /** True when a ground swing now would sweep (sword, ground, no sprint, slow) and something protected is close. */
    public static boolean sweepWouldHitProtected(CombatSnapshot s) {
        return s.sweepProtected > 0 && s.sweepWeapon && s.onGround && !s.sprinting
                && s.horizontalSpeed() < SWEEP_SAFE_SPEED;
    }

    /** The kind the policy picks for the current snapshot (no latching, no side effects). */
    public Kind choose(CombatSnapshot s, boolean hopOk) {
        switch (policy) {
            case KNOCKBACK:
                return Kind.SPRINT_KB;
            case PLAIN:
                return Kind.PLAIN;
            case CRITS:
                return hopOk || !s.onGround ? Kind.CRIT : Kind.PLAIN;
            default:
                break;
        }
        if (s.knockValue > 0.0 && (s.knockKind == ArenaField.KNOCK_LAVA || s.knockKind == ArenaField.KNOCK_DROP)) {
            return Kind.SPRINT_KB; // the arena read steers toward lava or a drop: knockback, always
        }
        boolean critOnly = critOrNothing() && (hopOk || !s.onGround);
        if (swingsThisEngagement == 0 || (reengage && !critOnly)) {
            return Kind.SPRINT_KB; // opener and re-engage
        }
        if (comboLive && !critOnly) {
            return Kind.SPRINT_KB;
        }
        if (s.sweepWeapon && s.sweepProtected == 0 && s.sweepHostiles >= 1) {
            return Kind.SWEEP; // the target plus at least one more hostile in the sweep box
        }
        boolean walled = s.knockValue > 0.0 && s.knockKind == ArenaField.KNOCK_WALL;
        if (tempo()) {
            // out-damaged, its healing is slow: plain hits make progress, so knockback hits on every charge
            return (walled || s.targetUsingItem) && critTiming && (hopOk || !s.onGround) ? Kind.CRIT
                    : Kind.SPRINT_KB;
        }
        boolean bigMob = !targetIsPlayer && s.targetHealth >= HIGH_HEALTH_MOB && !s.targetHeavyHitter;
        boolean worth = walled || s.targetUsingItem || !comboLive || gearRatio >= OUT_DAMAGED_RATIO || bigMob;
        if (worth && critTiming && (hopOk || !s.onGround)) {
            return Kind.CRIT;
        }
        return Kind.SPRINT_KB;
    }

    /** Decides this tick's swing, hop and sprint/forward overrides. Allocation free. */
    public void decide(CombatSnapshot s) {
        clearOutputs();
        boolean inReach = s.inReach();
        double reachSq = s.reachDistanceSq();
        double far = s.reach + REENGAGE_EXTRA;
        if (reachSq > far * far) {
            if (outOfReachTicks < REENGAGE_TICKS) {
                outOfReachTicks++;
            }
            if (outOfReachTicks >= REENGAGE_TICKS && swingsThisEngagement > 0) {
                reengage = true;
                latched = false;
            }
        } else {
            outOfReachTicks = 0;
        }
        boolean airborne = !s.onGround;
        if (!airborne && wasAirborne) {
            hopped = false; // landed without a swing: the next hop may start again
        }
        wasAirborne = airborne;
        double dist = Math.sqrt(reachSq);
        trackClosing(dist);
        if (!airborne && !hopped) {
            approachHop = false;
        }

        int readyIn = ticksUntilReady(s.attackStrength, s.ownAttackSpeed);
        boolean hopOk = hopAllowed(s);
        if (!airborne && !hopped && approachHopDue(s, dist, readyIn)) {
            // the gap closes with our hit charged: hop now so we fall as it walks into reach, and crit it first
            kind = Kind.CRIT;
            latched = true;
            jump = true;
            hopped = true;
            approachHop = true;
            approachHops++;
        }
        if (!latched || (kind == Kind.CRIT && !airborne && !hopped && !hopOk)) {
            kind = choose(s, hopOk);
            latched = readyIn <= LATCH_TICKS;
        }

        boolean ready = s.attackStrength >= SwingGate.READY;
        boolean canHit = inReach && s.crosshairOnTarget && ready && s.targetHurtTime <= SwingGate.HURT_HOLD;
        boolean want = false;
        switch (kind) {
            case CRIT -> {
                if (!airborne) {
                    if (!hopped && hopOk && readyIn <= hopLead(s.headroom)
                            && reachSq <= (s.reach + HOP_REACH_EXTRA) * (s.reach + HOP_REACH_EXTRA)) {
                        jump = true;
                        hopped = true;
                    }
                    // tempo: a charged hit in reach on the ground goes now, the crit is not worth a held tick
                    want = canHit && (waitTicks >= MAX_CRIT_WAIT || !hopOk || tempo());
                } else {
                    if (s.pvy < APEX_VY) {
                        releaseForward = true; // the apex and the fall: sprint off before the swing tick
                        dropSprint = true;
                    }
                    want = canHit && (s.fallingForCrit() || waitTicks >= MAX_CRIT_WAIT);
                }
            }
            case SPRINT_KB -> {
                holdSprint = true;
                // wait for sprint only while it is on its way (forward and sprint held, sprint not on yet)
                want = canHit && (s.sprinting || !sprintPending || waitTicks >= MAX_KB_WAIT);
            }
            case SWEEP -> {
                if (readyIn <= 2) {
                    dropSprint = true;
                    if (s.horizontalSpeed() >= SWEEP_SLOW_SPEED) {
                        releaseForward = true;
                    }
                }
                boolean slow = !airborne && !s.sprinting && s.horizontalSpeed() < SWEEP_SLOW_SPEED;
                want = canHit && (slow || waitTicks >= MAX_SWEEP_WAIT);
            }
            default -> want = canHit;
        }
        if (want && sweepWouldHitProtected(s)) {
            want = false; // never sweep a pet, a villager, a neutral mob or a bystander: speed up or hop first
            holdSprint = true;
            releaseForward = false;
            dropSprint = false;
        }
        swing = want;
        if (canHit && !want) {
            waitTicks++;
        }
    }

    /** Smoothed closing speed from the eye-to-box distance; a jump in distance (teleport, new target) restarts it. */
    private void trackClosing(double dist) {
        if (prevReachDist >= 0.0) {
            double d = prevReachDist - dist;
            if (Math.abs(d) > CLOSING_JUMP) {
                closing = 0.0;
            } else {
                closing += CLOSING_ALPHA * (d - closing);
            }
        }
        prevReachDist = dist;
    }

    /** Smoothed closing speed in blocks per tick (positive: the gap shrinks). */
    public double closing() {
        return closing;
    }

    /**
     * Ticks until the eye-to-box distance reaches {@code reach} at the current closing speed, or -1 when the gap is
     * not closing (or already closed).
     */
    public static double entryTicks(double dist, double reach, double closing) {
        double gap = dist - reach;
        if (gap <= 0.0 || closing <= 1e-6) {
            return -1.0;
        }
        return gap / closing;
    }

    /**
     * The approach hop is due: on the ground, the gap closing at {@link #APPROACH_MIN_CLOSING} or more, its entry
     * into reach predicted between {@code lead - APPROACH_EARLY} and {@code lead + APPROACH_LATE} ticks (the first
     * falling tick of a hop pressed now is {@code lead} away, and the fall lasts about five more), and the attack
     * charged by then. Every safety rule of a normal hop applies, except the "its hit is charged" veto: that veto is
     * about standing in its reach, and this hop starts outside it. Not when the arena read steers the hit toward lava
     * or a drop (knockback wins there), and only for the AUTO and CRITS policies.
     */
    boolean approachHopDue(CombatSnapshot s, double dist, int readyIn) {
        if (!approachCrit || !critTiming || (policy != Policy.AUTO && policy != Policy.CRITS)) {
            return false;
        }
        if (holdGap || s.hazardBack || dropBehind(s) || s.headroom <= LOW_HEADROOM
                || s.inWater || s.climbing || s.usingItem) {
            return false;
        }
        if (s.knockValue > 0.0 && (s.knockKind == ArenaField.KNOCK_LAVA || s.knockKind == ArenaField.KNOCK_DROP)) {
            return false;
        }
        if (closing < APPROACH_MIN_CLOSING || targetApproachSpeed(s) < APPROACH_TARGET_SPEED) {
            return false; // we walking at a standing target is the sprint opener's job
        }
        double entry = entryTicks(dist, s.reach, closing);
        if (entry < 0.0) {
            return false;
        }
        int lead = hopLead(s.headroom);
        return entry >= lead - APPROACH_EARLY && entry <= lead + APPROACH_LATE && readyIn <= Math.max(entry, lead);
    }

    /** The target's own speed toward us (its real per-tick motion projected on the line to us), blocks per tick. */
    public static double targetApproachSpeed(CombatSnapshot s) {
        double dx = s.px - s.tx;
        double dz = s.pz - s.tz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            return 0.0;
        }
        return (s.tdx * dx + s.tdz * dz) / len;
    }

    /** True from an approach hop's press until the swing or the landing. */
    public boolean approachHop() {
        return approachHop;
    }

    /** Approach hops made since this planner was created (telemetry). */
    public int approachHops() {
        return approachHops;
    }

    /** The caller sent the swing this planner asked for (or any other swing): the cycle starts over. */
    public void onSwing() {
        lastSwingKind = kind;
        swingsThisEngagement++;
        approachHop = false;
        latched = false;
        hopped = false;
        waitTicks = 0;
        reengage = false;
        outOfReachTicks = 0;
    }

    public boolean swing() {
        return swing;
    }

    /** Tap jump now (a planned crit hop). */
    public boolean jump() {
        return jump;
    }

    /** Release forward this tick (crit sprint drop, sweep slow-down). Sprint goes with it. */
    public boolean releaseForward() {
        return releaseForward;
    }

    /** Keep the sprint key down while stepping forward (a planned sprint-knockback hit). */
    public boolean holdSprint() {
        return holdSprint;
    }

    /** Keep the sprint key up this tick (crit and sweep need sprint off on the swing tick). */
    public boolean dropSprint() {
        return dropSprint;
    }

    /** The planned kind of the next hit. */
    public Kind kind() {
        return kind;
    }

    public Kind lastSwingKind() {
        return lastSwingKind;
    }

    /** True while a crit hop is planned or ridden: other hop sources (the solver) stay out. */
    public boolean ownsHop() {
        return kind == Kind.CRIT && (latched || hopped);
    }

    public int swingsThisEngagement() {
        return swingsThisEngagement;
    }
}
