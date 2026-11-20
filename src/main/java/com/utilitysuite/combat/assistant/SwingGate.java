package com.utilitysuite.combat.assistant;

/**
 * Cooldown and critical-hit timing. A swing is only allowed at full charge
 * ({@code getAttackStrengthScale(0.5f) >= 0.95}). With crit sync on, it hops early (from 60% charge) so the swing can
 * land while falling, and falls back to a plain swing if no fall happens within {@code maxCritWait} ticks.
 * Pure and allocation free; shared by the assistant and the coach.
 */
public final class SwingGate {

    public enum Action { NONE, SWING, JUMP }

    public static final float READY = 0.95f;
    public static final float PRE_JUMP = 0.6f;

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
        if (!critSync) {
            return ready ? Action.SWING : Action.NONE;
        }
        if (ready && s.fallingForCrit()) {
            return Action.SWING;
        }
        if (s.onGround && !jumpedThisCycle && s.attackStrength >= PRE_JUMP && !s.inWater && !s.climbing) {
            return Action.JUMP;
        }
        if (ready && critWaitTicks >= maxCritWait) {
            return Action.SWING;
        }
        return Action.NONE;
    }
}
