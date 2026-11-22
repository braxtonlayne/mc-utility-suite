package com.utilitysuite.travel.movement;

/**
 * Tick-phase check at an edge: is jumping on this tick N (at the current yaw) better than jumping on tick N+1 (at the
 * yaw the user's mouse will have turned to)? N+1 is only an option while the body is still supported on N+1, so the
 * check never walks the player off an edge waiting. Scores are target-aware ({@link JumpSearch}) and, without a
 * target, measured along the current heading for both takeoffs. Two jump simulations plus one walk tick per check.
 * Engine-free, allocation free.
 */
public final class TakeoffTiming {

    /** Jumping now must beat waiting by this much (score units) to be chosen. */
    public static final double MARGIN = 0.05;

    public enum Choice { NOW, NEXT_TICK }

    private final MovementPhysics next = new MovementPhysics();
    private double nowScore;
    private double nextScore;
    private float nowDamage;
    private boolean nextSupported;

    /**
     * @param yawRateDegPerTick the user's mouse yaw rate (+ = yaw increasing); N+1 uses {@code yaw + rate}
     * @param safeOnly          SafeWalk: a takeoff that costs health does not count as landing
     */
    public Choice choose(MovementPhysics now, float yaw, double yawRateDegPerTick, boolean sprint, float health,
                         boolean safeOnly, JumpSearch search, MovementPhysics.Voxels w) {
        nowScore = search.jumpScore(now, yaw, yaw, sprint, health, w);
        nowDamage = search.landDamage();
        if (safeOnly && nowDamage > 0f) {
            nowScore = JumpSearch.NO_LANDING;
        }
        next.copyFrom(now).tick(1, 0, yaw, sprint, false, w);
        nextSupported = next.onGround && next.supported(w);
        if (!nextSupported) {
            nextScore = JumpSearch.NO_LANDING;
            return Choice.NOW;
        }
        float nextYaw = (float) (yaw + yawRateDegPerTick);
        nextScore = search.jumpScore(next, nextYaw, yaw, sprint, health, w);
        if (safeOnly && search.landDamage() > 0f) {
            nextScore = JumpSearch.NO_LANDING;
        }
        return nowScore > JumpSearch.NO_LANDING && nowScore > nextScore + MARGIN ? Choice.NOW : Choice.NEXT_TICK;
    }

    public double nowScore() {
        return nowScore;
    }

    public double nextScore() {
        return nextScore;
    }

    /** Whether the body was still supported one tick on (N+1 was an option). */
    public boolean nextSupported() {
        return nextSupported;
    }
}
