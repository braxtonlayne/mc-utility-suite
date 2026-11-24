package com.utilitysuite.combat.tactics;

/**
 * Per-opponent habits in fixed primitive ring buffers: lateral strafe velocity, take-offs (crit-hop cadence), shield
 * use and the damage the opponent dealt. At most {@value #MAX_OPPONENTS} opponents; the least recently seen slot is
 * reused. Engine free (ids, doubles and tick counters only) and allocation free after construction, so a later hunter
 * bot can reuse it without client classes.
 * <p>
 * Lateral sign: positive = moving to the opponent's own right while facing the observer.
 */
public final class OpponentModel {

    public static final int MAX_OPPONENTS = 8;
    /** Samples kept per opponent (one per tick: the last 1.6 s). */
    public static final int RING = 32;
    /** Hits kept per opponent for the DPS estimate. */
    public static final int HIT_RING = 8;
    /** DPS window in ticks. */
    public static final int DPS_WINDOW = 100;

    private final int[] ids = new int[MAX_OPPONENTS];
    private final long[] lastSeen = new long[MAX_OPPONENTS];
    private final int[] head = new int[MAX_OPPONENTS];
    private final int[] count = new int[MAX_OPPONENTS];
    private final boolean[] wasGround = new boolean[MAX_OPPONENTS];
    private final float[] lateral = new float[MAX_OPPONENTS * RING];
    private final byte[] takeoff = new byte[MAX_OPPONENTS * RING];
    private final byte[] blocking = new byte[MAX_OPPONENTS * RING];
    private final long[] hitTick = new long[MAX_OPPONENTS * HIT_RING];
    private final float[] hitDamage = new float[MAX_OPPONENTS * HIT_RING];
    private final int[] hitHead = new int[MAX_OPPONENTS];
    /** Tick of the last arm swing seen per slot (Long.MIN_VALUE = none) and the cooldown period it started. */
    private final long[] swingTick = new long[MAX_OPPONENTS];
    private final double[] swingPeriod = new double[MAX_OPPONENTS];
    /** EMA of its swing-to-damage gap in ticks (NaN = none yet) and whether its last swing still awaits a hit. */
    private final double[] hitDelayEma = new double[MAX_OPPONENTS];
    private final boolean[] swingUnpaired = new boolean[MAX_OPPONENTS];
    private final int[] hitDelayCount = new int[MAX_OPPONENTS];
    /** Fight-clock tick until which the opponent's shield is disabled by our axe hit (Long.MIN_VALUE = not known). */
    private final long[] shieldDisabledUntil = new long[MAX_OPPONENTS];

    // ---- threat reads (reach, post-hit stun, healing, pace, retreat) -------------------------------------------------
    /** Eye-to-box distance (its eye to our box) at its hits on us, ring per slot. */
    private final float[] reachSample = new float[MAX_OPPONENTS * REACH_RING];
    private final int[] reachHead = new int[MAX_OPPONENTS];
    private final int[] reachCount = new int[MAX_OPPONENTS];
    /** Tick of our last landed hit (Long.MIN_VALUE = none) and whether a stun sample is still open for it. */
    private final long[] ownHitTick = new long[MAX_OPPONENTS];
    private final boolean[] stunOpen = new boolean[MAX_OPPONENTS];
    /** Ticks from our landed hit (it was charged) to its next swing, ring per slot. */
    private final byte[] stunSample = new byte[MAX_OPPONENTS * STUN_RING];
    private final int[] stunHead = new int[MAX_OPPONENTS];
    private final int[] stunCount = new int[MAX_OPPONENTS];
    /** Health seen last tick (NaN = none) and its rises (tick, points) for the heal-rate read. */
    private final float[] lastHealth = new float[MAX_OPPONENTS];
    private final long[] healTick = new long[MAX_OPPONENTS * HEAL_RING];
    private final float[] healAmount = new float[MAX_OPPONENTS * HEAL_RING];
    private final int[] healHead = new int[MAX_OPPONENTS];
    /** Average closing speed on ticks it ran at us (NaN = never seen). */
    private final double[] chaseSpeed = new double[MAX_OPPONENTS];
    /** Retreat read: consecutive qualifying ticks, and the read itself. */
    private final int[] retreatRun = new int[MAX_OPPONENTS];
    private final boolean[] retreating = new boolean[MAX_OPPONENTS];

