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
        }
        for (int i = 0; i < hitTick.length; i++) {
            hitTick[i] = Long.MIN_VALUE;
            hitDamage[i] = 0f;
        }
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
        int i = s * RING + head[s];
        lateral[i] = lat;
        takeoff[i] = (byte) (wasGround[s] && !onGround ? 1 : 0);
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
            hops += takeoff[s * RING + k];
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
}
