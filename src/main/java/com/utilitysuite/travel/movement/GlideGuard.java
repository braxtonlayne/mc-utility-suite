package com.utilitysuite.travel.movement;

/**
 * While gliding with an elytra: is the glide about to hit the ground hard? Elytra impact damage is the speed lost in
 * the hit times 10, less 3; a steep glide into the ground also keeps the fall distance. When the ground is close in
 * time and the speed would hurt, the answer is a pitch-up target (applied through the capped smoother by the caller).
 * Engine free.
 */
public final class GlideGuard {

    /** Look ahead in ticks: pull up when the ground is closer than this. */
    public static final int WARN_TICKS = 20;
    /** Pitch (degrees, negative = up) the pull-up aims at. */
    public static final float PULL_UP_PITCH = -25f;

    private GlideGuard() {
    }

    /** Damage of hitting something at {@code speed} blocks per tick and stopping. */
    public static float impactDamage(double speed) {
        return (float) Math.max(0.0, speed * 10.0 - 3.0);
    }

    /**
     * Ticks until the ground at the current sink rate, or {@code Integer.MAX_VALUE} when not sinking.
     *
     * @param heightAboveGround blocks between the feet and the ground below the glide path
     * @param verticalSpeed     blocks per tick (negative = down)
     */
    public static int ticksToGround(double heightAboveGround, double verticalSpeed) {
        if (verticalSpeed >= -1.0e-3) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(Math.max(0.0, heightAboveGround) / -verticalSpeed);
    }

    /** True when the glide should pull up: the ground is near in time and the impact would cost at least minDamage. */
    public static boolean shouldPullUp(double heightAboveGround, double verticalSpeed, double speed, float minDamage) {
        return ticksToGround(heightAboveGround, verticalSpeed) <= WARN_TICKS && impactDamage(speed) >= minDamage;
    }
}
