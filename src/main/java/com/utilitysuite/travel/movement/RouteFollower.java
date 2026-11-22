package com.utilitysuite.travel.movement;

/**
 * Follows a planned route as a corridor, not a rail: steers at the farthest route cell that the simulated body can
 * reach in a straight run or jump, so corners are cut, flats are sprint-jumped and gaps the route walks around are
 * jumped when the jump is safe. Engine-free (positions are {@code BlockPos.asLong}-packed feet cells, see
 * {@code travel.Packed}); the caller applies the keys and turns the camera toward {@link #wantYaw()} through the core.
 * <p>
 * CONTRACT STUB (volley 2026-11-22): the public surface below is fixed so the route runner can be wired in parallel;
 * the follower task fills in the bodies and may add members, but must not change these signatures.
 * Allocation free after construction.
 */
public final class RouteFollower {

    /** Longest route kept (cells); a longer one is truncated and replanned on the way. */
    public static final int MAX_ROUTE = 256;

    private final long[] route = new long[MAX_ROUTE];
    private int length;
    private int progress;

    private int forward;
    private int strafe;
    private boolean jump;
    private boolean sprint;
    private float wantYaw;
    private boolean finished;
    private boolean offRoute;

    /** Replaces the route with the first {@code count} positions of {@code packed} (copied). */
    public void setRoute(long[] packed, int count) {
        length = Math.min(Math.max(count, 0), MAX_ROUTE);
        System.arraycopy(packed, 0, route, 0, length);
        progress = 0;
        finished = length == 0;
        offRoute = false;
    }

    public void clear() {
        length = 0;
        progress = 0;
        finished = false;
        offRoute = false;
        forward = 0;
        strafe = 0;
        jump = false;
        sprint = false;
    }

    public boolean hasRoute() {
        return length > 0;
    }

    /**
     * One decision per tick.
     *
     * @param now    the player's body this tick
     * @param yaw    the player's current yaw (the keys are chosen for this yaw; {@link #wantYaw()} is where to turn)
     * @param health for fall-damage limits (a jump that would cost health is never taken)
     */
    public void update(MovementPhysics now, float yaw, float health, MovementPhysics.Voxels w) {
        forward = 0;
        strafe = 0;
        jump = false;
        sprint = false;
        wantYaw = yaw;
    }

    /** +1 forward key, -1 back, 0 none. */
    public int forward() {
        return forward;
    }

    /** +1 left key, -1 right, 0 none. */
    public int strafe() {
        return strafe;
    }

    public boolean jump() {
        return jump;
    }

    public boolean sprint() {
        return sprint;
    }

    /** Yaw along the route at the steering point (look where you move). */
    public float wantYaw() {
        return wantYaw;
    }

    /** The body stands on the last route cell. */
    public boolean finished() {
        return finished;
    }

    /** The body left the corridor (knocked away, fell): the caller should replan. */
    public boolean offRoute() {
        return offRoute;
    }

    /** Index of the route cell the body has reached. */
    public int progressIndex() {
        return progress;
    }

    public int length() {
        return length;
    }

    /** Route cell {@code i} (packed), for overlays. */
    public long cell(int i) {
        return route[i];
    }
}
