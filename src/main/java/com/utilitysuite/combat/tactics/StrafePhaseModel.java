package com.utilitysuite.combat.tactics;

/**
 * Learns the rhythm of an opponent's strafing (how long it holds a side before it reverses) so the aim can lead by
 * where the opponent is about to be, not by its current velocity, and so our own strafe can move with it. Fixed
 * primitive arrays for up to {@value #MAX_OPPONENTS} opponents; engine free and allocation free after construction.
 * <p>
 * Lateral sign as in {@link OpponentModel}: positive = moving to the opponent's own right while facing the observer
 * (that is the observer's left).
 * <p>
 * CONTRACT STUB: signatures are fixed for the volley; the bodies are filled in by the anticipatory-aim task. Until
 * then the confidence is 0 and {@link #predictOffset} is the plain velocity lead.
 */
public final class StrafePhaseModel {

    public static final int MAX_OPPONENTS = 8;

    /** One sample per tick: opponent position and velocity (blocks per tick), observer position. */
    public void observe(int id, long tick, double ox, double oz, double ovx, double ovz, double px, double pz) {
    }

    /** 0..1: how regular the reversals have been (0 = no pattern or too few reversals seen). */
    public double confidence(int id) {
        return 0.0;
    }

    /** Typical ticks between reversals, or 0 when unknown. */
    public double periodTicks(int id) {
        return 0.0;
    }

    /** Expected ticks until the next reversal (0 = due now); {@code Integer.MAX_VALUE} when unknown. */
    public int ticksToReversal(int id, long now) {
        return Integer.MAX_VALUE;
    }

    /** Expected lateral sign {@code ticksAhead} ticks from now: +1, -1, or 0 when unknown or standing. */
    public int lateralSignAt(int id, long now, int ticksAhead) {
        return 0;
    }

    /**
     * World-space displacement of the opponent over the next {@code leadTicks}, written to {@code outXZ[0..1]}.
     * With confidence it follows the learned phase (the lateral part flips at the expected reversal, blended by the
     * confidence); without it this is {@code velocity * leadTicks}.
     *
     * @return the confidence used, 0..1
     */
    public double predictOffset(int id, long now, double leadTicks, double ovx, double ovz, double[] outXZ) {
        outXZ[0] = ovx * leadTicks;
        outXZ[1] = ovz * leadTicks;
        return 0.0;
    }

    public void forget(int id) {
    }

    public void clear() {
    }
}
