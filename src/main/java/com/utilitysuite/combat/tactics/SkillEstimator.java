package com.utilitysuite.combat.tactics;

/**
 * Live estimate of an opponent's skill in [0, 1] from what it does in the fight: share of its swings that hit, the
 * length of its combos, how quickly it reacts, and how well it avoids our hits. Drives the rank dial: the shared
 * humanizer is asked for a skill one notch above the opponent ({@link #oneNotchAbove}), so the assistant wins like a
 * slightly better player. Fixed primitive arrays for up to {@value #MAX_OPPONENTS} opponents (least recently touched
 * slot reused); engine free and allocation free after construction.
 * <p>
 * Components, each mapped to 0..1:
 * <ul>
 *   <li>accuracy {@code a = (hits + 0.5) / (swingsInReach + 1)}, score {@code (a - 0.15) / 0.75};</li>
 *   <li>combo: {@code 0.7 x EMA(finished combo length) + 0.3 x min(best, 5)} (an ongoing longer run counts),
 *       score {@code (c - 1) / 2.5} (single hits 0, 3-hit combos 0.8);</li>
 *   <li>reaction: EMA of measured reaction ticks, score {@code (13 - r) / 9} (4 ticks 1.0, 12 ticks 0.11), 0.5 while
 *       unmeasured;</li>
 *   <li>evasion {@code e = (ownMisses + 0.25) / (ownSwings + 1)}, score {@code e / 0.35}.</li>
 * </ul>
 * Raw skill = {@value #FLOOR} + (1 - {@value #FLOOR}) x (0.25 accuracy + 0.30 combo + 0.25 reaction + 0.20 evasion).
 * The reported value starts at {@link #UNKNOWN_SKILL} and steps toward the raw value by a quarter of the gap, at most
 * {@value #MAX_STEP} per event, so the dial never jumps.
 */
public final class SkillEstimator {

    public static final int MAX_OPPONENTS = 8;
    /** Reported until enough has been seen. */
    public static final double UNKNOWN_SKILL = 0.5;
    /** Default step above the opponent. */
    public static final double NOTCH = 0.1;
    /** Events needed before {@link #skill} leaves {@link #UNKNOWN_SKILL}. */
    public static final int MIN_SAMPLES = 8;
    /** Largest change of the reported skill per event. */
    public static final double MAX_STEP = 0.02;

    public static final double W_ACCURACY = 0.25;
    public static final double W_COMBO = 0.30;
    public static final double W_REACTION = 0.25;
    public static final double W_EVASION = 0.20;
    public static final double FLOOR = 0.08;
    public static final double COMBO_ALPHA = 0.3;
    public static final double REACTION_ALPHA = 0.2;

    private final int[] ids = new int[MAX_OPPONENTS];
    private final long[] touched = new long[MAX_OPPONENTS];
    private final int[] samples = new int[MAX_OPPONENTS];
    private final int[] oppSwings = new int[MAX_OPPONENTS];
    private final int[] oppHits = new int[MAX_OPPONENTS];
    private final int[] curCombo = new int[MAX_OPPONENTS];
    private final int[] best = new int[MAX_OPPONENTS];
    private final double[] comboEma = new double[MAX_OPPONENTS];
    private final int[] combos = new int[MAX_OPPONENTS];
    private final double[] reaction = new double[MAX_OPPONENTS];
    private final int[] reactions = new int[MAX_OPPONENTS];
    private final int[] ownHits = new int[MAX_OPPONENTS];
    private final int[] ownMisses = new int[MAX_OPPONENTS];
    private final double[] shown = new double[MAX_OPPONENTS];
    private long clock;

    public SkillEstimator() {
        clear();
    }

