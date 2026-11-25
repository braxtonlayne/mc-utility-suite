package com.utilitysuite.combat.tactics.memory;

/**
 * The remembered habits of the engaged opponent, flattened from its {@link OpponentMemory.Profile} once on engage
 * ({@link #fill}) into plain fields the fight loop and its stages read per tick without allocation. Cleared for an
 * unknown opponent, a mob, or with "Opponent memory" off, so every read below is then a no-op. Engine free; client
 * thread.
 * <ul>
 *   <li>{@link #pearlLikely}: a remembered pearl-escaper is at or near the health it usually pearls out at (the pearl
 *       countdown line pre-warns).</li>
 *   <li>{@link #deflectHorizon}: against a remembered wind-charge thrower the deflect stage starts planning earlier.</li>
 *   <li>{@link #diveSpacing}: against a remembered mace diver that is airborne above us with the mace in hand, the
 *       built-in spacing keeps extra distance so the dive lands short.</li>
 * </ul>
 */
public final class RecalledHabits {

    /** Shared "nothing remembered" view (never filled). */
    public static final RecalledHabits NONE = new RecalledHabits();

    /** Health margin (half hearts) above the remembered pearl-out health where the pre-warning starts. */
    public static final double PEARL_MARGIN = 2.0;
    /** Escape share that makes a pearl-escaper (with a remembered pearl-out health). */
    public static final double ESCAPE_SHARE = 0.5;
    /** Per-minute rates that make a habit worth acting on. */
    public static final double WIND_RATE = 0.5, DIVE_RATE = 0.5, RANGED_RATE = 1.0;
    /** Deflect planning horizon gain against a wind-charge thrower. */
    public static final double DEFLECT_HORIZON_GAIN = 1.5;
    /** Extra spacing (blocks) while a remembered diver is airborne above us with the mace. */
    public static final double DIVE_SPACING = 2.0;
    /** The diver counts as "above us" from this height over our feet (blocks). */
    public static final double DIVE_ABOVE = 1.0;

    private boolean known;
    private boolean pearlEscaper;
    private double pearlOutHp = Double.NaN;
    private boolean windThrower;
    private boolean maceDiver;
    private double diveHeight = Double.NaN;
    private boolean rangedUser;
    private double rangedRange = Double.NaN;
    private double drawTicks = Double.NaN;

    /** Engage: copies what is worth acting on from the profile (null clears). Not a tick path. */
    public void fill(OpponentMemory.Profile p) {
        if (this == NONE) {
            return;
        }
        clear();
        if (p == null || p.fights <= 0) {
            return;
        }
        known = true;
        pearlOutHp = p.pearlOutHp;
        pearlEscaper = !Double.isNaN(p.pearlOutHp)
                && (Double.isNaN(p.pearlEscapeShare) || p.pearlEscapeShare >= ESCAPE_SHARE);
        windThrower = OpponentMemory.at(p.windShotsPerMin, WIND_RATE);
        maceDiver = OpponentMemory.maceDiver(p);
        diveHeight = p.diveHeight;
        rangedUser = OpponentMemory.rangedUser(p);
        rangedRange = p.rangedRange;
        drawTicks = p.drawTicks;
    }

    public void clear() {
        known = false;
        pearlEscaper = false;
        pearlOutHp = Double.NaN;
        windThrower = false;
        maceDiver = false;
        diveHeight = Double.NaN;
        rangedUser = false;
        rangedRange = Double.NaN;
        drawTicks = Double.NaN;
    }

    /** The remembered pearl-escaper is at or near its usual pearl-out health. */
    public boolean pearlLikely(float targetHealth) {
        return known && pearlEscaper && targetHealth <= pearlOutHp + PEARL_MARGIN;
    }

    /** The deflect planning horizon (ticks) for this opponent. */
    public double deflectHorizon(double base) {
        return known && windThrower ? base * DEFLECT_HORIZON_GAIN : base;
    }

    /**
     * Extra spacing (blocks) to keep this tick, 0 when none.
     *
     * @param heldKind       {@code HabitRecorder.HELD_*} of the target's hand
     * @param heightAboveUs  target feet y minus ours
     */
    public double diveSpacing(int heldKind, boolean targetOnGround, double heightAboveUs) {
        if (!known || !maceDiver || targetOnGround || heldKind != HabitRecorder.HELD_MACE
                || heightAboveUs < DIVE_ABOVE) {
            return 0.0;
        }
        return DIVE_SPACING;
    }

    public boolean known() {
        return known;
    }

    public boolean pearlEscaper() {
        return pearlEscaper;
    }

    public double pearlOutHp() {
        return pearlOutHp;
    }

    public boolean windThrower() {
        return windThrower;
    }

    public boolean maceDiver() {
        return maceDiver;
    }

    public double diveHeight() {
        return diveHeight;
    }

    public boolean rangedUser() {
        return rangedUser;
    }

    public double rangedRange() {
        return rangedRange;
    }

    public double drawTicks() {
        return drawTicks;
    }
}
