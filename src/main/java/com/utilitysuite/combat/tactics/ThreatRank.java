package com.utilitysuite.combat.tactics;

/**
 * Re-pick of the fight target among nearby mobs, by threat, with hysteresis. Engine-free and allocation free: the
 * caller offers each candidate row (flags, distance, health) once per refresh and asks for a decision.
 * <p>
 * Score: a base for a mob that is already hostile, plus bonuses for what makes it dangerous right now (a swelling
 * creeper, a bow drawn at us from close range, a melee mob about to hit from behind), minus distance, plus a little
 * for low health (a mob close to dying is worth finishing). A swelling creeper, a skeleton drawing at close range or
 * a mob about to hit from behind outranks a zombie at half health in reach.
 * <p>
 * Hysteresis: the current target keeps {@link #CURRENT_BONUS} on top of its own score, and a switch needs a minimum
 * dwell of {@link #MIN_DWELL_TICKS} on the current target unless the challenger is an emergency (a swelling creeper
 * within its blast range). Noisy scores therefore never flap.
 * <p>
 * Reactive rule: a row without {@link #HOSTILE} (not already hostile to the player) never scores; neither does a row
 * the caller marks as never-target (warden, wither) or one without line of sight when sight is required.
 */
public final class ThreatRank {

    // ---- row flags (shared with the nearby-threat table; engine-free ints) -----------------------------------------
    /** Already hostile to the local player (attacking, charging, drawing at it, or the last thing that hit it). */
    public static final int HOSTILE = 1;
    /** A creeper that is swelling (its fuse is running). */
    public static final int SWELLING = 1 << 1;
    /** Drawing a bow or crossbow pointed at the player. */
    public static final int DRAWING = 1 << 2;
    /** In the rear half relative to where the player faces. */
    public static final int BEHIND = 1 << 3;
    /** Within melee reach of the player and facing it. */
    public static final int MELEE_IN_RANGE = 1 << 4;
    /** A ranged attacker (bow, crossbow, potions, fireballs). */
    public static final int RANGED = 1 << 5;
    /** A baby (zombie family: small box, fast, no knockback resistance to speak of). */
    public static final int BABY = 1 << 6;
    /** Clear line of sight from the player's eyes. */
    public static final int LOS = 1 << 7;

    // ---- score terms -----------------------------------------------------------------------------------------------
    static final double BASE = 100.0;
    static final double SWELLING_BONUS = 600.0;
    /** A swelling creeper inside this distance is an emergency (skips the dwell). */
    public static final double BLAST_EMERGENCY = 5.0;
    static final double DRAWING_BONUS = 300.0;
    /** A bow drawn at us counts as close inside this distance (half the bonus beyond it). */
    public static final double DRAWING_CLOSE = 10.0;
    static final double BEHIND_MELEE_BONUS = 250.0;
    static final double MELEE_BONUS = 60.0;
    static final double DISTANCE_WEIGHT = 5.0;
    static final double LOW_HEALTH_WEIGHT = 2.0;
    static final float FULL_HEALTH = 20f;

    /** Score kept by the current target on top of its own. */
    public static final double CURRENT_BONUS = 80.0;
    /** Ticks on a target before a non-emergency switch. */
    public static final int MIN_DWELL_TICKS = 15;

    private int current = -1;
    private long since;
    private double currentScore;
    private boolean currentSeen;
    private int best = -1;
    private double bestScore;
    private boolean bestEmergency;
    private int switches;

    /** Score of one row (no hysteresis). {@code Double.NEGATIVE_INFINITY} when the row may never be picked. */
    public static double score(int flags, double distance, float health, boolean neverTarget, boolean requireLos) {
        if ((flags & HOSTILE) == 0 || neverTarget || (requireLos && (flags & LOS) == 0)) {
            return Double.NEGATIVE_INFINITY;
        }
        double s = BASE - distance * DISTANCE_WEIGHT;
        if ((flags & SWELLING) != 0) {
            s += SWELLING_BONUS;
        }
        if ((flags & DRAWING) != 0) {
            s += distance <= DRAWING_CLOSE ? DRAWING_BONUS : DRAWING_BONUS * 0.5;
        }
        if ((flags & MELEE_IN_RANGE) != 0) {
            s += (flags & BEHIND) != 0 ? BEHIND_MELEE_BONUS : MELEE_BONUS;
        }
        float h = Math.max(0f, Math.min(FULL_HEALTH, health));
        s += (FULL_HEALTH - h) * LOW_HEALTH_WEIGHT;
        return s;
    }

    /** A swelling creeper inside its blast range: switches without waiting for the dwell. */
    public static boolean emergency(int flags, double distance) {
        return (flags & SWELLING) != 0 && distance <= BLAST_EMERGENCY;
    }

    /** Starts a decision round for the current target (call on every refresh, before {@link #offer}). */
    public void begin(int currentId, long clock) {
        if (currentId != current) {
            current = currentId;
            since = clock;
        }
        currentSeen = false;
        currentScore = Double.NEGATIVE_INFINITY;
        best = -1;
        bestScore = Double.NEGATIVE_INFINITY;
        bestEmergency = false;
    }

    /**
     * The current target's own score when it is not among the offered rows (a target the table does not list, such
     * as one past its range): without it the current target counts as unscored and any hostile row can take over.
     */
    public void currentScore(double score) {
        if (!currentSeen) {
            currentScore = score;
        }
    }

    /** Offers one candidate (the current target included) with its {@link #score}. */
    public void offer(int id, double score, boolean emergency) {
        if (id == current) {
            currentSeen = true;
            currentScore = score;
            return;
        }
        if (score == Double.NEGATIVE_INFINITY) {
            return;
        }
        if (score > bestScore) {
            best = id;
            bestScore = score;
            bestEmergency = emergency;
        }
    }

    /**
     * @return the id to switch to, or -1 to keep the current target. A switch is counted and restarts the dwell.
     */
    public int decide(long clock) {
        if (best < 0) {
            return -1;
        }
        double keep = currentScore == Double.NEGATIVE_INFINITY ? Double.NEGATIVE_INFINITY
                : currentScore + CURRENT_BONUS;
        if (bestScore <= keep) {
            return -1;
        }
        if (!bestEmergency && clock - since < MIN_DWELL_TICKS && currentScore != Double.NEGATIVE_INFINITY) {
            return -1;
        }
        current = best;
        since = clock;
        switches++;
        return best;
    }

    /** Switches decided since construction (tests, diagnostics). */
    public int switches() {
        return switches;
    }

    public void reset() {
        current = -1;
        since = 0;
        best = -1;
        currentSeen = false;
    }
}
