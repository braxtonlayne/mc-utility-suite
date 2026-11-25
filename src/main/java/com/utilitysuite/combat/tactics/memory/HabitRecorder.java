package com.utilitysuite.combat.tactics.memory;

/**
 * Per-fight recorder of one opponent's ender-pearl, ranged and mace habits (C6). Engine free: the combat assistant
 * feeds it the target's per-tick state ({@link #tick}: held item kind, item use, airborne height, arm swings) and the
 * projectiles the target spawned ({@link #onProjectile}: entity join events matched to the target by owner or spawn
 * point, with the spawn velocity), then turns it into an {@link OpponentMemory.HabitSummary} when the fight ends.
 * <ul>
 *   <li><b>Pearls</b>: throws per minute, the share thrown at low health ({@value #LOW_HP} or less), to escape (the
 *       pearl flies away from us) or to engage (toward us), the target's health at escape pearls, and its distance
 *       from us at the throw.</li>
 *   <li><b>Ranged</b>: arrow / trident releases per minute, bow / crossbow / trident shares, draw time (the ticks the
 *       target was using the ranged item before the shot; the crossbow's charge) and its distance from us at the
 *       release.</li>
 *   <li><b>Mace</b>: wind-charge launches (a charge thrown steeply down: at its own feet) and wind charges thrown at
 *       us, per minute; dives (an airborne span with the mace in hand that fell at least {@value #DIVE_MIN_FALL}
 *       blocks before the swing or the landing) per minute and their typical fall height.</li>
 * </ul>
 * {@link #tick} and {@link #onProjectile} are allocation free (primitive counters only); client thread.
 */
public final class HabitRecorder {

    /** Target held-item kinds ({@code CombatSnapshot.targetHeld}). */
    public static final int HELD_OTHER = 0, HELD_BOW = 1, HELD_CROSSBOW = 2, HELD_TRIDENT = 3, HELD_MACE = 4;
    /** Projectile kinds of {@link #onProjectile}. */
    public static final int PROJ_PEARL = 1, PROJ_ARROW = 2, PROJ_TRIDENT = 3, PROJ_WIND = 4;

    /** "Low health" for a pearl: at or below this (half hearts, absorption included). */
    public static final float LOW_HP = 8f;
    /** Cosine between the pearl's flight and the line to us that counts as toward (engage) / away (escape). */
    public static final double DIRECTION_COS = 0.3;
    /** Smallest fall (blocks) that counts as a mace dive (a jump crit falls about 1.25). */
    public static final double DIVE_MIN_FALL = 2.0;
    /** Fight ticks before per-minute rates are reported (shorter fights say little about frequency). */
    public static final int MIN_TICKS_FOR_RATES = 200;
    /** Attacks (melee swings plus ranged shots) before attack shares are reported. */
    public static final int MIN_ATTACKS_FOR_SHARES = 3;
    /** A finished draw is credited to a shot this many ticks later at most (a crossbow is charged ahead). */
    public static final int DRAW_MEMORY_TICKS = 100;
    /** A wind charge whose downward speed is more than this share of its speed was thrown at the feet (a launch). */
    public static final double LAUNCH_DOWN_SHARE = 0.5;
    /** Arrows and tridents slower than this (blocks/tick) are lying around, not shots. */
    public static final double MIN_SHOT_SPEED = 0.5;

    private static final double TICKS_PER_MINUTE = 1200.0;

    // ---- per fight counters ----
    private int ticks;
    private int pearls, pearlsLowHp, pearlsEscape, pearlsEngage;
    private double escapeHpSum, pearlDistSum;
    private int ranged, bow, crossbow, trident;
    private double drawSum, rangeSum;
    private int draws;
    private int windLaunches, windShots;
    private int dives;
    private double diveFallSum;
    private int melee;

    // ---- per tick state ----
    private long clock;
    private int held;
    private int useStreak;
    private int lastUseLen;
    private long lastUseEnd;
    private boolean airborne;
    private double peakY;
    private boolean diveCounted;

    /** A new fight (or a new target): every counter back to zero. */
    public void reset() {
        ticks = 0;
        pearls = pearlsLowHp = pearlsEscape = pearlsEngage = 0;
        escapeHpSum = pearlDistSum = 0.0;
        ranged = bow = crossbow = trident = 0;
        drawSum = rangeSum = 0.0;
        draws = 0;
        windLaunches = windShots = 0;
        dives = 0;
        diveFallSum = 0.0;
        melee = 0;
        clock = 0L;
        held = HELD_OTHER;
        useStreak = lastUseLen = 0;
        lastUseEnd = 0L;
        airborne = false;
        peakY = 0.0;
        diveCounted = false;
    }

