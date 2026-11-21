package com.utilitysuite.tools.player;

/**
 * Engine-free offhand rules, evaluated in priority order: (1) totem at or below the health threshold, (2) totem while
 * gliding, (3) shield while a ranged threat is near or a projectile is incoming, (4) the user's preferred item. A rule
 * whose item is not in the inventory falls through to the next one. No allocation: the caller fills a reusable
 * {@link State} and reads the returned enum.
 */
public final class OffhandRules {

    /** What should be in the offhand. {@link #KEEP} = leave it alone. */
    public enum Want { KEEP, TOTEM, SHIELD, PREFERRED }

    /** Inputs, filled by the module each evaluation (reused, never reallocated). */
    public static final class State {
        public float health;
        public boolean gliding;
        public boolean rangedThreatNear;
        public boolean projectileDanger;
        /** What the offhand holds now. */
        public Want offhandHolds = Want.KEEP;
        /** Inventory (not offhand) has one of these. */
        public boolean haveTotem;
        public boolean haveShield;
        public boolean havePreferred;
        // blockers
        public boolean screenOpen;
        public boolean usingItem;
        public boolean clickPending;
        public int cooldownTicks;
    }

    /** Settings. */
    public static final class Config {
        public boolean totemOnLowHealth = true;
        public float totemHealth = 10f;
        public boolean totemWhileGliding = true;
        public boolean shieldVsRanged = true;
        public boolean preferredEnabled = true;
        /** Autopilot fallback: only the totem rules count. */
        public boolean totemRulesOnly;
    }

    private OffhandRules() {
    }

    /** The highest-priority rule that applies and whose item is available (or already in the offhand). */
    public static Want desired(State s, Config c) {
        if (c.totemOnLowHealth && s.health <= c.totemHealth && available(Want.TOTEM, s)) {
            return Want.TOTEM;
        }
        if (c.totemWhileGliding && s.gliding && available(Want.TOTEM, s)) {
            return Want.TOTEM;
        }
        if (c.totemRulesOnly) {
            return Want.KEEP;
        }
        if (c.shieldVsRanged && (s.rangedThreatNear || s.projectileDanger) && available(Want.SHIELD, s)) {
            return Want.SHIELD;
        }
        if (c.preferredEnabled && available(Want.PREFERRED, s)) {
            return Want.PREFERRED;
        }
        return Want.KEEP;
    }

    /**
     * The swap to perform now, or {@link Want#KEEP} when nothing should happen (already right, blocked by a screen, the
     * user using an item, a pending click or the cooldown).
     */
    public static Want decide(State s, Config c) {
        if (s.screenOpen || s.usingItem || s.clickPending || s.cooldownTicks > 0) {
            return Want.KEEP;
        }
        Want w = desired(s, c);
        if (w == Want.KEEP || w == s.offhandHolds) {
            return Want.KEEP;
        }
        // Never trade a totem away for a lower rule while health is at or below the threshold.
        if (s.offhandHolds == Want.TOTEM && s.health <= c.totemHealth && c.totemOnLowHealth) {
            return Want.KEEP;
        }
        return w;
    }

    private static boolean available(Want w, State s) {
        if (s.offhandHolds == w) {
            return true;
        }
        return switch (w) {
            case TOTEM -> s.haveTotem;
            case SHIELD -> s.haveShield;
            case PREFERRED -> s.havePreferred;
            case KEEP -> false;
        };
    }
}
