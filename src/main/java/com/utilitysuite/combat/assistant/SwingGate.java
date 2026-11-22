package com.utilitysuite.combat.assistant;

/**
 * Cooldown and critical-hit timing. A swing is only allowed at full charge
 * ({@code getAttackStrengthScale(0.5f) >= 0.95}). With crit sync on, it hops early (from 60% charge) so the swing can
 * land while falling, and falls back to a plain swing if no fall happens within {@code maxCritWait} ticks.
 * Pure and allocation free; shared by the assistant and the coach. The assistant's swings and hops now follow
 * {@code combat.tactics.HitPlanner} (which reuses {@link #READY} and {@link #HURT_HOLD}); {@link #decide} is kept for
 * its tests and simple callers.
 */
public final class SwingGate {

    public enum Action { NONE, SWING, JUMP }

    public static final float READY = 0.95f;
    public static final float PRE_JUMP = 0.6f;

    /**
     * Withhold a swing while the target's client hurt time is above this. Vanilla gives 10 ticks of damage immunity
     * (equal-or-weaker hits are ignored) after a hit, and the client's hurtTime counts 10 down to 0. A swing sent now
     * is processed by the server about one to two ticks later (input to packet, network, server tick), so a swing
     * chosen at hurtTime 2 arrives as the immunity ends. The hold lasts at most hurtTime - HURT_HOLD ticks (8 at
     * most), never longer than the immunity left, and a hurt time of 0 behaves exactly as before.
     */
    public static final int HURT_HOLD = 2;

    private SwingGate() {
    }

    public static boolean ready(float attackStrength) {
        return attackStrength >= READY;
    }

    /**
     * @param jumpedThisCycle a hop was already made since the last swing
     * @param critWaitTicks   ticks spent at full charge waiting for a fall
     */
    public static Action decide(CombatSnapshot s, boolean critSync, boolean jumpedThisCycle, int critWaitTicks,
                                int maxCritWait) {
        if (!s.inReach() || !s.crosshairOnTarget) {
            return Action.NONE;
        }
        boolean ready = ready(s.attackStrength);
        boolean hurtHold = s.targetHurtTime > HURT_HOLD;
        if (!critSync) {
            return ready && !hurtHold ? Action.SWING : Action.NONE;
        }
        if (ready && !hurtHold && s.fallingForCrit()) {
            return Action.SWING;
        }
        if (s.onGround && !jumpedThisCycle && s.attackStrength >= PRE_JUMP && !s.inWater && !s.climbing) {
            return Action.JUMP;
        }
        if (ready && !hurtHold && critWaitTicks >= maxCritWait) {
            return Action.SWING;
        }
        return Action.NONE;
    }
}
