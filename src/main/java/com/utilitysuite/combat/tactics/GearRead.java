package com.utilitysuite.combat.tactics;

/**
 * Gear read: how much one hit taken is worth against one hit landed, from the damage both sides deal per hit and the
 * health both sides have left. Engine free and allocation free.
 * <p>
 * {@code ratio = (their damage per hit / our effective health) / (our damage per hit / their effective health)}: the
 * share of our health one of their hits removes over the share of theirs one of ours removes. 1 = even.
 * {@link #takenWeight()} is {@code clamp(EVEN_BIAS * ratio, 0.8, 3.0)} and replaces the trade solver's fixed 1.2 (so
 * an even fight keeps the old weight); kiting multiplies it by {@link #KITE_FACTOR} (1.8 / 1.2, the old kite weight).
 * <p>
 * Before any hit has landed each side's damage per hit is the estimate from visible gear (held weapon, armour); once
 * hits are seen it is an exponential average of the observed hits, seeded with the estimate, so one odd hit does not
 * flip the read.
 */
public final class GearRead {

    /** Weight of a hit taken at even gear and even health (the solver's former constant). */
    public static final double EVEN_BIAS = 1.2;
    public static final double MIN_WEIGHT = 0.8;
    public static final double MAX_WEIGHT = 3.0;
    /** Kiting values hits taken more (the former 1.8 kite weight over 1.2). */
    public static final double KITE_FACTOR = 1.5;
    /** Bounds of the raw ratio handed to the tactic utility and the hit planner. */
    public static final double MIN_RATIO = 0.25;
    public static final double MAX_RATIO = 4.0;
    /** Smoothing of observed hits: weight of the newest hit. */
    public static final double ALPHA = 0.4;
    /** Observed hits below this are ignored (a cactus touch or a 0-damage shield hit tells nothing about gear). */
    public static final double MIN_HIT = 0.5;
    private static final double MIN_HEALTH = 1.0;
    private static final double MIN_DAMAGE = 0.25;

    private double takenAvg;
    private double dealtAvg;
    private int takenHits;
    private int dealtHits;
    private double ratio = 1.0;
    private double theirHit;
    private double ourHit;

    public void reset() {
        takenAvg = 0;
        dealtAvg = 0;
        takenHits = 0;
        dealtHits = 0;
        ratio = 1.0;
        theirHit = 0;
        ourHit = 0;
    }

    /** One of their hits landed on us for {@code damage} points (health plus absorption lost). */
    public void noteTaken(double damage) {
        if (damage < MIN_HIT) {
            return;
        }
        takenAvg = takenHits == 0 ? damage : takenAvg + ALPHA * (damage - takenAvg);
        takenHits++;
    }

    /** One of our hits landed on them for {@code damage} points. */
    public void noteDealt(double damage) {
        if (damage < MIN_HIT) {
            return;
        }
        dealtAvg = dealtHits == 0 ? damage : dealtAvg + ALPHA * (damage - dealtAvg);
        dealtHits++;
    }

    /**
     * Recomputes the ratio. Estimates are per-hit damage after armour ({@link #afterArmor}); they are used until hits
     * of that side were seen, and blended in for the first two hits so the switch is not a jump.
     */
    public void update(double ownEffectiveHealth, double oppEffectiveHealth, double ourHitEstimate,
                       double theirHitEstimate) {
        theirHit = blend(theirHitEstimate, takenAvg, takenHits);
        ourHit = blend(ourHitEstimate, dealtAvg, dealtHits);
        double own = Math.max(MIN_HEALTH, ownEffectiveHealth);
        double opp = Math.max(MIN_HEALTH, oppEffectiveHealth);
        double r = (Math.max(MIN_DAMAGE, theirHit) / own) / (Math.max(MIN_DAMAGE, ourHit) / opp);
        ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, r));
    }

    private static double blend(double estimate, double observed, int hits) {
        if (hits <= 0) {
            return estimate;
        }
        if (hits == 1) {
            return 0.5 * estimate + 0.5 * observed;
        }
        return observed;
    }

    /** Raw ratio (1 = even; 3 = one of their hits is worth three of ours), clamped to [0.25, 4]. */
    public double ratio() {
        return ratio;
    }

    /** Value of a hit taken vs. one landed for the trade solver. */
    public double takenWeight() {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, EVEN_BIAS * ratio));
    }

    public double takenWeight(boolean kiting) {
        double w = takenWeight();
        return kiting ? w * KITE_FACTOR : w;
    }

    /** Their per-hit damage used by the last {@link #update}. */
    public double theirHit() {
        return theirHit;
    }

    /** Our per-hit damage used by the last {@link #update}. */
    public double ourHit() {
        return ourHit;
    }

    public int takenHits() {
        return takenHits;
    }

    public int dealtHits() {
        return dealtHits;
    }

    /**
     * Vanilla armour reduction ({@code CombatRules.getDamageAfterAbsorb}, 1.21.1, without the armour-breach
     * enchantment): {@code damage * (1 - clamp(armor - damage / (2 + toughness / 4), armor / 5, 20) / 25)}.
     * Protection enchantments and resistance are not visible on other entities and are left out.
     */
    public static double afterArmor(double damage, double armor, double toughness) {
        if (damage <= 0) {
            return 0;
        }
        double f = 2.0 + toughness / 4.0;
        double eff = Math.max(armor / 5.0, Math.min(20.0, armor - damage / f));
        return damage * (1.0 - eff / 25.0);
    }
}
