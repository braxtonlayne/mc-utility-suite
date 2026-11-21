package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.LegitPresets;

/**
 * The gate for the less-legit movement modules (Fly, Velocity scale), engine free. Both need the Relaxed tuning
 * selected; Fly also runs where the server already grants flight, Velocity scale only in single-player or on an
 * allow-listed server. Returns null when allowed, else a one-line reason (constant strings).
 */
public final class RelaxedGate {

    public static final String NEED_RELAXED = "select the Relaxed tuning first";
    public static final String NEED_ALLOWED = "only in single-player or on an allow-listed server";
    public static final String NEED_FLIGHT = "only where flight is allowed (single-player, allow-listed, or granted)";

    private RelaxedGate() {
    }

    public static String flyRefusal(boolean singleplayer, boolean allowListed, boolean mayFly,
                                    LegitPresets.Level level) {
        if (level != LegitPresets.Level.RELAXED) {
            return NEED_RELAXED;
        }
        return singleplayer || allowListed || mayFly ? null : NEED_FLIGHT;
    }

    public static String velocityRefusal(boolean singleplayer, boolean allowListed, LegitPresets.Level level) {
        if (level != LegitPresets.Level.RELAXED) {
            return NEED_RELAXED;
        }
        return singleplayer || allowListed ? null : NEED_ALLOWED;
    }

    /** Knockback velocity scaled per axis; vertical scaled separately (0 = no lift). */
    public static double scale(double component, double factor) {
        return component * Math.max(0.0, Math.min(1.0, factor));
    }
}
