package com.utilitysuite.combat.cues;

import com.utilitysuite.combat.tactics.MaceDivePlanner;

/**
 * Engine-free tracker for the rising mace-fall tone. Each tick of a smash-capable fall it compares the mace damage the
 * fall would deal (base plus {@link MaceDivePlanner#smashBonus}) with the damage needed (the target's health, or the
 * FULL tier bonus when there is no target) and reports when the progress crosses the next fifth (a rising step, with a
 * pitch that climbs with progress) or reaches lethal (once per fall). Landing or leaving the smash range resets it.
 */
public final class MaceRiseCue {

    public static final int NONE = 0;
    public static final int RISE = 1;
    public static final int LETHAL = 2;
    /** Mace base attack damage. */
    public static final float BASE_DAMAGE = 6f;
    static final int STEPS = 5;

    private int step;
    private boolean lethalSent;
    private float pitch = 1f;

    /**
     * @param fall      current fall distance
     * @param density   Density enchantment level
     * @param needed    damage that kills the target, or NaN for "no target" (aim for the FULL tier)
     */
    public int update(boolean holdingMace, boolean onGround, float fall, int density, float needed) {
        if (!holdingMace || onGround || fall <= MaceDivePlanner.SMASH_MIN_FALL) {
            reset();
            return NONE;
        }
        float damage = BASE_DAMAGE + MaceDivePlanner.smashBonus(fall, density);
        float goal = Float.isNaN(needed) || needed <= 0f
                ? BASE_DAMAGE + MaceDivePlanner.smashBonus(MaceDivePlanner.fallFor(MaceDivePlanner.Tier.FULL), density)
                : needed;
        float progress = Math.min(1f, damage / goal);
        if (progress >= 1f) {
            if (!lethalSent) {
                lethalSent = true;
                pitch = 2f;
                return LETHAL;
            }
            return NONE;
        }
        int s = (int) (progress * STEPS);
        if (s > step) {
            step = s;
            pitch = 0.7f + 1.1f * progress;
            return RISE;
        }
        return NONE;
    }

    public float pitch() {
        return pitch;
    }

    public void reset() {
        step = 0;
        lethalSent = false;
        pitch = 1f;
    }
}
