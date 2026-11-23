package com.utilitysuite.combat.tactics;

/**
 * Picks the broad fight tactic by utility, with hysteresis so it does not flap. Engine free and allocation free: fill
 * the public input fields, call {@link #choose}.
 * <ul>
 *   <li>{@link Tactic#FIGHT}: trade hits; scores the time-to-kill advantage.</li>
 *   <li>{@link Tactic#KITE}: keep spacing and punish approaches; preferred against a crit-hopping, high-pressure
 *       opponent when the trade is even.</li>
 *   <li>{@link Tactic#RETREAT_HEAL}: back off to eat or drink; needs healing supplies, scores missing health.</li>
 *   <li>{@link Tactic#FLEE}: leave; losing badly with low health and nothing to heal with.</li>
 *   <li>{@link Tactic#HIGH_GROUND}: {@code (0.6*a + 0.45*highGround) * reach}, where {@code reach} is 1 at distance >= 2
 *       and falls linearly to 0.8 at distance 0 (little room to walk). When clearly winning ({@code a >= 0.65}) and
 *       already within 3 blocks the score is capped just under FIGHT. 0 when {@code highGround} is 0.</li>
 *   <li>{@link Tactic#CEILING_DENY}: {@code 0.5*a + 0.6*ceilingDeny*pressure}, pressure being the hop rate as KITE
 *       uses it ({@code oppHopRate/1.5}, clamped). 0 without a hopping opponent or without a spot.</li>
 * </ul>
 * With both arena inputs at 0 the scores and choices are exactly those without an arena read.
 */
public final class TacticUtility {

    public enum Tactic {
        FIGHT, KITE, RETREAT_HEAL, FLEE,
        /** Take a nearby spot above the opponent and fight from there. Scores {@link #highGround}. */
        HIGH_GROUND,
        /** Back under a low ceiling so a crit-hopper's hops fail. Scores {@link #ceilingDeny} and the hop rate. */
        CEILING_DENY
    }

    /** Bonus the current tactic keeps over challengers. */
    public static final double HYSTERESIS = 0.12;
    /** A tactic stays at least this many ticks once chosen (unless FLEE becomes the clear winner). */
    public static final int MIN_DWELL_TICKS = 10;
    private static final double MIN_DPS = 0.1;

    // ---- inputs (health in points, DPS in points per second) -------------------------------------------------------
    public double ownHealth = 20;
    public double ownMaxHealth = 20;
    public double oppHealth = 20;
    public double ownDps = 5;
    public double oppDps = 5;
    /** Golden apples, healing/regeneration potions (not plain food). */
    public int healingItems;
    /** Horizontal distance to the opponent. */
    public double distance = 3;
    /** Opponent take-offs per second ({@link OpponentModel#hopRate}). */
    public double oppHopRate;
    /** 0..1: a useful higher spot is close ({@code ArenaTactics.highGround}); 0 = none (scores 0). */
    public double highGround;
    /** 0..1: a low-ceiling spot is close ({@code ArenaTactics.ceilingDeny}); 0 = none (scores 0). */
    public double ceilingDeny;
    /**
     * {@link GearRead#ratio()} (1 = even, 3 = one of their hits is worth three of ours); 0 = no gear read. When set,
     * the advantage is the mean of the DPS read and {@code 1 / (1 + gearRatio)}, so a stronger kit counts from the
     * first tick instead of after seconds of observed damage.
     */
    public double gearRatio;
    /** Duel mode ("Never disengage"): FLEE is never chosen; its score goes to RETREAT_HEAL, which the caller runs as a
     * reset (make distance, heal, come back). */
    public boolean duelMode;
    /**
     * A reset can heal without items: hunger is high enough for natural regeneration (food level 18+). In duel mode a
     * reset with neither this nor a healing item only hands the opponent time, so FLEE's score is dropped instead of
     * becoming a reset and the fight goes on.
     */
    public boolean canRegenerate = true;
    /**
     * The opponent is retreating ({@link OpponentModel#retreating}): no threat right now, so FIGHT wins outright
     * (without the dwell) and every other tactic scores 0. Default false.
     */
    public boolean oppRetreating;
    /**
     * We out-reach it (its learned reach is below ours): the trade solver ranges its crit hops out by itself, so the hop
     * pressure counts at {@link #OUT_RANGED_PRESSURE} toward KITE and CEILING_DENY. Default false.
     */
    public boolean outRanged;
    public static final double OUT_RANGED_PRESSURE = 0.5;

    /** Health (points) we must miss beyond what the opponent misses for a regeneration reset to pay. */
    public static final double REGEN_RESET_MARGIN = 2.0;

    /**
     * A reset on natural regeneration pays: both sides regenerate at the same rate while apart, so it only gains
     * ground while we miss clearly more health than the opponent does (they top up and stop, we keep healing).
     * Against a nearly dead opponent it never pays: the reset would hand back the damage already dealt.
     */
    public static boolean regenResetPays(double ownMissing, double oppMissing) {
        return ownMissing > oppMissing + REGEN_RESET_MARGIN;
    }

    /** Its chase speed must stay below this share of our sprint for a regeneration reset to gain distance. */
    public static final double PACE_SHARE = 0.9;

