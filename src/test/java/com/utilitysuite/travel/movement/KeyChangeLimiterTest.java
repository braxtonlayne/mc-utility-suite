package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static com.utilitysuite.travel.movement.LandingHold.FORWARD;
import static com.utilitysuite.travel.movement.LandingHold.LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key-change limits of a jump, in the two places that apply them: the commitment (what the assist presses) and
 * the technique roller (what a script is planned with). Both run {@link KeyChangeLimiter}.
 */
class KeyChangeLimiterTest {

    private static final int KEEP = KeyChangeLimiter.KEEP;

    // ---- the commitment ---------------------------------------------------------------------------------------------

    private static void ground(JumpCommit c, int keys) {
        c.tick(true, false, false, keys, false, 0f, 0, true, false, false, 1, false);
    }

    private static void takeoff(JumpCommit c, int keys) {
        c.tick(false, true, false, keys, false, 0f, 0, true, false, false, 1, false);
    }

    private static void air(JumpCommit c, int keys) {
        c.tick(false, false, false, keys, false, 0f, 0, true, false, false, 1, false);
    }

    private static JumpCommit armed(int reaction, int hold) {
        JumpCommit c = new JumpCommit();
        c.mode = JumpCommit.Mode.CARRY;
        c.reactionTicks = reaction;
        c.minChangeHoldTicks = hold;
        ground(c, FORWARD);
        assertEquals(JumpCommit.Phase.ARMED, c.phase());
        return c;
    }

    @Test
    void theReactionDelayCountsFromTheFirstAirTick() {
        JumpCommit c = armed(3, 1);
        takeoff(c, FORWARD);
        for (int airTick = 0; airTick < 6; airTick++) {
            if (airTick > 0) {
                air(c, FORWARD);
            }
            c.limit(KEEP, 1, 1, 0);
            assertEquals(airTick < 3 ? KEEP : 1, c.limitedStrafe(), "air tick " + airTick);
        }
        assertEquals(1, c.keyChanges());
    }

    @Test
    void theReactionDelayOfAJumpIsFixedAtItsTakeoff() {
        JumpCommit c = armed(2, 1);
        takeoff(c, FORWARD);
        c.reactionTicks = 4; // the level's value for the next jump
        air(c, FORWARD);
        air(c, FORWARD);
        c.limit(KEEP, 1, 1, 0);
        assertEquals(1, c.limitedStrafe(), "air tick 2 of a jump that took off with a delay of 2");
    }

    @Test
    void aKeyHeldOnTheGroundThroughTheTakeoffIsNoChange() {
        JumpCommit c = armed(3, 3);
        c.noteGroundKeys(KEEP, 1); // a script's run-up strafe, two ground ticks
        ground(c, FORWARD);
        c.noteGroundKeys(KEEP, 1);
        takeoff(c, FORWARD);
        c.limit(KEEP, 1, 1, 0);
        assertEquals(1, c.limitedStrafe(), "it goes on through the reaction delay");
        assertEquals(0, c.keyChanges());
        air(c, FORWARD);
        c.limit(0, 1, 1, 0);
        assertEquals(KEEP, c.limitedForward(), "a new key is still held back by the delay");
        assertEquals(1, c.limitedStrafe(), "and the held one stays");
        air(c, FORWARD);
        air(c, FORWARD);
        c.limit(0, 1, 1, 0);
        assertEquals(0, c.limitedForward(), "after the delay");
        assertEquals(1, c.keyChanges());
    }

    @Test
    void aGroundKeyLetGoBeforeTheTakeoffIsNotCarried() {
        JumpCommit c = armed(3, 1);
        c.noteGroundKeys(KEEP, 1);
        ground(c, FORWARD);
        ground(c, FORWARD); // nothing noted on this tick
        takeoff(c, FORWARD);
        c.limit(KEEP, 1, 1, 0);
        assertEquals(KEEP, c.limitedStrafe(), "a new key inside the reaction delay");
    }

    @Test
    void theUsersTapOnTheChosenKeyDoesNotEndTheChoice() {
        JumpCommit c = armed(0, 3);
        takeoff(c, FORWARD);
        c.limit(KEEP, 1, 1, 0);
        assertEquals(1, c.limitedStrafe());
        air(c, FORWARD | LEFT);
        c.limit(KEEP, KEEP, 1, 1); // the user holds the same key: nothing to write
        assertEquals(KEEP, c.limitedStrafe());
        air(c, FORWARD);
        c.limit(KEEP, 1, 1, 0); // the tap is over: the choice goes on, no new change
        assertEquals(1, c.limitedStrafe());
        assertEquals(1, c.keyChanges());
        air(c, FORWARD);
        c.limit(0, 1, 1, 0); // held three ticks by now: the next change may come
        assertEquals(0, c.limitedForward());
        assertEquals(2, c.keyChanges());
    }

