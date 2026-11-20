package com.utilitysuite.travel.terrain;

/**
 * Surface traversal's on/off state plus its capability check, engine-free. While active, every server position
 * correction is counted ({@link CorrectionCounter}); reaching the limit switches the mode off and reports it once, so
 * the module warns the user instead of fighting the server. Corrections while inactive are ignored.
 */
public final class SurfaceGuard {

    private final CorrectionCounter counter = new CorrectionCounter();
    private boolean active;

    public void configure(int threshold, int windowTicks, double teleportDistance) {
        counter.configure(threshold, windowTicks, teleportDistance);
    }

    public boolean isActive() {
        return active;
    }

    /** Turns the mode on with a fresh correction count. */
    public void activate() {
        counter.reset();
        active = true;
    }

    public void deactivate() {
        active = false;
    }

    /**
     * A server correction arrived. Returns true exactly when this correction made the guard switch the mode off
     * (the caller warns the user).
     */
    public boolean onCorrection(long tick, double distance) {
        if (!active) {
            return false;
        }
        if (counter.record(tick, distance)) {
            active = false;
            return true;
        }
        return false;
    }

    public int threshold() {
        return counter.threshold();
    }
}
