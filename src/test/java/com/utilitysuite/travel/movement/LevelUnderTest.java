package com.utilitysuite.travel.movement;

/**
 * The legit level the loop harnesses run at (BALANCED unless a test switches it, always restored in a finally). The
 * numbers are the movement_assist knobs of {@code LegitPresets}: key changes per jump, hold ticks, reaction ticks and
 * whether the level has yaw co-steer at all (LEGIT has none: keys only).
 * <p>
 * Also counts, over the harness loops, the air ticks a technique script was replayed on and how many of those the
 * brain let out other keys than the script holds (the commitment's key limits changed them): a script is planned
 * under those limits, so that count stays 0.
 */
final class LevelUnderTest {
    static int keyChanges = 6;
    static int holdTicks = 2;
    static int reactionTicks = 0;
    static boolean coSteer = true;

    /** Air ticks a script was replayed on (up to the user's first strafe key of the run, if any). */
    static int scriptAirTicks;
    /** Of those: ticks the applied keys were not the script's. */
    static int scriptKeyMismatches;

    private LevelUnderTest() {
    }

    static void balanced() {
        set(6, 2, 0, true);
    }

    /**
     * LEGIT as the module runs it: 3 changes, 3 hold ticks, no co-steer, and a reaction delay of 2 to 4 ticks (the
     * brain varies it around the knob's 3 from jump to jump; a fresh brain's first jump flies the value given here).
     */
    static void legit(int reaction) {
        set(3, 3, reaction, false);
    }

    static void set(int changes, int hold, int reaction, boolean steer) {
        keyChanges = changes;
        holdTicks = hold;
        reactionTicks = reaction;
        coSteer = steer;
        scriptAirTicks = 0;
        scriptKeyMismatches = 0;
    }

    private static boolean userTapped;

    /** At the start of a harness loop: the level's limits onto a fresh brain. */
    static void apply(MovementAssistBrain b) {
        b.setKeyChangeLimits(keyChanges, holdTicks, reactionTicks);
        userTapped = false;
    }

    /** The level's co-steer rate: the harness's BALANCED rate, or none. */
    static float steerRate(float balancedRate) {
        return coSteer ? balancedRate : 0f;
    }

    /** After each {@code decide} of a harness loop, with the user's own axes of that tick. */
    static void afterDecide(MovementAssistBrain b, boolean onGround, int userForward, int userStrafe) {
        userTapped |= userStrafe != 0;
        if (onGround || !b.scriptActive() || userTapped) {
            return; // (a strafe key of the user's changes what goes out from then on: manual input, not the limits)
        }
        FlightScript sc = b.activeScript();
        int i = b.scriptIndex();
        int f = b.forward() == MovementAssistBrain.KEEP ? userForward : b.forward();
        int s = b.strafe() == MovementAssistBrain.KEEP ? userStrafe : b.strafe();
        scriptAirTicks++;
        if (f != sc.forward[i] || s != sc.strafe[i]) {
            scriptKeyMismatches++;
        }
    }
}
