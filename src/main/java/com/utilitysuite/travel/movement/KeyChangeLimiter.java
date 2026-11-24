package com.utilitysuite.travel.movement;

/**
 * The per-level key-change limits of one jump (LegitPresets, design 2026-11-22, 3.1): at most {@link #maxKeyChanges}
 * departures from the user's keys, each kept a minimum number of ticks, no new one inside the reaction delay after
 * the takeoff. One state machine for the two places that must agree: {@link JumpCommit#limit} applies it to what the
 * assist presses, {@link TechniqueRoller} applies it while planning, so a script is rolled with the keys the replay
 * will really deliver.
 * <p>
 * A departure the assist already held on the ground through the takeoff ({@link #start}) is no change: it goes on
 * through the reaction delay (releasing a held key for two ticks and pressing it again is the opposite of a human
 * hand). Allocation free.
 */
final class KeyChangeLimiter {

    static final int KEEP = MovementAssistBrain.KEEP;

    /** {@link #decide}: the pair goes out as asked. */
    static final int PASS = 0;
    /** {@link #decide}: the earlier choice ({@link #choiceF}, {@link #choiceS}) stays; the caller applies it. */
    static final int HOLD = 1;
    /** {@link #decide}: nothing goes out (the user's keys stand). */
    static final int NONE = 2;

    /** Departures per jump, 0 = unlimited. */
    int maxKeyChanges;

    int changes;
    int choiceF = KEEP;
    int choiceS = KEEP;
    int choiceAge;

    /**
     * A new jump.
     *
     * @param heldF   the departure on the forward axis the assist held on the last ground tick ({@link #KEEP}: none)
     * @param heldS   the same for the strafe axis
     * @param heldAge ticks it was held on the ground (0: nothing held)
     */
    void start(int heldF, int heldS, int heldAge) {
        changes = 0;
        if (heldAge > 0 && (heldF != KEEP || heldS != KEEP)) {
            changes = 1; // no reaction, but one of the jump's key changes all the same
            choiceF = heldF;
            choiceS = heldS;
            choiceAge = heldAge;
        } else {
            choiceF = KEEP;
            choiceS = KEEP;
            choiceAge = 0;
        }
    }

    /**
     * What happens to the wanted pair this tick (each {@link #KEEP} or a value). Follow with {@link #settle} of the
     * pair that really goes out.
     *
     * @param minHold    ticks a choice is kept at least
     * @param inReaction inside the reaction delay after the takeoff
     */
    int decide(int f, int s, int minHold, boolean inReaction) {
        boolean has = choiceF != KEEP || choiceS != KEEP;
        if (f == KEEP && s == KEEP) {
            return has && choiceAge < minHold ? HOLD : PASS;
        }
        if (f == choiceF && s == choiceS) {
            return PASS; // the choice goes on: no change
        }
        if (inReaction) {
            return has ? HOLD : NONE; // the reaction delay of the level: nothing new this early in the flight
        }
        if (has && choiceAge < minHold) {
            return HOLD;
        }
        if (maxKeyChanges > 0 && changes >= maxKeyChanges) {
            return NONE;
        }
        changes++;
        choiceAge = 0;
        return PASS;
    }

    /**
     * The wanted pair only presses a strafe key (nothing held is let go or swapped, forward stays as it is). On the
     * first air tick of a jump planned on the ground, that key goes down together with the jump key, one tick apart at
     * most: the way a player takes a corner, planned with the takeoff, so the reaction delay does not hold it back
     * (decision of 2026-11-24). It is still one of the jump's key changes and is held the minimum time; every later
     * change waits out the delay.
     */
    boolean withTheJump(int f, int s) {
        return f == choiceF && choiceS == KEEP && s != KEEP && s != 0;
    }

    /** The pair that went out this tick. */
    void settle(int f, int s) {
        if (f != KEEP || s != KEEP) {
            choiceF = f;
            choiceS = s;
            choiceAge++;
        } else {
            choiceF = KEEP;
            choiceS = KEEP;
            choiceAge = 0;
        }
    }
}