    /** Reach samples kept per opponent, and the number needed before the learned reach replaces the default. */
    public static final int REACH_RING = 8;
    public static final int MIN_REACH_SAMPLES = 3;
    /**
     * Bounds of the learned reach (eye to box). The floor keeps a few close-range hits from claiming a short reach:
     * we never plan inside 2.7 of an opponent on its hits alone.
     */
    public static final double MIN_LEARNED_REACH = 2.7;
    public static final double MAX_LEARNED_REACH = 4.5;
    /** Stun samples kept, the number needed, the cap of one sample and of the learned stun (ticks). */
    public static final int STUN_RING = 4;
    public static final int MIN_STUN_SAMPLES = 2;
    public static final int STUN_SAMPLE_CAP = 20;
    public static final int MAX_STUN = 5;
    /** Heal rises kept, and the window of the heal-rate read (ticks). */
    public static final int HEAL_RING = 32;
    public static final int HEAL_WINDOW = 200;
    /** A take-off this soon after our landed hit is knockback lift or a combo-break hop, not crit pressure. */
    public static final int COMBO_BREAK_WINDOW = 20;
    /** Closing speed (blocks per tick) from which a tick counts as running at us, and the average's smoothing. */
    public static final double CHASE_MIN = 0.15;
    public static final double CHASE_ALPHA = 0.15;
    /** Retreat read: speed away from us, facing within this angle of straight away, for this many ticks. */
    public static final double RETREAT_SPEED = 0.2;
    public static final double RETREAT_FACING_DEG = 45.0;
    /** Once read, the retreat holds while it faces within this angle of straight away (it slows at a wall). */
    public static final double RETREAT_HOLD_DEG = 70.0;
    public static final int RETREAT_TICKS = 5;
    /** No swing seen for this many ticks before a retreat can be read. */
    public static final int RETREAT_QUIET = 10;
    private static final double COS_RETREAT_FACING = Math.cos(Math.toRadians(RETREAT_FACING_DEG));
    private static final double COS_RETREAT_HOLD = Math.cos(Math.toRadians(RETREAT_HOLD_DEG));

    /** Attack speed clamp (attacks per second) and the period used when nothing is known (sword, 1.6/s). */
    public static final double MIN_ATTACK_SPEED = 0.25;
    public static final double MAX_ATTACK_SPEED = 16.0;
    public static final double DEFAULT_COOLDOWN_TICKS = 12.5;
    /** Swing-to-damage gaps above this are not one swing and its hit (a miss, then a later hit): ignored. */
    public static final int HIT_DELAY_SAMPLE_MAX = 6;
    public static final double HIT_DELAY_ALPHA = 0.3;
    public static final int MIN_HIT_DELAY_SAMPLES = 2;
    public static final int MIN_HIT_DELAY = 0;
    public static final int MAX_HIT_DELAY = 4;

    public OpponentModel() {
        clear();
    }

