package com.utilitysuite.travel.movement;

/**
 * Estimates which block the player means to land on. Candidate landing cells around the player are scored by the look
 * ray, the held keys, the current velocity and the line of the recent jumps; the winner is the cell the jump search
 * and the turn gain aim for ("the block they were looking at", not just progress along the heading).
 * <p>
 * CONTRACT STUB (volley 2026-11-22): the public surface below is fixed so the movement assist can be wired in
 * parallel; the estimator task fills in the bodies and may add members, but must not change these signatures.
 * Engine-free and allocation free after construction.
 */
public final class IntentEstimator {

    /** Below this confidence the assist treats the intent as unknown and keeps its heading-based behaviour. */
    public static final double MIN_CONFIDENCE = 0.5;

    private boolean hasTarget;
    private int tx, ty, tz;
    private double confidence;
    private float idealYaw;

    /** A jump the player made (takeoff and landing feet positions), kept as the "line of recent jumps". */
    public void noteJump(double takeoffX, double takeoffZ, double landX, double landZ) {
    }

    /** Forgets the jump line (teleport, world change, long stop). */
    public void reset() {
        hasTarget = false;
        confidence = 0.0;
    }

    /**
     * One estimate per tick, budgeted (a bounded number of candidate cells).
     *
     * @param userForward the user's forward axis (-1, 0, 1)
     * @param userStrafe  the user's strafe axis (+1 left, -1 right, 0)
     * @return true when a target with at least {@link #MIN_CONFIDENCE} was found
     */
    public boolean estimate(MovementPhysics now, float yaw, float pitch, int userForward, int userStrafe,
                            boolean sprinting, MovementPhysics.Voxels w) {
        hasTarget = false;
        confidence = 0.0;
        idealYaw = yaw;
        return false;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    /** The block to stand on (the solid block; feet land at y + 1). */
    public int targetX() {
        return tx;
    }

    public int targetY() {
        return ty;
    }

    public int targetZ() {
        return tz;
    }

    /** 0..1: how clearly the best candidate beats the runner-up. */
    public double confidence() {
        return confidence;
    }

    /** Yaw from the player's position to the centre of the target block's top face. */
    public float idealYaw() {
        return idealYaw;
    }
}