    /**
     * The opponent keeps pace with us on a regeneration reset: its observed chase speed
     * ({@link OpponentModel#chaseSpeed}) is at least {@value #PACE_SHARE} of our sprint speed, or it was never seen
     * running at us (unknown counts as keeping pace: a reset on natural regeneration then only hands it free hits).
     */
    public static boolean oppKeepsPace(double chaseSpeed, double ownSprintSpeed) {
        return chaseSpeed != chaseSpeed || chaseSpeed >= PACE_SHARE * ownSprintSpeed;
    }

    private final double[] scores = new double[Tactic.values().length];
    private static final Tactic[] TACTICS = Tactic.values();
    private Tactic current = Tactic.FIGHT;
    private int dwell;

    /** Fight advantage 0..1 (0.5 even): share of the opponent's time-to-kill-me in the total. */
    public double advantage() {
        double ttkOpp = oppHealth / Math.max(ownDps, MIN_DPS);
        double ttkMe = ownHealth / Math.max(oppDps, MIN_DPS);
        double a = ttkMe / (ttkOpp + ttkMe);
        if (gearRatio > 0.0) {
            a = 0.5 * a + 0.5 / (1.0 + gearRatio);
        }
        return a;
    }

    /** Raw scores for the current inputs, written into {@link #score}. */
    public void scoreAll() {
        double a = advantage();
        double hp = ownMaxHealth > 0 ? Math.max(0.0, Math.min(1.0, ownHealth / ownMaxHealth)) : 1.0;
        double pressure = Math.max(0.0, Math.min(1.0, oppHopRate / 1.5)) * (outRanged ? OUT_RANGED_PRESSURE : 1.0);
        if (oppRetreating) {
            for (int i = 0; i < scores.length; i++) {
                scores[i] = 0.0;
            }
            scores[Tactic.FIGHT.ordinal()] = Math.max(a, 1.0);
            return;
        }
        scores[Tactic.FIGHT.ordinal()] = a;
        scores[Tactic.KITE.ordinal()] = 0.75 * a + 0.3 * pressure * (1.0 - Math.abs(a - 0.5));
        scores[Tactic.RETREAT_HEAL.ordinal()] = healingItems > 0 ? (1.0 - hp) * 1.2 * (1.0 - 0.5 * a) : 0.0;
        scores[Tactic.FLEE.ordinal()] = (1.0 - a) * (1.0 - hp) * (healingItems > 0 ? 0.8 : 2.0);
        if (duelMode) {
            int r = Tactic.RETREAT_HEAL.ordinal();
            if (healingItems > 0 || canRegenerate) {
                scores[r] = Math.max(scores[r], scores[Tactic.FLEE.ordinal()]);
            }
            scores[Tactic.FLEE.ordinal()] = 0.0;
        }
        double hg = 0.0;
        if (highGround > 0.0) {
            double reach = distance >= 2.0 ? 1.0 : 0.8 + 0.1 * Math.max(0.0, distance);
            hg = (0.6 * a + 0.45 * Math.min(1.0, highGround)) * reach;
            if (a >= 0.65 && distance <= 3.0) {
                hg = Math.min(hg, a - 0.01);
            }
        }
        scores[Tactic.HIGH_GROUND.ordinal()] = hg;
        scores[Tactic.CEILING_DENY.ordinal()] = ceilingDeny > 0.0 && pressure > 0.0
                ? 0.5 * a + 0.6 * Math.min(1.0, ceilingDeny) * pressure : 0.0;
    }

    public double score(Tactic t) {
        return scores[t.ordinal()];
    }

    /** Scores and picks the winner, keeping the current tactic unless a challenger beats it by the hysteresis. */
    public Tactic choose() {
        scoreAll();
        if (duelMode && current == Tactic.FLEE) {
            // duel mode switched on mid-flight: the flight becomes a reset, or the fight if a reset cannot heal
            current = healingItems > 0 || canRegenerate ? Tactic.RETREAT_HEAL : Tactic.FIGHT;
        }
        if (current == Tactic.RETREAT_HEAL && healingItems <= 0 && !(duelMode && canRegenerate)) {
            // the reset can no longer heal (last item used, hunger fell below regeneration): it ends now, it does
            // not wait to be outscored, since in a losing fight every score is near zero and the hysteresis would hold it
            current = Tactic.FIGHT;
            dwell = 0;
        }
        if (oppRetreating) {
            // a retreating opponent is no threat: chase it now, no dwell in a kite or a reset
            if (current != Tactic.FIGHT) {
                current = Tactic.FIGHT;
                dwell = 0;
            } else {
                dwell++;
            }
            return current;
        }
        Tactic best = current;
        double bestScore = scores[current.ordinal()] + HYSTERESIS;
        for (Tactic t : TACTICS) {
            if (t != current && scores[t.ordinal()] > bestScore) {
                best = t;
                bestScore = scores[t.ordinal()];
            }
        }
        if (best != current) {
            boolean urgent = best == Tactic.FLEE && scores[Tactic.FLEE.ordinal()] > 0.9;
            if (dwell < MIN_DWELL_TICKS && !urgent) {
                dwell++;
                return current;
            }
            current = best;
            dwell = 0;
            return current;
        }
        dwell++;
        return current;
    }

    public Tactic current() {
        return current;
    }

    public void reset() {
        current = Tactic.FIGHT;
        dwell = 0;
    }
}
