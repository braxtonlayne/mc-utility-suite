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

    private final double[] scores = new double[Tactic.values().length];
    private static final Tactic[] TACTICS = Tactic.values();
    private Tactic current = Tactic.FIGHT;
    private int dwell;

    /** Fight advantage 0..1 (0.5 even): share of the opponent's time-to-kill-me in the total. */
    public double advantage() {
        double ttkOpp = oppHealth / Math.max(ownDps, MIN_DPS);
        double ttkMe = ownHealth / Math.max(oppDps, MIN_DPS);
        return ttkMe / (ttkOpp + ttkMe);
    }

    /** Raw scores for the current inputs, written into {@link #score}. */
    public void scoreAll() {
        double a = advantage();
        double hp = ownMaxHealth > 0 ? Math.max(0.0, Math.min(1.0, ownHealth / ownMaxHealth)) : 1.0;
        double pressure = Math.max(0.0, Math.min(1.0, oppHopRate / 1.5));
        scores[Tactic.FIGHT.ordinal()] = a;
        scores[Tactic.KITE.ordinal()] = 0.75 * a + 0.3 * pressure * (1.0 - Math.abs(a - 0.5));
        scores[Tactic.RETREAT_HEAL.ordinal()] = healingItems > 0 ? (1.0 - hp) * 1.2 * (1.0 - 0.5 * a) : 0.0;
        scores[Tactic.FLEE.ordinal()] = (1.0 - a) * (1.0 - hp) * (healingItems > 0 ? 0.8 : 2.0);
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
