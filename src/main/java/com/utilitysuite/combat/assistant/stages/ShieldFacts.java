package com.utilitysuite.combat.assistant.stages;

/**
 * World facts the shield duel needs beyond the combat snapshot. The Minecraft implementation
 * ({@code combat.assistant.mc.McShieldFacts}) reads the local player and the target once per {@link #refresh};
 * tests use a plain fake. Client thread only, allocation free.
 */
public interface ShieldFacts {

    /** Reads this tick's facts about the local player and the target (one entity lookup). */
    void refresh(int targetId);

    /** The target was found by the last {@link #refresh}. When false the target getters return their defaults. */
    boolean targetKnown();

    // ---- own shield -----------------------------------------------------------------------------------------------

    /**
     * A shield sits in our offhand, is not on cooldown, and the main hand has no use action of its own (so USE raises
     * the shield, not food or a bow).
     */
    boolean ownShieldReady();

    /** Ticks our shield has been raised ({@code getTicksUsingItem} while using it); 0 = down. */
    int ownShieldUseTicks();

    // ---- target ---------------------------------------------------------------------------------------------------

    /** The target's head yaw (the yaw {@code isDamageSourceBlocked} uses); NaN when unknown. */
    float targetHeadYaw();

    /** Ticks the target has been using a shield (0 = not raised); it blocks once this reaches 5. */
    int targetShieldUseTicks();

    /** The target's main hand disables shields (26.3: a {@code WEAPON} component with disable_blocking_for_seconds > 0, e.g. an axe). */
    boolean targetHoldsAxe();

    /**
     * Ticks left of the target's shield disable when it is observable (the shield-break sound vanilla plays for
     * {@code disableShield}'s entity event 30), 0 when an observed disable ran out; -1 when none was observed.
     */
    int targetShieldDisabledTicks();

    /** No facts (stage registered without a source): every getter reports "unknown / none". */
    ShieldFacts NONE = new ShieldFacts() {
        @Override
        public void refresh(int targetId) {
        }

        @Override
        public boolean targetKnown() {
            return false;
        }

        @Override
        public boolean ownShieldReady() {
            return false;
        }

        @Override
        public int ownShieldUseTicks() {
            return 0;
        }

        @Override
        public float targetHeadYaw() {
            return Float.NaN;
        }

        @Override
        public int targetShieldUseTicks() {
            return 0;
        }

        @Override
        public boolean targetHoldsAxe() {
            return false;
        }

        @Override
        public int targetShieldDisabledTicks() {
            return -1;
        }
    };
}
