package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.Angles;

/**
 * Picks the boat heading from a fan of water-clearance rays, engine-free. Rays are cast at fixed offsets around the
 * bearing to the next stop; each sample is the open-water distance along that ray. When the direct ray is clear the
 * bearing wins; otherwise the heading maximises clearance minus a penalty for straying from the bearing, which keeps
 * the boat in the open middle of a river while still heading for the stop.
 */
public final class BoatSteering {

    /** Ray offsets (degrees) relative to the bearing; index 0 is the direct ray. */
    public static final double[] OFFSETS = {0, -15, 15, -30, 30, -50, 50, -75, 75};
    public static final double ALIGN_PENALTY = 0.35;

    private BoatSteering() {
    }

    /**
     * @param bearingDeg  yaw toward the next stop
     * @param clearances  open-water distance per {@link #OFFSETS} entry
     * @param maxRange    ray length (clearance of an unobstructed ray)
     * @param needed      clearance that counts as "clear enough" for the direct ray (e.g. distance to the stop)
     * @return the yaw to steer toward
     */
    public static double chooseHeading(double bearingDeg, double[] clearances, double maxRange, double needed) {
        if (clearances[0] >= Math.min(maxRange, needed) - 1.0e-6) {
            return Angles.wrap(bearingDeg);
        }
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < OFFSETS.length; i++) {
            double score = clearances[i] / maxRange - ALIGN_PENALTY * Math.abs(OFFSETS[i]) / 90.0;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return Angles.wrap(bearingDeg + OFFSETS[best]);
    }
}
