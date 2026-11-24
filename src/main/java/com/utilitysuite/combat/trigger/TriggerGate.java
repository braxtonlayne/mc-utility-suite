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

    /** Longest run of ticks a swing is held back waiting for a falling crit; then it swings as it is. */
    public static final int MAX_CRIT_DEFER = 8;

    private int targetId = -1;
    private int delayLeft = -1;
    private int critDeferred;

    /** True when every condition for a swing holds at this instant (no delay). */
    public static boolean valid(int crosshairTargetId, boolean livingAlive, boolean allowedKind, boolean ally,
                                boolean inReach, float attackStrength, boolean screenOpen, boolean usingItem,
                                boolean manualAttack) {
        return valid(crosshairTargetId, livingAlive, allowedKind, ally, inReach, attackStrength, screenOpen, usingItem,
                manualAttack, false);
    }

    /** As above; {@code critHold} (from {@link #critHold}) defers the swing while a falling crit is still coming. */
    public static boolean valid(int crosshairTargetId, boolean livingAlive, boolean allowedKind, boolean ally,
                                boolean inReach, float attackStrength, boolean screenOpen, boolean usingItem,
                                boolean manualAttack, boolean critHold) {
        return !critHold && crosshairTargetId >= 0 && livingAlive && allowedKind && !ally && inReach && attackStrength >= READY
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

    /**
     * Wait-for-falling-crit rule: true while the swing should be held back. Holds only when the option is on, a crit
     * is possible at all (not in water, on a ladder, riding or blind), the player is off the ground and has not yet
     * started to fall (vy >= 0 or no fall distance), and the target is not near the edge of reach. The wait is
     * capped at {@value #MAX_CRIT_DEFER} ticks per airtime, so a target leaving reach is never missed; the count
     * restarts on landing. Call once per tick while enabled.
     */
    public boolean critHold(boolean enabled, boolean onGround, boolean canCrit, double vy, float fallDistance,
                            boolean nearReachEdge) {
        if (!enabled || onGround || !canCrit) {
            critDeferred = 0;
            return false;
        }
        if (vy < 0.0 && fallDistance > 0f) {
            return false; // falling: the crit is live now
        }
        if (nearReachEdge || critDeferred >= MAX_CRIT_DEFER) {
            return false;
        }
        critDeferred++;
        return true;
    }

    /** True when the next valid tick starts a new reaction delay (the caller then supplies one). */
    public boolean needsDelay(int crosshairTargetId) {
        return crosshairTargetId != targetId || delayLeft < 0;
    }

    public void reset() {
        targetId = -1;
        delayLeft = -1;
        critDeferred = 0;
    }
}
