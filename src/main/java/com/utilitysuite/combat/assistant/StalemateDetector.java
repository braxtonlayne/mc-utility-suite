package com.utilitysuite.combat.assistant;

/**
 * Per-tick stalemate bookkeeping: a fight in which nothing happens. A tick counts toward a stall when
 * <ul>
 *   <li>no damage was dealt or taken (neither health dropped: no swing of ours landed, none of theirs),</li>
 *   <li>the target is not approaching or opening the distance on purpose (a smoothed radial speed within
 *       {@value #RADIAL_STILL} blocks per tick either way: pacing back and forth averages out; walking in or running
 *       off does not),</li>
 *   <li>we made no progress toward it (the distance did not fall {@value #PROGRESS} blocks below where the count
 *       started),</li>
 *   <li>and it cannot be hit from here: either it is out of reach ({@value #OUT_OF_REACH} blocks, feet to feet) or
 *       it is {@link #unreachable unreachable} (higher than a jump or lower than a safe drop).</li>
 * </ul>
 * Any tick that fails one of these restarts the count, and so does {@code busy} (eating, retreating, a pearl chase).
 * A normal spacing dance stays inside reach and lands hits; one-sided pressure drops a health bar; neither stalls.
 * Engine free, allocation free; a handful of arithmetic per tick. Client thread only.
 */
public final class StalemateDetector {

    /** Feet-to-feet distance beyond which neither side can hit (3 blocks of reach plus the hover margin). */
    public static final double OUT_OF_REACH = 4.5;
    /** The target's feet this far above ours: beyond a jump (1.25 blocks). */
    public static final double JUMP_RISE = 1.3;
    /** The target's feet this far below ours: beyond a drop without fall damage. */
    public static final double SAFE_DROP = 3.0;
    /** Smoothing of the target's radial speed (per tick; about a one-second window). */
    public static final double RADIAL_ALPHA = 0.05;
    /** Smoothed radial speed (blocks per tick) within which the target counts as neither approaching nor leaving. */
    public static final double RADIAL_STILL = 0.06;
    /** Getting this much closer than where the count started restarts it (a chase that is gaining is no stall). */
    public static final double PROGRESS = 1.0;
    /** Health drop that counts as a hit (absorption included). */
    static final float HIT_EPSILON = 0.01f;

    private int stallTicks;
    private double radial;
    private double closest = Double.MAX_VALUE;
    private float lastOwn = -1f;
    private float lastTarget = -1f;
    private boolean exchanged;
    private double distance;
    private boolean unreachableNow;

    /** Forget everything (a new engagement, a new target). */
    public void reset() {
        stallTicks = 0;
        radial = 0.0;
        closest = Double.MAX_VALUE;
        lastOwn = -1f;
        lastTarget = -1f;
        exchanged = false;
        distance = 0.0;
        unreachableNow = false;
    }

    /** Restart the stall count only (the healths and the radial read stay). */
    public void restart() {
        stallTicks = 0;
        closest = Double.MAX_VALUE;
    }

    /**
     * One engaged tick.
     *
     * @param busy the fight is doing something else on purpose (eating, retreating, a pearl chase)
     * @return the stall length in ticks after this tick (0 = not stalled)
     */
    public int observe(CombatSnapshot s, boolean busy) {
        float own = s.effectiveHealth();
        float target = s.targetHealth;
        exchanged = (lastOwn >= 0f && own < lastOwn - HIT_EPSILON) || (lastTarget >= 0f && target < lastTarget - HIT_EPSILON);
        lastOwn = own;
        lastTarget = target;

        double dx = s.px - s.tx;
        double dz = s.pz - s.tz;
        double dy = s.ty - s.py;
        double h = Math.sqrt(dx * dx + dz * dz);
        double toward = h > 0.5 ? (s.tdx * dx + s.tdz * dz) / h : 0.0; // + = the target closes in (real motion)
        radial += (toward - radial) * RADIAL_ALPHA;
        distance = Math.sqrt(h * h + dy * dy);
        unreachableNow = unreachable(dy);

        // progress: closer than where the count (re)started by PROGRESS; the anchor only moves on a restart, so a
        // slow steady gain still counts while pacing back and forth around one spot does not
        boolean progress = distance < closest - PROGRESS;
        boolean cannotHit = unreachableNow || distance > OUT_OF_REACH;
        if (busy || exchanged || Math.abs(radial) > RADIAL_STILL || !cannotHit || (progress && stallTicks > 0)) {
            stallTicks = 0;
            closest = distance;
        } else {
            if (stallTicks == 0) {
                closest = distance; // the anchor of a new count
            }
            stallTicks++;
        }
        return stallTicks;
    }

    /** True when the target stands higher than a jump or lower than a safe drop (feet height difference). */
    public static boolean unreachable(double dyTargetMinusOurs) {
        return dyTargetMinusOurs > JUMP_RISE || dyTargetMinusOurs < -SAFE_DROP;
    }

    public int stallTicks() {
        return stallTicks;
    }

    /** A health bar dropped this tick (ours or the target's). */
    public boolean exchanged() {
        return exchanged;
    }

    /** Smoothed speed (blocks per tick) at which the target closes in; negative while it moves away. */
    public double radialSpeed() {
        return radial;
    }

    /** True while the smoothed radial speed says the target walks in. */
    public boolean approaching() {
        return radial > RADIAL_STILL;
    }

    /** Feet-to-feet distance of the last tick. */
    public double distance() {
        return distance;
    }

    /** The last tick's {@link #unreachable} read. */
    public boolean unreachableNow() {
        return unreachableNow;
    }
}