    public void clear() {
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            ids[i] = -1;
            lastSeen[i] = Long.MIN_VALUE;
            head[i] = 0;
            count[i] = 0;
            hitHead[i] = 0;
            wasGround[i] = true;
            swingTick[i] = Long.MIN_VALUE;
            swingPeriod[i] = DEFAULT_COOLDOWN_TICKS;
            shieldDisabledUntil[i] = Long.MIN_VALUE;
            clearReads(i);
        }
        for (int i = 0; i < hitTick.length; i++) {
            hitTick[i] = Long.MIN_VALUE;
            hitDamage[i] = 0f;
        }
    }

    private void clearReads(int s) {
        reachHead[s] = 0;
        reachCount[s] = 0;
        ownHitTick[s] = Long.MIN_VALUE;
        stunOpen[s] = false;
        stunHead[s] = 0;
        stunCount[s] = 0;
        lastHealth[s] = Float.NaN;
        healHead[s] = 0;
        for (int k = 0; k < HEAL_RING; k++) {
            healTick[s * HEAL_RING + k] = Long.MIN_VALUE;
            healAmount[s * HEAL_RING + k] = 0f;
        }
        chaseSpeed[s] = Double.NaN;
        hitDelayEma[s] = Double.NaN;
        swingUnpaired[s] = false;
        hitDelayCount[s] = 0;
        retreatRun[s] = 0;
        retreating[s] = false;
    }

    /** Slot of {@code id}, or -1. */
    public int slotOf(int id) {
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    private int slotFor(int id, long tick) {
        int s = slotOf(id);
        if (s >= 0) {
            return s;
        }
        int oldest = 0;
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            if (ids[i] == -1) {
                oldest = i;
                break;
            }
            if (lastSeen[i] < lastSeen[oldest]) {
                oldest = i;
            }
        }
        ids[oldest] = id;
        head[oldest] = 0;
        count[oldest] = 0;
        hitHead[oldest] = 0;
        wasGround[oldest] = true;
        swingTick[oldest] = Long.MIN_VALUE;
        swingPeriod[oldest] = DEFAULT_COOLDOWN_TICKS;
        shieldDisabledUntil[oldest] = Long.MIN_VALUE;
        clearReads(oldest);
        for (int k = 0; k < HIT_RING; k++) {
            hitTick[oldest * HIT_RING + k] = Long.MIN_VALUE;
            hitDamage[oldest * HIT_RING + k] = 0f;
        }
        lastSeen[oldest] = tick;
        return oldest;
    }

    /**
     * One observation per tick.
     *
     * @param ox,oz   opponent position; {@code ovx,ovz} its horizontal velocity
     * @param px,pz   observer position
     */
    public void observe(int id, long tick, double ox, double oz, double ovx, double ovz, double px, double pz,
                        boolean onGround, boolean isBlocking) {
        int s = slotFor(id, tick);
        lastSeen[s] = tick;
        double fx = px - ox;
        double fz = pz - oz;
        double len = Math.sqrt(fx * fx + fz * fz);
        float lat = 0f;
        if (len > 1.0e-3) {
            fx /= len;
            fz /= len;
            // right of a forward vector (fx, fz) is (-fz, fx)
            lat = (float) (ovx * -fz + ovz * fx);
        }
        if (len > 1.0e-3) {
            double closing = ovx * fx + ovz * fz;
            if (closing >= CHASE_MIN) {
                double c = chaseSpeed[s];
                chaseSpeed[s] = c != c ? closing : c + CHASE_ALPHA * (closing - c);
            }
        }
        if (stunOpen[s] && tick - ownHitTick[s] >= STUN_SAMPLE_CAP) {
            addStun(s, STUN_SAMPLE_CAP); // no swing for a second after our hit
        }
        int i = s * RING + head[s];
        lateral[i] = lat;
        boolean took = wasGround[s] && !onGround;
        boolean afterOurHit = ownHitTick[s] != Long.MIN_VALUE && tick - ownHitTick[s] <= COMBO_BREAK_WINDOW;
        takeoff[i] = (byte) (took ? (afterOurHit ? 2 : 1) : 0);
        blocking[i] = (byte) (isBlocking ? 1 : 0);
        wasGround[s] = onGround;
        head[s] = (head[s] + 1) % RING;
        if (count[s] < RING) {
            count[s]++;
        }
    }

    /** Damage the opponent dealt to the observer. */
    public void recordHit(int id, long tick, float damage) {
        int s = slotFor(id, tick);
        lastSeen[s] = tick;
        int i = s * HIT_RING + hitHead[s];
        hitTick[i] = tick;
        hitDamage[i] = damage;
        hitHead[s] = (hitHead[s] + 1) % HIT_RING;
        if (swingUnpaired[s]) {
            swingUnpaired[s] = false;
            long gap = tick - swingTick[s];
            if (swingTick[s] != Long.MIN_VALUE && gap >= 0 && gap <= HIT_DELAY_SAMPLE_MAX) {
                double prev = hitDelayEma[s];
                hitDelayEma[s] = prev != prev ? gap : prev + HIT_DELAY_ALPHA * (gap - prev);
                hitDelayCount[s]++;
            }
        }
    }

    public int samples(int id) {
        int s = slotOf(id);
        return s < 0 ? 0 : count[s];
    }

    /** -1..1: consistent strafing to its right (+) or left (-); 0 for no data or no preference. */
    public double strafeBias(int id) {
        int s = slotOf(id);
        if (s < 0 || count[s] == 0) {
            return 0.0;
        }
        double sum = 0;
        double abs = 0;
        for (int k = 0; k < count[s]; k++) {
            float v = lateral[s * RING + k];
            sum += v;
            abs += Math.abs(v);
        }
        return abs < 1.0e-6 ? 0.0 : sum / abs;
    }

    /** Take-offs per second over the buffered window (crit-hop cadence). */
    public double hopRate(int id) {
        int s = slotOf(id);
        if (s < 0 || count[s] == 0) {
            return 0.0;
        }
        int hops = 0;
        for (int k = 0; k < count[s]; k++) {
            if (takeoff[s * RING + k] != 0) {
                hops++;
            }
        }
        return hops * 20.0 / count[s];
    }

    /**
     * Take-offs per second that are its own choice: {@link #hopRate} without the ones within
     * {@value #COMBO_BREAK_WINDOW} ticks of our landed hit (the knockback's own lift and the hop it answers it with).
     * Those are reactions to our hits, not crit pressure, and keeping distance from them only gives up tempo.
     */
    public double pressureHopRate(int id) {
        int s = slotOf(id);
        if (s < 0 || count[s] == 0) {
            return 0.0;
        }
        int hops = 0;
        for (int k = 0; k < count[s]; k++) {
            if (takeoff[s * RING + k] == 1) {
                hops++;
            }
        }
        return hops * 20.0 / count[s];
    }

    /** Fraction 0..1 of buffered ticks the opponent spent blocking. */
    public double shieldUse(int id) {
        int s = slotOf(id);
        if (s < 0 || count[s] == 0) {
            return 0.0;
        }
        int b = 0;
        for (int k = 0; k < count[s]; k++) {
            b += blocking[s * RING + k];
        }
        return (double) b / count[s];
    }

    /** Damage per second the opponent dealt within the last {@value #DPS_WINDOW} ticks. */
    public double dps(int id, long now) {
        int s = slotOf(id);
        if (s < 0) {
            return 0.0;
        }
        double sum = 0;
        for (int k = 0; k < HIT_RING; k++) {
            long t = hitTick[s * HIT_RING + k];
            if (t != Long.MIN_VALUE && now - t <= DPS_WINDOW && now >= t) {
                sum += hitDamage[s * HIT_RING + k];
            }
        }
        return sum * 20.0 / DPS_WINDOW;
    }

    // ---- cooldown reading ----------------------------------------------------------------------------------------

    /**
     * The opponent started an arm swing on this tick: its attack cooldown restarts.
     *
     * @param attackSpeed attacks per second with its held item (4.0 bare hand, 1.6 sword, 1.0 axe)
     */
    public void noteSwing(int id, long tick, double attackSpeed) {
        int s = slotFor(id, tick);
        lastSeen[s] = tick;
        if (stunOpen[s] && tick > ownHitTick[s]) {
            addStun(s, (int) Math.min(STUN_SAMPLE_CAP, tick - ownHitTick[s]));
        }
        retreatRun[s] = 0;
        retreating[s] = false; // a swing ends any retreat read
        swingTick[s] = tick;
        swingUnpaired[s] = true;
        if (attackSpeed != attackSpeed) {
            swingPeriod[s] = DEFAULT_COOLDOWN_TICKS; // NaN
        } else {
            swingPeriod[s] = 20.0 / Math.max(MIN_ATTACK_SPEED, Math.min(MAX_ATTACK_SPEED, attackSpeed));
        }
    }

    /**
     * Ticks until the opponent's next hit would be at full charge; 0 when it is ready now or it was never seen
     * swinging (an unread opponent counts as ready). Allocation free.
     */
    public int attackReadyIn(int id, long now) {
        int s = slotOf(id);
        if (s < 0 || swingTick[s] == Long.MIN_VALUE) {
            return 0;
        }
        double remaining = swingPeriod[s] - (double) (now - swingTick[s]);
        return remaining <= 1.0e-9 ? 0 : (int) Math.ceil(remaining - 1.0e-9);
    }

    /** The opponent's attack charge 0..1 right now (1 = full, also when unknown). */
    public float attackCharge(int id, long now) {
        int s = slotOf(id);
        if (s < 0 || swingTick[s] == Long.MIN_VALUE) {
            return 1f;
        }
        double c = (double) (now - swingTick[s]) / swingPeriod[s];
        return (float) (c < 0.0 ? 0.0 : Math.min(c, 1.0));
    }

    /**
     * Its effective hit delay in ticks: the exponential average of the gap from its swing edge to the damage it then
     * dealt us (ping, its aim and our own latency all included), rounded and clamped to
     * [{@value #MIN_HIT_DELAY}, {@value #MAX_HIT_DELAY}]. {@code fallback} until {@value #MIN_HIT_DELAY_SAMPLES}
     * paired samples have been seen. Allocation free.
     */
    public int hitDelay(int id, int fallback) {
        int s = slotOf(id);
        if (s < 0 || hitDelayEma[s] != hitDelayEma[s] || hitDelayCount[s] < MIN_HIT_DELAY_SAMPLES) {
            return fallback;
        }
        long r = Math.round(hitDelayEma[s]);
        return (int) Math.max(MIN_HIT_DELAY, Math.min(MAX_HIT_DELAY, r));
    }

    /** Its full cooldown period in ticks (20 / attack speed of the last swing seen; 12.5 when unknown). */
    public double cooldownTicks(int id) {
        int s = slotOf(id);
        return s < 0 || swingTick[s] == Long.MIN_VALUE ? DEFAULT_COOLDOWN_TICKS : swingPeriod[s];
    }

    // ---- shield disable window -----------------------------------------------------------------------------------

    /** Ticks an axe hit on a raised shield disables it (vanilla 1.21.1 {@code Player.disableShield}: cooldown 100). */
    public static final int SHIELD_DISABLE_TICKS = 100;

    /**
     * Our axe hit landed on the opponent's raised shield (or its disable was observed): the shield cannot be raised
     * again before fight-clock tick {@code untilTick}. A later value replaces an earlier one; an earlier one is ignored.
     */
    public void noteShieldDisabled(int id, long untilTick) {
        int s = slotFor(id, untilTick - SHIELD_DISABLE_TICKS);
        if (untilTick > shieldDisabledUntil[s]) {
            shieldDisabledUntil[s] = untilTick;
        }
    }

    /** Fight-clock tick until which the opponent's shield is disabled; Long.MIN_VALUE when none was recorded. */
    public long shieldDisabledUntil(int id) {
        int s = slotOf(id);
        return s < 0 ? Long.MIN_VALUE : shieldDisabledUntil[s];
    }

    /** True while a recorded shield disable of the opponent is still running at {@code now}. */
    public boolean shieldDisabled(int id, long now) {
        int s = slotOf(id);
        return s >= 0 && shieldDisabledUntil[s] != Long.MIN_VALUE && now < shieldDisabledUntil[s];
    }

    /** Ticks left of the opponent's shield disable at {@code now} (0 = none running). */
    public int shieldDisabledFor(int id, long now) {
        int s = slotOf(id);
        if (s < 0 || shieldDisabledUntil[s] == Long.MIN_VALUE || now >= shieldDisabledUntil[s]) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, shieldDisabledUntil[s] - now);
    }

    // ---- reach learned from its hits -------------------------------------------------------------------------------

    /**
     * One of its hits landed on us while its eye was {@code eyeToBox} from the nearest point of our box (measured on
     * the tick before the hit was seen, before our knockback moved us).
     */
    public void noteHitDistance(int id, long tick, double eyeToBox) {
        if (!(eyeToBox >= 0.0) || eyeToBox > 16.0) {
            return; // NaN or nonsense (a teleport between ticks)
        }
        int s = slotFor(id, tick);
        lastSeen[s] = tick;
        reachSample[s * REACH_RING + reachHead[s]] = (float) eyeToBox;
        reachHead[s] = (reachHead[s] + 1) % REACH_RING;
        if (reachCount[s] < REACH_RING) {
            reachCount[s]++;
        }
    }

    /** Hit distances recorded for this opponent (at most {@value #REACH_RING}). */
    public int reachSamples(int id) {
        int s = slotOf(id);
        return s < 0 ? 0 : reachCount[s];
    }

    /** True once {@value #MIN_REACH_SAMPLES} hit distances were seen. */
    public boolean reachLearned(int id) {
        return reachSamples(id) >= MIN_REACH_SAMPLES;
    }

    /**
     * Its effective reach (eye to box) from its hits on us: the second-longest hit seen (the longest with fewer than
     * four samples, so one lag outlier does not stretch it), clamped to [{@value #MIN_LEARNED_REACH},
     * {@value #MAX_LEARNED_REACH}]. {@code fallback} until {@value #MIN_REACH_SAMPLES} hits were seen. Allocation free.
     */
    public double learnedReach(int id, double fallback) {
        int s = slotOf(id);
        if (s < 0 || reachCount[s] < MIN_REACH_SAMPLES) {
            return fallback;
        }
        float first = -1f;
        float second = -1f;
        for (int k = 0; k < reachCount[s]; k++) {
            float v = reachSample[s * REACH_RING + k];
            if (v > first) {
                second = first;
                first = v;
            } else if (v > second) {
                second = v;
            }
        }
        double r = reachCount[s] >= 4 ? second : first;
        return Math.max(MIN_LEARNED_REACH, Math.min(MAX_LEARNED_REACH, r));
    }

    // ---- recovery after our hit ----------------------------------------------------------------------------------

    /**
     * Our hit landed on it this tick. Opens a stun sample when its attack was charged (then the time to its next swing
     * is its recovery, not its cooldown) and marks the window in which its take-offs are reactions, not pressure.
     */
    public void noteOwnHit(int id, long tick) {
        int s = slotFor(id, tick);
        lastSeen[s] = tick;
        boolean charged = attackReadyIn(id, tick) == 0;
        ownHitTick[s] = tick;
        stunOpen[s] = charged;
        retreatRun[s] = 0;
    }

    private void addStun(int s, int ticks) {
        stunOpen[s] = false;
        stunSample[s * STUN_RING + stunHead[s]] = (byte) Math.max(0, Math.min(STUN_SAMPLE_CAP, ticks));
        stunHead[s] = (stunHead[s] + 1) % STUN_RING;
        if (stunCount[s] < STUN_RING) {
            stunCount[s]++;
        }
    }

    /**
     * Ticks after our landed hit in which it does not swing back although charged: the shortest recovery seen over
     * the last {@value #STUN_RING} samples minus one (the swing tick itself), capped at {@value #MAX_STUN}; 0 until
     * {@value #MIN_STUN_SAMPLES} samples (an unknown opponent can answer at once). The minimum keeps a fast answer from
     * ever being forgotten behind slow ones.
     */
    public int hitStun(int id) {
        int s = slotOf(id);
        if (s < 0 || stunCount[s] < MIN_STUN_SAMPLES) {
            return 0;
        }
        int min = Integer.MAX_VALUE;
        for (int k = 0; k < stunCount[s]; k++) {
            min = Math.min(min, stunSample[s * STUN_RING + k]);
        }
        return Math.max(0, Math.min(MAX_STUN, min - 1));
    }

    // ---- healing ---------------------------------------------------------------------------------------------------

    /** Its health this tick; every rise is recorded as healing (regeneration, food, potions). */
    public void noteHealth(int id, long tick, float health) {
        int s = slotFor(id, tick);
        lastSeen[s] = tick;
        float last = lastHealth[s];
        lastHealth[s] = health;
        if (last == last && health > last + 0.01f) {
            int i = s * HEAL_RING + healHead[s];
            healTick[i] = tick;
            healAmount[i] = health - last;
            healHead[s] = (healHead[s] + 1) % HEAL_RING;
        }
    }

    /** Health points it healed per second over the last {@value #HEAL_WINDOW} ticks. */
    public double healRate(int id, long now) {
        int s = slotOf(id);
        if (s < 0) {
            return 0.0;
        }
        double sum = 0;
        for (int k = 0; k < HEAL_RING; k++) {
            long t = healTick[s * HEAL_RING + k];
            if (t != Long.MIN_VALUE && now >= t && now - t <= HEAL_WINDOW) {
                sum += healAmount[s * HEAL_RING + k];
            }
        }
        return sum * 20.0 / HEAL_WINDOW;
    }

    // ---- pace ------------------------------------------------------------------------------------------------------

    /** Its average speed (blocks per tick) on ticks it ran at us; NaN when it never did. */
    public double chaseSpeed(int id) {
        int s = slotOf(id);
        return s < 0 ? Double.NaN : chaseSpeed[s];
    }

    // ---- retreat ---------------------------------------------------------------------------------------------------

    /**
     * Retreat read, once per tick after {@link #observe}: it faces away from us (within {@value #RETREAT_FACING_DEG}
     * degrees) and moves away at {@value #RETREAT_SPEED}+ blocks per tick for {@value #RETREAT_TICKS} ticks, with no
     * swing for {@value #RETREAT_QUIET} ticks. Once read it holds while it keeps facing away (within
     * {@value #RETREAT_HOLD_DEG} degrees, so slowing at a wall does not end it) and does not swing. A person facing
     * away cannot hit us. Allocation free.
     *
     * @param yaw its body yaw (Minecraft convention: view vector (-sin, cos))
     */
    public void observeRetreat(int id, long tick, float yaw, double ox, double oz, double ovx, double ovz, double px,
                               double pz) {
        int s = slotFor(id, tick);
        double ax = ox - px;
        double az = oz - pz;
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1.0e-3) {
            retreatRun[s] = 0;
            retreating[s] = false;
            return;
        }
        ax /= len;
        az /= len;
        double yr = Math.toRadians(yaw);
        double facing = -Math.sin(yr) * ax + Math.cos(yr) * az; // cosine of its view off "straight away from us"
        double away = ovx * ax + ovz * az;
        boolean quiet = swingTick[s] == Long.MIN_VALUE || tick - swingTick[s] >= RETREAT_QUIET;
        if (retreating[s]) {
            retreating[s] = facing >= COS_RETREAT_HOLD;
            if (!retreating[s]) {
                retreatRun[s] = 0;
            }
            return;
        }
        if (quiet && facing >= COS_RETREAT_FACING && away >= RETREAT_SPEED) {
            if (++retreatRun[s] >= RETREAT_TICKS) {
                retreating[s] = true;
            }
        } else {
            retreatRun[s] = 0;
        }
    }

    /** The opponent is read as retreating (see {@link #observeRetreat}). */
    public boolean retreating(int id) {
        int s = slotOf(id);
        return s >= 0 && retreating[s];
    }
}
