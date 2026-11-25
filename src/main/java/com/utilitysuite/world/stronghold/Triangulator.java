package com.utilitysuite.world.stronghold;

/**
 * Engine-free state of the two-throw stronghold finder. Each throw is a horizontal ray (origin + direction). The first
 * throw is kept; a second throw whose origin is at least {@code minBaseline} blocks away is intersected with it. A throw
 * closer than that replaces the stored one (the player is told to walk further). Throws expire after
 * {@code expiryTicks}; the owner resets on a dimension change.
 */
public final class Triangulator {

    /** Outcome of {@link #addThrow}. */
    public enum Result { FIRST_STORED, TOO_CLOSE, NEAR_PARALLEL, LOCATED }

    /** Rays closer in angle than this cannot be intersected reliably. */
    public static final double MIN_ANGLE_DEG = 2.0;

    private boolean hasFirst;
    private double ox;
    private double oz;
    private double dx;
    private double dz;
    private long firstTick;
    private StrongholdMath.Estimate last;

    public Result addThrow(double x, double z, double dirX, double dirZ, long tick, double minBaseline, boolean snap,
                           long expiryTicks) {
        if (hasFirst && tick - firstTick > expiryTicks) {
            hasFirst = false;
        }
        if (!hasFirst) {
            store(x, z, dirX, dirZ, tick);
            return Result.FIRST_STORED;
        }
        if (Math.hypot(x - ox, z - oz) < minBaseline) {
            store(x, z, dirX, dirZ, tick); // the newer, probably steadier throw from about the same spot
            return Result.TOO_CLOSE;
        }
        StrongholdMath.Hit hit = StrongholdMath.intersect(ox, oz, dx, dz, x, z, dirX, dirZ, MIN_ANGLE_DEG);
        if (hit == null) {
            store(x, z, dirX, dirZ, tick);
            return Result.NEAR_PARALLEL;
        }
        last = StrongholdMath.estimate(hit.x(), hit.z(), snap);
        hasFirst = false;
        return Result.LOCATED;
    }

    private void store(double x, double z, double dirX, double dirZ, long tick) {
        hasFirst = true;
        ox = x;
        oz = z;
        dx = dirX;
        dz = dirZ;
        firstTick = tick;
    }

    /** The estimate from the last LOCATED throw. */
    public StrongholdMath.Estimate last() {
        return last;
    }

    public boolean hasFirst() {
        return hasFirst;
    }

    public double firstX() {
        return ox;
    }

    public double firstZ() {
        return oz;
    }

    public void reset() {
        hasFirst = false;
        last = null;
    }

    /** Drops a stored throw older than {@code expiryTicks}; true when one was dropped. */
    public boolean expire(long tick, long expiryTicks) {
        if (hasFirst && tick - firstTick > expiryTicks) {
            hasFirst = false;
            return true;
        }
        return false;
    }
}
