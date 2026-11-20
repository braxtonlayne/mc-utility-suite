package com.utilitysuite.travel.autopilot;

/**
 * Scores candidate elytra landing columns, engine-free. Rejected outright (score {@link #REJECT}): fluid or lava on top,
 * foliage (leaves/logs: trees), harmful blocks (fire, magma, cactus, ...), less than {@value #MIN_HEADROOM} blocks of
 * headroom, uneven ground (a neighbour more than one block higher or lower), or out of glide reach. Otherwise flatter,
 * closer sites whose approach slope is near the comfortable glide slope score higher.
 */
public final class LandingSiteScorer {

    public static final double REJECT = Double.NEGATIVE_INFINITY;
    public static final int MIN_HEADROOM = 3;
    /** Comfortable approach: about one block down per three forward. */
    public static final double IDEAL_SLOPE = 0.33;
    /** Steepest approach that keeps the sink rate below fall-damage speed. */
    public static final double MAX_SLOPE = 1.2;

    /**
     * A sampled column.
     *
     * @param maxNeighborDelta largest |height difference| to the 8 neighbours
     * @param headroom         open blocks above the surface
     */
    public record Sample(int surfaceY, boolean fluid, boolean lava, boolean foliage, boolean harmful,
                         int maxNeighborDelta, int headroom) {
    }

    private LandingSiteScorer() {
    }

    /**
     * @param s                  the column
     * @param horizontalDistance blocks from the glider to the column
     * @param altitudeAbove      glider height above the column's surface
     * @param glideRatio         forward blocks per block of height the glider can cover without rockets
     */
    public static double score(Sample s, double horizontalDistance, double altitudeAbove, double glideRatio) {
        if (s.lava() || s.fluid() || s.foliage() || s.harmful() || s.headroom() < MIN_HEADROOM
                || s.maxNeighborDelta() > 1) {
            return REJECT;
        }
        if (altitudeAbove < 0.0 || horizontalDistance > altitudeAbove * glideRatio) {
            return REJECT; // cannot reach it gliding
        }
        double slope = horizontalDistance < 1.0 ? MAX_SLOPE + 1.0 : altitudeAbove / horizontalDistance;
        double steepPenalty = slope > MAX_SLOPE ? 20.0 * (slope - MAX_SLOPE) : 0.0;
        return 100.0
                - 25.0 * s.maxNeighborDelta()
                - 0.2 * horizontalDistance
                - 30.0 * Math.abs(slope - IDEAL_SLOPE)
                - steepPenalty;
    }
}
