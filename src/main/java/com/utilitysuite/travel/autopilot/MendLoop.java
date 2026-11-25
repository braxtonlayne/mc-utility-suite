package com.utilitysuite.travel.autopilot;

/**
 * "Land to mend", engine-free: after a reserve landing with a Mending elytra worn and Bottles o' Enchanting in the
 * hotbar, look down at the feet (the core smooths the pitch), throw bottles one at a time until the elytra is repaired
 * or the bottles run out, wait for the last orbs, then take off again (or give up when the elytra is still near the
 * reserve). No allocation.
 */
final class MendLoop {

    /** Camera pitch (degrees, down positive) to throw bottles at. */
    static final double THROW_PITCH = 88.0;
    /** Pitch that counts as "looking at the feet". */
    static final double READY_PITCH = 80.0;
    static final int THROW_INTERVAL = 4;
    /** Ticks to wait for the orbs of the last bottles. */
    static final int ABSORB_TICKS = 60;
    /** Stops throwing when nothing was repaired for this long. */
    static final int STALL_TICKS = 200;

    enum Phase { IDLE, LANDING, AIM_DOWN, THROW, ABSORB, DONE }

    enum Action { NONE, AIM_DOWN, SELECT_BOTTLE, THROW, TAKE_OFF, GIVE_UP }

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int cooldown;
    private int bestUses;
    private int stall;

    /** Land to mend: the setting is on, the worn elytra has Mending and bottles are carried. */
    static boolean shouldLandToMend(boolean enabled, boolean wornHasMending, int bottles) {
        return enabled && wornHasMending && bottles > 0;
    }

    /** Repaired: full durability. */
    static boolean repaired(int usesLeft, int maxUses) {
        return usesLeft >= maxUses - 1;
    }

    void begin() {
        to(Phase.LANDING);
        cooldown = 0;
        bestUses = -1;
        stall = 0;
    }

    void reset() {
        to(Phase.IDLE);
    }

    /** A mend is pending or in progress. */
    boolean active() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    Phase phase() {
        return phase;
    }

    /**
     * One tick.
     *
     * @param landed         standing on the ground, elytra closed
     * @param pitch          current camera pitch
     * @param usesLeft       uses left on the worn elytra
     * @param maxUses        its maximum uses
     * @param minDurability  the reserve: taking off again after running out of bottles needs a margin above it
     * @param bottles        bottles in the hotbar
     * @param bottleSelected the selected hotbar slot holds bottles
     */
    Action step(boolean landed, double pitch, int usesLeft, int maxUses, int minDurability, int bottles,
                boolean bottleSelected) {
        phaseTicks++;
        if (cooldown > 0) {
            cooldown--;
        }
        if (usesLeft > bestUses) {
            bestUses = usesLeft;
            stall = 0;
        } else {
            stall++;
        }
        switch (phase) {
            case LANDING -> {
                if (landed) {
                    to(Phase.AIM_DOWN);
                    return Action.AIM_DOWN;
                }
                return Action.NONE;
            }
            case AIM_DOWN -> {
                if (pitch >= READY_PITCH) {
                    to(Phase.THROW);
                }
                return Action.AIM_DOWN;
            }
            case THROW -> {
                if (repaired(usesLeft, maxUses)) {
                    to(Phase.DONE);
                    return Action.TAKE_OFF;
                }
                if (bottles <= 0 || stall > STALL_TICKS) {
                    to(Phase.ABSORB);
                    return Action.AIM_DOWN;
                }
                if (pitch < READY_PITCH) {
                    to(Phase.AIM_DOWN); // the camera moved: look down again first
                    return Action.AIM_DOWN;
                }
                if (!bottleSelected) {
                    return Action.SELECT_BOTTLE;
                }
                if (cooldown == 0) {
                    cooldown = THROW_INTERVAL;
                    return Action.THROW;
                }
                return Action.AIM_DOWN;
            }
            case ABSORB -> {
                if (repaired(usesLeft, maxUses)) {
                    to(Phase.DONE);
                    return Action.TAKE_OFF;
                }
                if (phaseTicks < ABSORB_TICKS) {
                    return Action.AIM_DOWN;
                }
                to(Phase.DONE);
                return usesLeft > minDurability + BackupElytraSwap.SWAP_MARGIN ? Action.TAKE_OFF : Action.GIVE_UP;
            }
            default -> {
                return Action.NONE;
            }
        }
    }

    private void to(Phase p) {
        phase = p;
        phaseTicks = 0;
    }
}
