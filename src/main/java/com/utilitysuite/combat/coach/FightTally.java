package com.utilitysuite.combat.coach;

import java.util.Locale;

/**
 * The coach's per-fight counters for the post-fight summary: hits landed and taken, crits among the landed hits and
 * the average horizontal spacing over the fight's ticks. Engine free (fed primitives once per tick by
 * {@link FightCoach}), primitive fields only, allocation free per tick; {@link #formatLine()} allocates once, at the
 * end of a fight.
 * <p>
 * Reads, all estimates from what the client sees:
 * <ul>
 *   <li><b>landed</b>: the opponent's hurt animation restarted within {@value #SWING_ATTRIBUTION_TICKS} ticks of our
 *       own attack (our recharge dropped by more than {@value #SWING_RESET_DROP});</li>
 *   <li><b>crit</b>: that attack left while charged and falling (the tick before the recharge dropped);</li>
 *   <li><b>taken</b>: our health plus absorption dropped while the opponent was within {@value #TAKEN_RANGE} blocks or
 *       had swung within {@value #OPP_SWING_WINDOW} ticks, at most once per {@value #TAKEN_DEBOUNCE_TICKS} ticks.</li>
 * </ul>
 * A fight starts at the first hit either way and ends when the opponent is gone for {@value #END_ABSENT_TICKS} ticks,
 * nobody hits for {@value #END_QUIET_TICKS} ticks, or the coach switches to another opponent.
 */
final class FightTally {

    static final int SWING_ATTRIBUTION_TICKS = 3;
    static final float SWING_RESET_DROP = 0.3f;
    static final double TAKEN_RANGE = 6.0;
    static final int OPP_SWING_WINDOW = 4;
    static final int TAKEN_DEBOUNCE_TICKS = 8;
    static final int END_ABSENT_TICKS = 40;
    static final int END_QUIET_TICKS = 200;
    /** Fights with fewer hits (both ways) than this end without a summary (a mob's stray hit). */
    static final int MIN_HITS_TO_POST = 2;
    private static final int AGE_CAP = 1 << 20;

    // fight
    private boolean active;
    private int opponentId = -1;
    private int landed;
    private int taken;
    private int crits;
    private double distSum;
    private int distTicks;
    private int quietTicks;
    private int absentTicks;

    // detection baselines
    private float prevStrength = -1f;
    private boolean prevCritReady;
    private int swingAge = AGE_CAP;
    private boolean swingCrit;
    private int prevHurtId = -1;
    private int prevHurt;
    private float prevHealth = -1f;
    private int takenAge = AGE_CAP;
    private int oppSwingAge = AGE_CAP;

    /**
     * One tick with an opponent shown.
     *
     * @param id             the opponent's entity id
     * @param distance       horizontal distance to it
     * @param attackStrength our attack recharge 0..1
     * @param critReady      a swing now would crit (charged and falling)
     * @param targetHurtTime its hurt-animation ticks
     * @param effHealth      our health plus absorption
     * @param oppSwing       it started an arm swing this tick
     * @return true when a fight ended this tick: read the counters (or {@link #formatLine()}), then
     *         {@link #clearFight()}
     */
    boolean observe(int id, double distance, float attackStrength, boolean critReady, int targetHurtTime,
                    float effHealth, boolean oppSwing) {
        // our own attack: the recharge resets when a swing leaves
        if (prevStrength >= 0f && attackStrength < prevStrength - SWING_RESET_DROP) {
            swingAge = 0;
            swingCrit = prevCritReady;
        } else if (swingAge < AGE_CAP) {
            swingAge++;
        }
        prevStrength = attackStrength;
        prevCritReady = critReady;
        oppSwingAge = oppSwing ? 0 : Math.min(AGE_CAP, oppSwingAge + 1);

        boolean hit = id == prevHurtId && targetHurtTime > prevHurt && swingAge <= SWING_ATTRIBUTION_TICKS;
        prevHurtId = id;
        prevHurt = targetHurtTime;

        if (takenAge < AGE_CAP) {
            takenAge++;
        }
        boolean hurt = prevHealth >= 0f && effHealth < prevHealth - 0.01f && takenAge >= TAKEN_DEBOUNCE_TICKS
                && (distance <= TAKEN_RANGE || oppSwingAge <= OPP_SWING_WINDOW);
        if (hurt) {
            takenAge = 0;
        }
        prevHealth = effHealth;

        if (active && id != opponentId) {
            active = false; // another opponent: this fight is over (its first hit starts the next one)
            return true;
        }
        if (!active) {
            if (!hit && !hurt) {
                return false;
            }
            clearFight();
            active = true;
            opponentId = id;
        }
        distSum += distance;
        distTicks++;
        absentTicks = 0;
        if (hit) {
            landed++;
            if (swingCrit) {
                crits++;
            }
            swingAge = AGE_CAP; // one swing, one hit
        }
        if (hurt) {
            taken++;
        }
        if (hit || hurt) {
            quietTicks = 0;
        } else if (++quietTicks >= END_QUIET_TICKS) {
            active = false;
            return true;
        }
        return false;
    }

    /** One tick with no opponent shown. @return true when a fight ended this tick (see {@link #observe}). */
    boolean absent() {
        prevStrength = -1f;
        prevHurtId = -1;
        prevHealth = -1f;
        swingAge = AGE_CAP;
        oppSwingAge = AGE_CAP;
        if (active && ++absentTicks >= END_ABSENT_TICKS) {
            active = false;
            return true;
        }
        return false;
    }

    /** Drops the counters of the last fight (call after reading an ended fight). */
    void clearFight() {
        active = false;
        opponentId = -1;
        landed = 0;
        taken = 0;
        crits = 0;
        distSum = 0.0;
        distTicks = 0;
        quietTicks = 0;
        absentTicks = 0;
    }

    /** Everything back to idle (world unload, module off): no summary. */
    void reset() {
        clearFight();
        prevStrength = -1f;
        prevCritReady = false;
        swingAge = AGE_CAP;
        swingCrit = false;
        prevHurtId = -1;
        prevHurt = 0;
        prevHealth = -1f;
        takenAge = AGE_CAP;
        oppSwingAge = AGE_CAP;
    }

    boolean active() {
        return active;
    }

    int landed() {
        return landed;
    }

    int taken() {
        return taken;
    }

    int crits() {
        return crits;
    }

    /** Average horizontal spacing over the fight's ticks (0 with none). */
    double avgSpacing() {
        return distTicks == 0 ? 0.0 : distSum / distTicks;
    }

    /** Enough happened for a summary to be worth a notice. */
    boolean worthPosting() {
        return landed + taken >= MIN_HITS_TO_POST;
    }

    /** The one-line summary of the ended fight (allocates: once per fight). */
    String formatLine() {
        return format(landed, taken, crits, avgSpacing());
    }

    /** e.g. {@code "Fight: landed 7 / taken 4, crits 43%, avg spacing 2.9"}; crits "-" when nothing landed. */
    static String format(int landed, int taken, int crits, double avgSpacing) {
        String critText = landed > 0 ? Math.round(100.0 * crits / landed) + "%" : "-";
        return "Fight: landed " + landed + " / taken " + taken + ", crits " + critText + ", avg spacing "
                + String.format(Locale.ROOT, "%.1f", avgSpacing);
    }
}
