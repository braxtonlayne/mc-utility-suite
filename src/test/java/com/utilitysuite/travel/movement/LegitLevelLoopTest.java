package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop harnesses (lanes, the course neo, the lone fence post) at the key-change limits of the LEGIT level:
 * at most 3 changes, each held 3 ticks, the first after a reaction delay of 2 to 4 ticks.
 * <p>
 * Review finding of 2026-11-24: the technique planner rolled its scripts without those limits and the replay applied
 * them, so a script that changed a key in its first air ticks had that key dropped for the reaction delay and left its
 * line. Measured before the fix with the LEGIT limits and co-steer on (reaction 3): the course neo 8 of 60 (60 of 60
 * at BALANCED), the lanes 27 of 40 (40 of 40). The planner now rolls under the same limiter the replay applies
 * ({@link KeyChangeLimiter}).
 * <p>
 * Decision of 2026-11-24 for the keys-only corner (LEGIT has no co-steer, and the corner lanes need the strafe key
 * from the takeoff: 12 of 16 with no reaction delay, none with one): a strafe key a script planned on the ground
 * presses together with the jump key, on its tick or the tick after, is part of the takeoff and not a reaction, so
 * the delay does not hold it back ({@link KeyChangeLimiter#withTheJump}). It counts as a key change and is held the
 * minimum time; every later change (letting go of forward behind the corner) still waits out the delay.
 * <p>
 * Known limits these tests do not hide (open items, not the planner's):
 * <ul>
 * <li>The course neo needs the view at every level: none keys only.</li>
 * <li>Co-pilot on the lone fence post with a planned reaction delay of 3 or 4 ticks: the brake starts late, ends
 * before the touchdown and the landing hold does not take over (12 of 18 slide off; Takeover holds all).</li>
 * </ul>
 */
class LegitLevelLoopTest {

    private static final JumpCommit.Mode[] MODES = {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY};
    private static final ParkourLaneReplicaTest.Lane[] STRAIGHT = {ParkourLaneReplicaTest.FENCE2,
            ParkourLaneReplicaTest.WALL3, ParkourLaneReplicaTest.PANE1};
    private static final ParkourLaneReplicaTest.Lane[] CORNERS = {ParkourLaneReplicaTest.CORNER_FENCE,
            ParkourLaneReplicaTest.CORNER_WALL};

    /** Lane runs that land and stay, over four tick phases of the run-up and both modes (8 runs per lane). */
    static int lanes(ParkourLaneReplicaTest.Lane[] lanes, StringBuilder misses) {
        int landed = 0;
        try {
            for (double z0 : new double[] {0.5, 0.57, 0.64, 0.71}) {
                ParkourLaneReplicaTest.START_Z = z0;
                for (ParkourLaneReplicaTest.Lane lane : lanes) {
                    for (JumpCommit.Mode mode : MODES) {
                        ParkourLaneReplicaTest.Run r = ParkourLaneReplicaTest.roll(lane, mode, false,
                                ParkourLaneReplicaTest.REAL);
                        if (r.stayed == ParkourLaneReplicaTest.STAY_TICKS) {
                            landed++;
                        } else {
                            misses.append(' ').append(lane.name()).append('/').append(mode).append('@').append(z0);
                        }
                    }
                }
            }
        } finally {
            ParkourLaneReplicaTest.START_Z = 0.5;
        }
        return landed;
    }

    /** Course-neo loops that land, of 60 (the grid of {@link CourseNeoIntentTest}, both modes). */
    static int neo() {
        int landed = 0;
        for (JumpCommit.Mode mode : MODES) {
            for (double x = 0.3; x <= 0.71; x += 0.2) {
                for (double z = -0.8; z <= 0.41; z += 0.3) {
                    for (float pitch : new float[] {15f, 30f}) {
                        if (CourseNeoIntentTest.loop(mode, x, z, pitch, null).equals("landed")) {
                            landed++;
                        }
                    }
                }
            }
        }
        return landed;
    }

    /** Fence-post loops that hold the post, of 18 (the grid of {@link FencePostLoopTest}, no glance). */
    static int fence(JumpCommit.Mode mode) {
        int held = 0;
        for (double x = 0.3; x <= 0.71; x += 0.2) {
            for (double z = -6.0; z <= -4.9; z += 0.5) {
                for (float pitch : new float[] {10f, 25f}) {
                    if (FencePostLoopTest.loop(mode, x, z, pitch, 0).equals("held")) {
                        held++;
                    }
                }
            }
        }
        return held;
    }

    /**
     * The LEGIT key limits with co-steer still on (the knobs are separate: a profile can hold both). The scripts fly
     * the keys they were planned with, and the lanes and the neo land as at BALANCED.
     */
    @Test
    void scriptsPlannedUnderTheLegitKeyLimitsAreFlownAsPlanned() {
        Assumptions.assumeTrue(ParkourLaneReplicaTest.shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        for (int reaction = 2; reaction <= 4; reaction++) {
            LevelUnderTest.set(3, 3, reaction, true);
            try {
                StringBuilder misses = new StringBuilder();
                int straight = lanes(STRAIGHT, misses);
                int corners = lanes(CORNERS, misses);
                int neo = neo();
                System.out.println("LEVEL legit keys + co-steer, reaction " + reaction + ": straight lanes " + straight
                        + "/24, corner lanes " + corners + "/16, neo " + neo + "/60, script air ticks "
                        + LevelUnderTest.scriptAirTicks + ", key mismatches " + LevelUnderTest.scriptKeyMismatches
                        + misses);
                if (LevelUnderTest.scriptKeyMismatches != 0 || LevelUnderTest.scriptAirTicks < 300) {
                    fails.append("reaction ").append(reaction).append(": ").append(LevelUnderTest.scriptKeyMismatches)
                            .append(" of ").append(LevelUnderTest.scriptAirTicks)
                            .append(" script air ticks went out with other keys than the script's\n");
                }
                if (straight != 24 || corners != 16 || neo < 54) {
                    fails.append("reaction ").append(reaction).append(": straight ").append(straight)
                            .append("/24, corners ").append(corners).append("/16, neo ").append(neo).append("/60")
                            .append(misses).append('\n');
                }
            } finally {
                LevelUnderTest.balanced();
            }
        }
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    /** BALANCED is what the other loop tests run at: the same count there, so the check above is not empty. */
    @Test
    void balancedScriptsAreFlownAsPlanned() {
        Assumptions.assumeTrue(ParkourLaneReplicaTest.shapes(), "needs the shape-physics replica");
        LevelUnderTest.balanced();
        StringBuilder misses = new StringBuilder();
        int landed = lanes(STRAIGHT, misses) + lanes(CORNERS, misses);
        int neo = neo();
        System.out.println("LEVEL balanced: lanes " + landed + "/40, neo " + neo + "/60, script air ticks "
                + LevelUnderTest.scriptAirTicks + ", key mismatches " + LevelUnderTest.scriptKeyMismatches + misses);
        assertEquals(0, LevelUnderTest.scriptKeyMismatches, "script air ticks with other keys than the script's");
        assertTrue(LevelUnderTest.scriptAirTicks >= 300, "scripts flown: " + LevelUnderTest.scriptAirTicks);
        assertEquals(40, landed, misses.toString());
        assertEquals(60, neo);
    }

    /**
     * LEGIT as the module runs it (keys only), at each reaction delay it varies over: the straight lanes land and
     * stay, Takeover holds the fence post, Co-pilot holds it at the shortest delay, and no script is ever flown with
     * other keys than its own. The corner lanes land with the planned strafe key down with the jump: 12 of 16 at a
     * delay of 2 and 3 (what keys alone land with no delay at all; the four misses are the run-up phase 0.71, the
     * known limit of {@link ParkourLaneReplicaTest#theCornersDoNotDependOnTheTiming}), 8 of 16 at a delay of 4.
     */
    @Test
    void legitLandsTheStraightAndTheCornerLanesAtEveryReactionDelay() {
        Assumptions.assumeTrue(ParkourLaneReplicaTest.shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        for (int reaction = 2; reaction <= 4; reaction++) {
            LevelUnderTest.legit(reaction);
            try {
                StringBuilder misses = new StringBuilder();
                int straight = lanes(STRAIGHT, misses);
                int corners = lanes(CORNERS, misses);
                int takeover = fence(JumpCommit.Mode.CARRY);
                int coPilot = fence(JumpCommit.Mode.GUIDE);
                System.out.println("LEVEL legit, reaction " + reaction + ": straight lanes " + straight
                        + "/24, corner lanes " + corners + "/16, fence post takeover " + takeover + "/18, co-pilot "
                        + coPilot + "/18, script air ticks " + LevelUnderTest.scriptAirTicks + ", key mismatches "
                        + LevelUnderTest.scriptKeyMismatches + misses);
                if (straight != 24 || corners < (reaction == 4 ? 8 : 12) || takeover != 18
                        || (reaction == 2 && coPilot != 18) || LevelUnderTest.scriptKeyMismatches != 0) {
                    fails.append("reaction ").append(reaction).append(": straight ").append(straight)
                            .append("/24, corners ").append(corners).append("/16, fence post takeover ").append(takeover).append("/18, co-pilot ")
                            .append(coPilot).append("/18, key mismatches ")
                            .append(LevelUnderTest.scriptKeyMismatches).append(misses).append('\n');
                }
            } finally {
                LevelUnderTest.balanced();
            }
        }
        assertTrue(fails.length() == 0, "\n" + fails);
    }
}
