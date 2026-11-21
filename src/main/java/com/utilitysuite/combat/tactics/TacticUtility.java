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
 * </ul>
 */
public final class TacticUtility {

    public enum Tactic { FIGHT, KITE, RETREAT_HEAL, FLEE }

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