    /**
     * One engaged tick of the target.
     *
     * @param heldKind     {@code HELD_*} of the item in the target's hand (the used item while using one)
     * @param using        the target is using an item (drawing a bow, charging a crossbow or trident; not a shield)
     * @param swingStarted the target's arm swing started this tick
     */
    public void tick(long clock, boolean onGround, double y, int heldKind, boolean using, boolean swingStarted) {
        ticks++;
        this.clock = clock;
        held = heldKind;
        boolean rangedHeld = isRanged(heldKind);
        if (using && rangedHeld) {
            useStreak++;
        } else if (useStreak > 0) {
            lastUseLen = useStreak;
            lastUseEnd = clock;
            useStreak = 0;
        }
        if (swingStarted && !rangedHeld) {
            melee++;
        }
        if (!onGround) {
            if (!airborne) {
                airborne = true;
                peakY = y;
                diveCounted = false;
            } else if (y > peakY) {
                peakY = y;
            }
            if (swingStarted && heldKind == HELD_MACE) {
                noteDive(peakY - y); // the hit comes before the landing
            }
        } else if (airborne) {
            airborne = false;
            if (heldKind == HELD_MACE) {
                noteDive(peakY - y);
            }
        }
    }

    private void noteDive(double fall) {
        if (diveCounted || fall < DIVE_MIN_FALL) {
            return;
        }
        diveCounted = true;
        dives++;
        diveFallSum += fall;
    }

    /**
     * A projectile the target spawned (owner matched by the caller), with its spawn velocity.
     *
     * @param targetHealth the target's health plus absorption right now
     * @param tx           target feet x/z, {@code ux}/{@code uz} ours
     */
    public void onProjectile(int kind, double vx, double vy, double vz, float targetHealth, double tx, double tz,
                             double ux, double uz) {
        double hx = ux - tx, hz = uz - tz;
        double dist = Math.sqrt(hx * hx + hz * hz);
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        switch (kind) {
            case PROJ_PEARL -> {
                pearls++;
                pearlDistSum += dist;
                if (targetHealth <= LOW_HP) {
                    pearlsLowHp++;
                }
                double vh = Math.sqrt(vx * vx + vz * vz);
                if (vh > 1.0e-6 && dist > 1.0e-6) {
                    double cos = (vx * hx + vz * hz) / (vh * dist);
                    if (cos >= DIRECTION_COS) {
                        pearlsEngage++;
                    } else if (cos <= -DIRECTION_COS) {
                        pearlsEscape++;
                        escapeHpSum += targetHealth;
                    }
                }
            }
            case PROJ_ARROW, PROJ_TRIDENT -> {
                if (speed < MIN_SHOT_SPEED) {
                    return;
                }
                ranged++;
                if (kind == PROJ_TRIDENT) {
                    trident++;
                } else if (held == HELD_CROSSBOW) {
                    crossbow++;
                } else {
                    bow++;
                }
                rangeSum += dist;
                int d = useStreak > 0 ? useStreak
                        : (lastUseLen > 0 && clock - lastUseEnd <= DRAW_MEMORY_TICKS ? lastUseLen : 0);
                if (d > 0) {
                    drawSum += d;
                    draws++;
                    useStreak = 0; // one draw, one shot (the streak's end must not credit it again)
                    lastUseLen = 0;
                }
            }
            case PROJ_WIND -> {
                if (speed > 1.0e-6 && vy < -LAUNCH_DOWN_SHARE * speed) {
                    windLaunches++;
                } else {
                    windShots++;
                }
            }
            default -> {
            }
        }
    }

    static boolean isRanged(int heldKind) {
        return heldKind == HELD_BOW || heldKind == HELD_CROSSBOW || heldKind == HELD_TRIDENT;
    }

    // ---- results ---------------------------------------------------------------------------------------------------

    public int ticks() {
        return ticks;
    }

    public int pearls() {
        return pearls;
    }

    public int rangedShots() {
        return ranged;
    }

    public int dives() {
        return dives;
    }

    public int windLaunches() {
        return windLaunches;
    }

    public int windShots() {
        return windShots;
    }

    private double perMinute(int n) {
        return ticks >= MIN_TICKS_FOR_RATES ? n * TICKS_PER_MINUTE / ticks : Double.NaN;
    }

    private static double share(int n, int of) {
        return of > 0 ? (double) n / of : Double.NaN;
    }

    /** The target's health at escape pearls (mean), or NaN. */
    public double pearlOutHp() {
        return pearlsEscape > 0 ? escapeHpSum / pearlsEscape : Double.NaN;
    }

    /** Share of the target's attacks that were ranged shots, or NaN with too few attacks. */
    public double rangedRate() {
        int attacks = melee + ranged;
        return attacks >= MIN_ATTACKS_FOR_SHARES ? (double) ranged / attacks : Double.NaN;
    }

    /** Share of the target's attacks that were mace dives, or NaN with too few attacks. */
    public double maceRate() {
        int attacks = melee + ranged;
        return attacks >= MIN_ATTACKS_FOR_SHARES ? Math.min(1.0, (double) dives / attacks) : Double.NaN;
    }

    /** What this fight showed (allocates one record; fight end only). */
    public OpponentMemory.HabitSummary summary() {
        return new OpponentMemory.HabitSummary(
                perMinute(pearls), share(pearlsLowHp, pearls), share(pearlsEscape, pearls),
                share(pearlsEngage, pearls), pearls > 0 ? pearlDistSum / pearls : Double.NaN,
                perMinute(ranged), share(bow, ranged), share(crossbow, ranged), share(trident, ranged),
                draws > 0 ? drawSum / draws : Double.NaN, ranged > 0 ? rangeSum / ranged : Double.NaN,
                perMinute(windLaunches), perMinute(windShots), perMinute(dives),
                dives > 0 ? diveFallSum / dives : Double.NaN);
    }
}
