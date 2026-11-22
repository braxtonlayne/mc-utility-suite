package com.utilitysuite.combat.tactics;

/**
 * Live estimate of an opponent's skill in [0, 1] from what it does in the fight: share of its swings that hit, the
 * length of its combos, how quickly it reacts, and how well it avoids our hits. Drives the rank dial: the shared
 * humanizer is asked for a skill one notch above the opponent ({@link #oneNotchAbove}), so the assistant wins like a
 * slightly better player. Fixed primitive arrays for up to {@value #MAX_OPPONENTS} opponents; engine free and
 * allocation free after construction.
 * <p>
 * CONTRACT STUB: signatures are fixed for the volley; the bodies are filled in by the opponent-reads task. Until then
 * {@link #skill} is {@link #UNKNOWN_SKILL} and {@link #samples} is 0.
 */
public final class SkillEstimator {

    public static final int MAX_OPPONENTS = 8;
    /** Reported until enough has been seen. */
    public static final double UNKNOWN_SKILL = 0.5;
    /** Default step above the opponent. */
    public static final double NOTCH = 0.1;

    /** The opponent swung; {@code inReach} = we were inside its reach (a swing that could have hit). */
    public void noteOpponentSwing(int id, long tick, boolean inReach) {
    }

    /** The opponent's hit landed on us. */
    public void noteOpponentHit(int id, long tick, float damage) {
    }

    /** Our hit landed on the opponent. */
    public void noteOwnHit(int id, long tick) {
    }

    /** Our swing was sent while the opponent was in reach but did not land (it dodged or blocked). */
    public void noteOwnMiss(int id, long tick) {
    }

    /** A measured reaction of the opponent (ticks between a stimulus, e.g. our step-in, and its answer). */
    public void noteReaction(int id, int ticks) {
    }

    /** Estimated skill 0..1; {@link #UNKNOWN_SKILL} until {@link #samples} reaches a minimum. */
    public double skill(int id) {
        return UNKNOWN_SKILL;
    }

    /** Events seen for this opponent. */
    public int samples(int id) {
        return 0;
    }

    /** Longest run of the opponent's hits without one of ours in between. */
    public int bestCombo(int id) {
        return 0;
    }

    public void forget(int id) {
    }

    public void clear() {
    }

    /** The skill to request: {@code opponentSkill + notch}, clamped to [{@code lo}, {@code hi}]. */
    public static double oneNotchAbove(double opponentSkill, double notch, double lo, double hi) {
        double v = opponentSkill + notch;
        return v < lo ? lo : Math.min(v, hi);
    }
}