    // ---- the roller -------------------------------------------------------------------------------------------------

    /** A run-up along +z to an edge at z 2, two air blocks, a wide pad. */
    private static ShapeGrid scene() {
        return TechniqueLibraryTest.floor().fill(-3, 19, 4, 3, 19, 9);
    }

    private static MovementPhysics start() {
        return new MovementPhysics().set(0.5, 20, -2.0, 0, -0.0784, 0, true);
    }

    /** The takeoff tick of a plain sprint jump that lands on the pad (the last one that does). */
    private static int takeoffTick(TechniqueRoller roller, ShapeGrid g, SupportRegion pad,
                                   TechniqueTemplates.Candidate c, FlightScript out) {
        for (int jump = 25; jump >= 0; jump--) {
            c.jump = jump;
            if (roller.roll(start(), 0f, true, 0, c, 0, pad, false, 0f, true, 20f, g, out)) {
                return jump;
            }
        }
        throw new AssertionError("no takeoff tick lands the pad");
    }

    @Test
    void aRollHoldsANewAirKeyBackForTheReactionDelay() {
        ShapeGrid g = scene();
        SupportRegion pad = TechniqueLibraryTest.top(g, 0, 19, 6);
        TechniqueRoller roller = new TechniqueRoller();
        TechniqueTemplates.Candidate c = new TechniqueTemplates.Candidate();
        c.reset(TechniqueTemplates.HEADHITTER, 0, 0f);
        c.segment(1, 0, Float.NaN); // strafe from the first air tick to the touchdown
        FlightScript out = new FlightScript();
        int jump = takeoffTick(roller, g, pad, c, out);
        assertEquals(1, out.strafe[jump + 1], "no limits: the key is down on the first air tick");

        roller.keyReaction = 3;
        assertTrue(roller.roll(start(), 0f, true, 0, c, 0, pad, false, 0f, true, 20f, g, out));
        for (int a = 0; a < 6; a++) {
            assertEquals(a < 3 ? 0 : 1, out.strafe[jump + 1 + a], "air tick " + a);
        }
        // and the recorded line is the line of those keys: a plain replay of the script lands where it says
        MovementPhysics p = start();
        for (int i = 0; i <= out.landIndex; i++) {
            p.tick(out.forward[i], out.strafe[i], out.yaw[i], out.sprint[i], out.jump[i], false, g);
            assertEquals(out.x[i], p.x, 1.0e-9, "x at " + i);
            assertEquals(out.z[i], p.z, 1.0e-9, "z at " + i);
        }
    }

    @Test
    void aRollCarriesTheRunUpStrafeThroughTheReactionDelay() {
        ShapeGrid g = scene();
        SupportRegion pad = TechniqueLibraryTest.top(g, 0, 19, 6);
        TechniqueRoller roller = new TechniqueRoller();
        TechniqueTemplates.Candidate c = new TechniqueTemplates.Candidate();
        c.reset(TechniqueTemplates.HEADHITTER, 0, 0f);
        c.groundStrafe = 1;
        c.groundStrafeTicks = 2;
        c.segment(1, 0, Float.NaN);
        FlightScript out = new FlightScript();
        roller.keyReaction = 3;
        roller.keyMinHold = 3;
        int jump = takeoffTick(roller, g, pad, c, out);
        for (int i = jump - 1; i <= out.landIndex; i++) {
            assertEquals(1, out.strafe[i], "tick " + i + " (takeoff " + jump + ")");
        }
    }

    @Test
    void aRollKeepsAKeyTheMinimumHoldAndStopsAtTheChangeLimit() {
        ShapeGrid g = scene();
        SupportRegion pad = TechniqueLibraryTest.top(g, 0, 19, 6);
        TechniqueRoller roller = new TechniqueRoller();
        TechniqueTemplates.Candidate c = new TechniqueTemplates.Candidate();
        c.reset(TechniqueTemplates.CORNER, 0, 0f);
        c.segment(1, 1, Float.NaN); // one tick of left, then right, then left to the touchdown
        c.segment(-1, 3, Float.NaN);
        c.segment(1, 0, Float.NaN);
        FlightScript out = new FlightScript();
        roller.keyMinHold = 3;
        roller.keyMaxChanges = 2;
        int jump = takeoffTick(roller, g, pad, c, out);
        int[] want = {1, 1, 1, -1, -1, -1, 0, 0}; // held 3, the second change held, the third refused
        for (int a = 0; a < want.length && jump + 1 + a < out.landIndex; a++) {
            assertEquals(want[a], out.strafe[jump + 1 + a], "air tick " + a);
        }
    }
}
