package com.utilitysuite.combat.trigger;

/**
 * Decision of the trigger assist, pure and allocation free: swing only when the player's own crosshair is already on
 * a valid living target in reach and the attack is fully charged. It never aims and never turns the camera. A short
 * reaction delay (from the humanizer) runs between first seeing the target and the swing; it restarts when the target
 * changes. Manual clicks always win.
 */
public final class TriggerGate {

    /** Full charge, the same threshold as the assistant's swing gate. */
    public static final float READY = 0.95f;

    private int targetId = -1;
    private int delayLeft = -1;

    /** True when every condition for a swing holds at this instant (no delay). */
    public static boolean valid(int crosshairTargetId, boolean livingAlive, boolean allowedKind, boolean ally,
                                boolean inReach, float attackStrength, boolean screenOpen, boolean usingItem,
                                boolean manualAttack) {
        return crosshairTargetId >= 0 && livingAlive && allowedKind && !ally && inReach && attackStrength >= READY
                && !screenOpen && !usingItem && !manualAttack;
    }

    /**
     * Advances one tick.
     *
     * @param valid         result of {@link #valid} for {@code crosshairTargetId}
     * @param reactionTicks delay to use when a new target appears (asked lazily by the caller only when needed)
     * @return true on the tick the swing should be made
     */
    public boolean tick(int crosshairTargetId, boolean valid, int reactionTicks) {
        if (!valid) {
            if (crosshairTargetId != targetId) {
                targetId = -1;
                delayLeft = -1;
            }
            return false;
        }
        if (crosshairTargetId != targetId || delayLeft < 0) {
            targetId = crosshairTargetId;
            delayLeft = Math.max(0, reactionTicks);
        }
        if (delayLeft > 0) {
            delayLeft--;
            return false;
        }
        delayLeft = -1; // next swing waits for a fresh delay after the cooldown refills
        return true;
    }

    /** True when the next valid tick starts a new reaction delay (the caller then supplies one). */
    public boolean needsDelay(int crosshairTargetId) {
        return crosshairTargetId != targetId || delayLeft < 0;
    }

    public void reset() {
        targetId = -1;
        delayLeft = -1;
    }
}
