package com.utilitysuite.combat.tactics;

/**
 * Learns the rhythm of an opponent's strafing (how long it holds a side before it reverses) so the aim can lead by
 * where the opponent is about to be, not by its current velocity, and so our own strafe can move with it. Fixed
 * primitive arrays for up to {@value #MAX_OPPONENTS} opponents (least recently seen slot reused, as in
 * {@link OpponentModel}); engine free and allocation free after construction. Client thread only (one shared scratch
 * array for the median).
 * <p>
 * Lateral sign as in {@link OpponentModel}: positive = moving to the opponent's own right while facing the observer
 * (that is the observer's left).
 * <p>
 * Reversal detection: samples inside {@link #DEAD_ZONE} count as "no side"; a new side has to persist for
 * {@link #MIN_HOLD} samples before it is accepted (1-2 tick jitter is ignored), and the reversal is dated back to the
 * first sample of the new side. The last {@link #HOLDS} hold durations feed a median period and a confidence from
 * their coefficient of variation. A read that is wrong or stale fades back to the plain velocity lead.
 */
public final class StrafePhaseModel {

    public static final int MAX_OPPONENTS = 8;
    /** Hold durations kept per opponent. */
    public static final int HOLDS = 8;
    /** Lateral speed (blocks per tick) below which a sample has no side. Walking strafe is about 0.1-0.2. */
    public static final double DEAD_ZONE = 0.03;
    /** Samples a new side must persist before it counts as a reversal. */
    public static final int MIN_HOLD = 3;
    /** Consecutive no-side samples after which the opponent counts as standing (sign 0). */
    public static final int STAND_TICKS = 8;
    /** An observation gap longer than this restarts the phase (holds are kept). */
    public static final int GAP_TICKS = 20;
    /** Coefficient of variation at which the confidence reaches 0. */
    public static final double CV_ZERO = 0.4;
    /** Confidence below this is treated as no pattern by {@link #predictOffset}; it reaches full weight at 0.9. */
    public static final double USE_FLOOR = 0.3;
    private static final double USE_FULL = 0.9;

    private final int[] ids = new int[MAX_OPPONENTS];
    private final long[] lastSeen = new long[MAX_OPPONENTS];
    /** Accepted side: +1, -1, or 0 (unknown / standing). */
    private final int[] side = new int[MAX_OPPONENTS];
    private final int[] candSide = new int[MAX_OPPONENTS];
    private final int[] candRun = new int[MAX_OPPONENTS];
    private final long[] candStart = new long[MAX_OPPONENTS];
    private final int[] zeroRun = new int[MAX_OPPONENTS];
    private final long[] lastReversal = new long[MAX_OPPONENTS];
    private final int[] holds = new int[MAX_OPPONENTS * HOLDS];
    private final int[] holdHead = new int[MAX_OPPONENTS];
    private final int[] holdCount = new int[MAX_OPPONENTS];
    /** Unit vector from the opponent to the observer at the last sample (0,0 = none). */
    private final double[] fwdX = new double[MAX_OPPONENTS];
    private final double[] fwdZ = new double[MAX_OPPONENTS];
    /** Cached period / spread, refreshed when a hold is recorded. */
    private final double[] period = new double[MAX_OPPONENTS];
    private final double[] baseConfidence = new double[MAX_OPPONENTS];
    private final double[] scratch = new double[HOLDS];

    public StrafePhaseModel() {
        clear();
    }

