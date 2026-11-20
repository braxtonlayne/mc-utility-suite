package com.utilitysuite.combat.assistant;

/**
 * Optional 1.21 aerial weapons. {@link #shouldSwapToMace} swaps to a hotbar mace for a swing made while falling far
 * enough for a smash. The wind-charge sequence (look down, throw a wind charge at the feet while hopping, then let the
 * mace swing land on the way down) is a small state machine. Allocation free.
 */
public final class AerialPlanner {

    public enum Action { NONE, AIM_DOWN, THROW, RESTORE }

    private enum Phase { IDLE, AIMING, LAUNCHED }

    public static final float MIN_SMASH_FALL = 1.5f;
    static final int AIM_TIMEOUT = 10;
    static final int LAUNCH_COOLDOWN = 60;
    static final float AIM_DOWN_PITCH = 80f;

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int cooldown;

    public static boolean shouldSwapToMace(CombatSnapshot s, boolean enabled) {
        return enabled && s.maceSlot >= 0 && s.maceSlot != s.selectedSlot && !s.onGround && !s.inWater
                && s.fallDistance >= MIN_SMASH_FALL;
    }

    /** True while the wind-charge sequence owns the camera and the hotbar. */
    public boolean active() {
        return phase != Phase.IDLE;
    }

    public Action step(CombatSnapshot s, boolean enabled) {
        if (cooldown > 0) {
            cooldown--;
        }
        switch (phase) {
            case IDLE:
                if (enabled && cooldown == 0 && s.windChargeSlot >= 0 && s.maceSlot >= 0 && !s.windChargeOnCooldown
                        && s.onGround && !s.inWater && SwingGate.ready(s.attackStrength)
                        && s.horizontalDistance() <= 3.5) {
                    phase = Phase.AIMING;
                    phaseTicks = 0;
                    return Action.AIM_DOWN;
                }
                return Action.NONE;
            case AIMING:
                if (++phaseTicks > AIM_TIMEOUT || !s.onGround) {
                    phase = Phase.IDLE;
                    cooldown = LAUNCH_COOLDOWN;
                    return Action.RESTORE;
                }
                if (s.pitch >= AIM_DOWN_PITCH) {
                    phase = Phase.LAUNCHED;
                    phaseTicks = 0;
                    return Action.THROW;
                }
                return Action.AIM_DOWN;
            case LAUNCHED:
            default:
                phase = Phase.IDLE;
                cooldown = LAUNCH_COOLDOWN;
                return Action.RESTORE;
        }
    }

    public void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
    }
}
