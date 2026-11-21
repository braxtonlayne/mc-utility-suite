package com.utilitysuite.world.filters;

/**
 * Flags the render-filter mixins read (client thread, every frame or particle, so plain volatile fields and no logic
 * beyond a compare). Set only by {@link RenderFiltersModule}; all default off, so the mixins are pass-through.
 */
public final class RenderFilterHooks {

    /** No-override sentinel for the time of day. */
    public static final long NO_TIME = Long.MIN_VALUE;

    public static volatile boolean hideHurtTilt;
    public static volatile boolean hideWeather;
    public static volatile boolean hideItemActivation;
    public static volatile boolean hideExplosionParticles;
    /** Day time shown instead of the server's (ticks, 0..23999), or {@link #NO_TIME}. */
    public static volatile long dayTime = NO_TIME;
    /** Rain level drawn instead of the real one (0..1), or NaN for no override. */
    public static volatile float rainLevel = Float.NaN;
    /** Thunder level drawn instead of the real one (0..1), or NaN for no override. */
    public static volatile float thunderLevel = Float.NaN;

    private RenderFilterHooks() {
    }

    public static void resetAll() {
        hideHurtTilt = false;
        hideWeather = false;
        hideItemActivation = false;
        hideExplosionParticles = false;
        dayTime = NO_TIME;
        rainLevel = Float.NaN;
        thunderLevel = Float.NaN;
    }

    /**
     * Day time to show: the fixed override when set, otherwise the real value. Keeps the real day count so the moon
     * phase stays the server's.
     */
    public static long displayedDayTime(long real, long override) {
        if (override == NO_TIME) {
            return real;
        }
        long day = Math.floorDiv(real, 24000L);
        return day * 24000L + Math.floorMod(override, 24000L);
    }
}