    public void clear() {
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            ids[i] = -1;
            reset(i, Long.MIN_VALUE);
        }
    }

    public void forget(int id) {
        int s = slotOf(id);
        if (s >= 0) {
            ids[s] = -1;
            reset(s, Long.MIN_VALUE);
        }
    }

    private void reset(int s, long tick) {
        lastSeen[s] = tick;
        holdHead[s] = 0;
        holdCount[s] = 0;
        period[s] = 0.0;
        baseConfidence[s] = 0.0;
        fwdX[s] = 0.0;
        fwdZ[s] = 0.0;
        restartPhase(s);
    }

    private void restartPhase(int s) {
        side[s] = 0;
        candSide[s] = 0;
        candRun[s] = 0;
        candStart[s] = Long.MIN_VALUE;
        zeroRun[s] = 0;
        lastReversal[s] = Long.MIN_VALUE;
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
        reset(oldest, tick);
        return oldest;
    }

    /** One sample per tick: opponent position and velocity (blocks per tick), observer position. */
    public void observe(int id, long tick, double ox, double oz, double ovx, double ovz, double px, double pz) {
        int s = slotFor(id, tick);
        if (lastSeen[s] != Long.MIN_VALUE && tick - lastSeen[s] > GAP_TICKS) {
            restartPhase(s);
        }
        lastSeen[s] = tick;
        double fx = px - ox;
        double fz = pz - oz;
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1.0e-3) {
            return; // on top of each other: no lateral axis this tick
        }
        fx /= len;
        fz /= len;
        fwdX[s] = fx;
        fwdZ[s] = fz;
        // right of a forward vector (fx, fz) is (-fz, fx)
        double lat = ovx * -fz + ovz * fx;
        int raw = lat > DEAD_ZONE ? 1 : (lat < -DEAD_ZONE ? -1 : 0);

        if (raw == 0) {
            if (++zeroRun[s] >= STAND_TICKS && side[s] != 0) {
                side[s] = 0; // standing: the next side starts a fresh phase
                lastReversal[s] = Long.MIN_VALUE;
                candSide[s] = 0;
                candRun[s] = 0;
            }
            return; // a pass through the dead zone neither confirms nor breaks a candidate
        }
        zeroRun[s] = 0;
        if (raw == side[s]) {
            candSide[s] = 0;
            candRun[s] = 0;
            return;
        }
        if (raw != candSide[s]) {
            candSide[s] = raw;
            candRun[s] = 0;
            candStart[s] = tick;
        }
        if (++candRun[s] < MIN_HOLD) {
            return;
        }
        // accepted
        int previous = side[s];
        side[s] = raw;
        candSide[s] = 0;
        candRun[s] = 0;
        if (previous == 0) {
            return; // first side seen (or resumed from standing): not a reversal, phase unknown until the next one
        }
        long at = candStart[s];
        if (lastReversal[s] != Long.MIN_VALUE) {
            long hold = at - lastReversal[s];
            if (hold > 0) {
                holds[s * HOLDS + holdHead[s]] = (int) Math.min(hold, Integer.MAX_VALUE);
                holdHead[s] = (holdHead[s] + 1) % HOLDS;
                if (holdCount[s] < HOLDS) {
                    holdCount[s]++;
                }
                refresh(s);
            }
        }
        lastReversal[s] = at;
    }

    private void refresh(int s) {
        int n = holdCount[s];
        double sum = 0;
        for (int k = 0; k < n; k++) {
            double h = holds[s * HOLDS + k];
            sum += h;
            // insertion sort into scratch for the median
            int j = k;
            while (j > 0 && scratch[j - 1] > h) {
                scratch[j] = scratch[j - 1];
                j--;
            }
            scratch[j] = h;
        }
        double median = (n & 1) == 1 ? scratch[n / 2] : 0.5 * (scratch[n / 2 - 1] + scratch[n / 2]);
        double mean = sum / n;
        double var = 0;
        for (int k = 0; k < n; k++) {
            double d = holds[s * HOLDS + k] - mean;
            var += d * d;
        }
        double cv = mean > 0 ? Math.sqrt(var / n) / mean : 1.0;
        period[s] = median;
        if (n < 2) {
            baseConfidence[s] = 0.0; // fewer than 3 reversals
        } else {
            double regularity = Math.max(0.0, 1.0 - cv / CV_ZERO);
            double amount = Math.min(1.0, n / 4.0);
            baseConfidence[s] = regularity * amount;
        }
    }

    /** 0..1: how regular the reversals have been (0 = no pattern or too few reversals seen). */
    public double confidence(int id) {
        int s = slotOf(id);
        return s < 0 ? 0.0 : confidenceAt(s, lastSeen[s]);
    }

    private double confidenceAt(int s, long now) {
        double c = baseConfidence[s];
        if (c <= 0.0 || side[s] == 0 || lastReversal[s] == Long.MIN_VALUE) {
            return 0.0;
        }
        // overdue reversal (the rhythm broke or it became a circle-strafe): fade out over one more period
        double p = period[s];
        double since = now - lastReversal[s];
        double late = since - (1.5 * p + 2.0);
        if (late > 0) {
            c *= Math.max(0.0, 1.0 - late / Math.max(1.0, p));
        }
        return c;
    }

    /** Typical ticks between reversals, or 0 when unknown. */
    public double periodTicks(int id) {
        int s = slotOf(id);
        return s < 0 || holdCount[s] < 2 ? 0.0 : period[s];
    }

    private boolean phaseKnown(int s) {
        return holdCount[s] >= 2 && side[s] != 0 && lastReversal[s] != Long.MIN_VALUE && period[s] > 0;
    }

    /** Expected ticks until the next reversal (0 = due now); {@code Integer.MAX_VALUE} when unknown. */
    public int ticksToReversal(int id, long now) {
        int s = slotOf(id);
        if (s < 0 || !phaseKnown(s)) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0L, Math.round(period[s] - (now - lastReversal[s])));
    }

    /** Expected lateral sign {@code ticksAhead} ticks from now: +1, -1, or 0 when unknown or standing. */
    public int lateralSignAt(int id, long now, int ticksAhead) {
        int s = slotOf(id);
        if (s < 0 || side[s] == 0) {
            return 0;
        }
        int sign = side[s];
        if (ticksAhead <= 0 || !phaseKnown(s)) {
            return sign;
        }
        double t0 = Math.max(0.0, period[s] - (now - lastReversal[s]));
        if (ticksAhead < t0) {
            return sign;
        }
        long flips = 1 + (long) Math.floor((ticksAhead - t0) / period[s]);
        return (flips & 1L) == 1L ? -sign : sign;
    }

    /**
     * World-space displacement of the opponent over the next {@code leadTicks}, written to {@code outXZ[0..1]}.
     * With confidence it follows the learned phase (the lateral part flips at the expected reversal, blended by the
     * confidence); without it this is {@code velocity * leadTicks}. The lateral part is never predicted farther than
     * the plain lead, so a wrong read degrades to the plain lead, not to a wild aim point.
     *
     * @return the confidence used, 0..1
     */
    public double predictOffset(int id, long now, double leadTicks, double ovx, double ovz, double[] outXZ) {
        double plainX = ovx * leadTicks;
        double plainZ = ovz * leadTicks;
        outXZ[0] = plainX;
        outXZ[1] = plainZ;
        int s = slotOf(id);
        if (s < 0 || leadTicks <= 0 || !phaseKnown(s) || (fwdX[s] == 0.0 && fwdZ[s] == 0.0)) {
            return 0.0;
        }
        double raw = confidenceAt(s, now);
        double c = Math.max(0.0, Math.min(1.0, (raw - USE_FLOOR) / (USE_FULL - USE_FLOOR)));
        if (c <= 0.0) {
            return 0.0;
        }
        double fx = fwdX[s];
        double fz = fwdZ[s];
        double rx = -fz;
        double rz = fx;
        double vRad = ovx * fx + ovz * fz;
        double vLat = ovx * rx + ovz * rz;
        double plainLat = vLat * leadTicks;
        // integrate the lateral speed, flipping its sign at each expected reversal inside the lead window
        double p = period[s];
        double t0 = Math.max(0.0, p - (now - lastReversal[s]));
        double phaseLat;
        if (leadTicks <= t0) {
            phaseLat = plainLat;
        } else {
            phaseLat = vLat * t0;
            double rest = leadTicks - t0;
            double sign = -1.0;
            int guard = 0;
            while (rest > 0 && guard++ < 64) {
                double seg = Math.min(rest, p);
                phaseLat += sign * vLat * seg;
                rest -= seg;
                sign = -sign;
            }
        }
        double lat = (1.0 - c) * plainLat + c * phaseLat;
        double cap = Math.abs(plainLat);
        if (lat > cap) {
            lat = cap;
        } else if (lat < -cap) {
            lat = -cap;
        }
        double rad = vRad * leadTicks;
        outXZ[0] = fx * rad + rx * lat;
        outXZ[1] = fz * rad + rz * lat;
        return c;
    }
}