    private int slotOf(int id) {
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    private int slotFor(int id) {
        int s = slotOf(id);
        if (s < 0) {
            s = 0;
            for (int i = 0; i < MAX_OPPONENTS; i++) {
                if (ids[i] == -1) {
                    s = i;
                    break;
                }
                if (touched[i] < touched[s]) {
                    s = i;
                }
            }
            resetSlot(s);
            ids[s] = id;
        }
        touched[s] = ++clock;
        return s;
    }

    private void resetSlot(int s) {
        ids[s] = -1;
        touched[s] = Long.MIN_VALUE;
        samples[s] = 0;
        oppSwings[s] = 0;
        oppHits[s] = 0;
        curCombo[s] = 0;
        best[s] = 0;
        comboEma[s] = 1.0;
        combos[s] = 0;
        reaction[s] = 0.0;
        reactions[s] = 0;
        ownHits[s] = 0;
        ownMisses[s] = 0;
        shown[s] = UNKNOWN_SKILL;
    }

    /** Counts the event and moves the reported skill one bounded step toward the raw estimate. */
    private void event(int s) {
        if (samples[s] < Integer.MAX_VALUE) {
            samples[s]++;
        }
        if (samples[s] >= MIN_SAMPLES) {
            double gap = raw(s) - shown[s];
            double step = gap * 0.25;
            step = step < -MAX_STEP ? -MAX_STEP : Math.min(step, MAX_STEP);
            // close tiny gaps fully so the dial settles
            shown[s] += Math.abs(gap) <= 1.0e-3 ? gap : step;
        }
    }

    /** The unsmoothed blend for a slot. */
    double raw(int s) {
        double acc = Math.min(1.0, (oppHits[s] + 0.5) / (oppSwings[s] + 1.0));
        double accS = clamp01((acc - 0.15) / 0.75);
        double c = combos[s] > 0 ? comboEma[s] : 1.0;
        c = Math.max(c, curCombo[s]);
        double comboVal = 0.7 * c + 0.3 * Math.min(best[s] == 0 ? 1 : best[s], 5);
        double comboS = clamp01((comboVal - 1.0) / 2.5);
        double reactS = reactions[s] == 0 ? 0.5 : clamp01((13.0 - reaction[s]) / 9.0);
        int own = ownHits[s] + ownMisses[s];
        double ev = (ownMisses[s] + 0.25) / (own + 1.0);
        double evS = clamp01(ev / 0.35);
        double blend = W_ACCURACY * accS + W_COMBO * comboS + W_REACTION * reactS + W_EVASION * evS;
        return FLOOR + (1.0 - FLOOR) * blend;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }

    /** The opponent swung; {@code inReach} = we were inside its reach (a swing that could have hit). */
    public void noteOpponentSwing(int id, long tick, boolean inReach) {
        int s = slotFor(id);
        if (inReach) {
            oppSwings[s]++;
        }
        event(s);
    }

    /** The opponent's hit landed on us. */
    public void noteOpponentHit(int id, long tick, float damage) {
        int s = slotFor(id);
        oppHits[s]++;
        curCombo[s]++;
        if (curCombo[s] > best[s]) {
            best[s] = curCombo[s];
        }
        event(s);
    }

    /** Our hit landed on the opponent. */
    public void noteOwnHit(int id, long tick) {
        int s = slotFor(id);
        ownHits[s]++;
        if (curCombo[s] > 0) {
            int n = ++combos[s];
            double a = Math.max(COMBO_ALPHA, 1.0 / n);
            comboEma[s] += a * (curCombo[s] - comboEma[s]);
            curCombo[s] = 0;
        }
        event(s);
    }

    /** Our swing was sent while the opponent was in reach but did not land (it dodged or blocked). */
    public void noteOwnMiss(int id, long tick) {
        int s = slotFor(id);
        ownMisses[s]++;
        event(s);
    }

    /** A measured reaction of the opponent (ticks between a stimulus, e.g. our step-in, and its answer). */
    public void noteReaction(int id, int ticks) {
        int s = slotFor(id);
        int n = ++reactions[s];
        double a = Math.max(REACTION_ALPHA, 1.0 / n);
        reaction[s] += a * (Math.max(0, ticks) - reaction[s]);
        event(s);
    }

    /** Estimated skill 0..1; {@link #UNKNOWN_SKILL} until {@link #samples} reaches a minimum. */
    public double skill(int id) {
        int s = slotOf(id);
        if (s < 0 || samples[s] < MIN_SAMPLES) {
            return UNKNOWN_SKILL;
        }
        return shown[s];
    }

    /** Events seen for this opponent. */
    public int samples(int id) {
        int s = slotOf(id);
        return s < 0 ? 0 : samples[s];
    }

    /** Longest run of the opponent's hits without one of ours in between. */
    public int bestCombo(int id) {
        int s = slotOf(id);
        return s < 0 ? 0 : best[s];
    }

    public void forget(int id) {
        int s = slotOf(id);
        if (s >= 0) {
            resetSlot(s);
        }
    }

    public void clear() {
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            resetSlot(i);
        }
        clock = 0;
    }

    /** The skill to request: {@code opponentSkill + notch}, clamped to [{@code lo}, {@code hi}]. */
    public static double oneNotchAbove(double opponentSkill, double notch, double lo, double hi) {
        double v = opponentSkill + notch;
        return v < lo ? lo : Math.min(v, hi);
    }
}
