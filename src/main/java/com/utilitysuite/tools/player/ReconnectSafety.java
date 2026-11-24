package com.utilitysuite.tools.player;

/**
 * Whether it is safe to resume the remembered job after a reconnect. Engine-free: the module passes plain facts.
 * Returns a short reason when it is not safe, or null. No allocation.
 */
public final class ReconnectSafety {

    /** Ticks after the last hit taken during which the player counts as under attack. */
    public static final int RECENT_HURT_TICKS = 100;

    private ReconnectSafety() {
    }

    /**
     * @param inLava          the player is in lava
     * @param falling         airborne and dropping (not swimming, climbing or flying)
     * @param ticksSinceHurt  ticks since the player last took damage from an attacker
     * @param dangerLive      the danger model holds a live hazard (projectile, explosion, melee, fire, ...)
     */
    public static String unsafeReason(boolean inLava, boolean falling, int ticksSinceHurt, boolean dangerLive) {
        if (inLava) {
            return "in lava";
        }
        if (falling) {
            return "falling";
        }
        if (ticksSinceHurt < RECENT_HURT_TICKS) {
            return "under attack";
        }
        if (dangerLive) {
            return "in danger";
        }
        return null;
    }
}
