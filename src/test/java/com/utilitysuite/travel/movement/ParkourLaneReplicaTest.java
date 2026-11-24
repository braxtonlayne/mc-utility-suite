package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.SupportRegionTest.ShapeGrid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The devtest landing lanes ({@code ParkourScenario.landingLane}) in the replica, with the REAL intent estimator in
 * front of the brain (the other approach tests force the intent target). Same script as in game: W + sprint held
 * from the start of an 11-block run-up, yaw 0, pitch 10, one jump press on the first tick at z >= 10.85 (also when
 * the assist already took the jump: a planned press still comes), and on corner lanes a 2-tick strafe tap toward the
 * post 6 ticks after the press.
 * <p>
 * The second half is the evidence behind the lane set (run note 2026-11-22, "Parkour lanes"): replica searches of
 * what each control envelope can land at all. Set {@code LANE_SEARCH=1} to print the hill-climb results,
 * {@code LANE_TRACE=<part of a lane name>} for a per-tick trace of the lane runs.
 */
@Tag("needs-shape-physics")
class ParkourLaneReplicaTest {

    static final int FLOOR = 19;
    static final int STAY_TICKS = 40;
    /** Rendered frames per client tick in the lane runs (60 fps). */
    static int FRAMES_PER_TICK = 3;
    /** The estimator starts with the jumps of earlier lanes noted (as in the playtest) instead of fresh. */
    static boolean HISTORY = System.getenv("LANE_HISTORY") != null;
    /** Where the run-up starts (shifts the phase of the ticks against the edge) and when the corner tap comes. */
    static double START_Z = 0.5;
    static int TAP_AT = 6;

    /** A lane: where the post is, its shape, the column of a corner lane, the run-up start. */
    record Lane(String name, float[] shape, int postX, int postZ, int columnTo, double startX, float startYaw,
                int tapStrafe) {
    }

    static final Lane FENCE2 = new Lane("fence-2gap", ShapeGrid.FENCE_POST, 0, 13, 0, 0.5, 0f, 0);
    static final Lane WALL3 = new Lane("wall-3gap", ShapeGrid.WALL_POST, 0, 14, 0, 0.5, 0f, 0);
    static final Lane PANE1 = new Lane("pane-1gap", ShapeGrid.PANE_POST, 0, 12, 0, 0.5, 0f, 0);
    /**
     * The corner lanes of the playtest: the post one block to the side, behind a stone column ONE block deep at the
     * platform edge (x + 1, z 11). Fence post across 2 air blocks, wall post across 3.
     */
    static final Lane CORNER_FENCE = new Lane("corner-fence-2gap", ShapeGrid.FENCE_POST, 1, 13, 11, 0.5, 0f, 1);
    static final Lane CORNER_WALL = new Lane("corner-wall-3gap", ShapeGrid.WALL_POST, 1, 14, 11, 0.5, 0f, 1);
    /**
     * As first built: both posts across 3 air blocks behind a column TWO blocks deep (z 11..12). The search finds
     * touchdowns, but only frame-perfect ones (wall slides, margins under 0.2); no clean technique lands them.
     */
    static final Lane CORNER_FENCE_DEEP = new Lane("corner-fence-3gap-deep", ShapeGrid.FENCE_POST, 1, 14, 12, 0.5,
            0f, 1);
    static final Lane CORNER_WALL_DEEP = new Lane("corner-wall-3gap-deep", ShapeGrid.WALL_POST, 1, 14, 12, 0.5, 0f,
            1);
    /** The deep fence corner with the thin column (still across 3): no clean technique either. */
    static final Lane CORNER_FENCE_THIN3 = new Lane("corner-fence-3gap", ShapeGrid.FENCE_POST, 1, 14, 11, 0.5, 0f, 1);

    /** The lanes Co-pilot and Takeover must land. */
    static final Lane[] LANES = {FENCE2, WALL3, PANE1, CORNER_FENCE, CORNER_WALL};

    static ShapeGrid grid(Lane lane) {
        ShapeGrid g = new ShapeGrid().fill(-1, FLOOR, 0, 1, FLOOR, 10, ShapeGrid.FULL);
        g.put(lane.postX, FLOOR, lane.postZ, lane.shape);
        if (lane.columnTo >= 11) {
            g.fill(1, FLOOR, 11, 1, FLOOR + 3, lane.columnTo, ShapeGrid.FULL);
        }
        g.fill(-8, 5, -4, 8, 5, 24, ShapeGrid.FULL);
        return g;
    }

    /** Where the brain gets its target: the real estimator, a fixed target, or a fixed one dropped once airborne. */
    static final int REAL = 0;
    static final int FORCED = 1;
    static final int DROPPED_IN_THE_AIR = 2;
    /** The real estimator, but "blocked" reaches the brain three ticks late (its roll cache can lag the pick). */
    static final int LATE_BLOCKED = 3;
    /** The real estimator until the press, then no target (the search behind a held press is dropped mid-hold). */
    static final int DROPPED_AFTER_PRESS = 4;

    static final class Run {
        int stayed = -1;
        boolean landed;
        boolean everTarget;
        boolean targetAtPress;
        boolean committed;
        double jumpZ = Double.NaN;
        double maxZ = -1e9;
        double touchX = Double.NaN, touchZ = Double.NaN;
        float maxYawStep;
        boolean freshKeyReplaced;
        int maxIntentSim;
        int maxCommitSim;
        int pressTick = -1;
        int takeoffTick = -1;
        /** The body left the ground on a jump (false: it walked off the edge). */
        boolean takeoffJumped;
        /** The brain held the user's press back on at least one tick before the body left the ground. */
        boolean pressHeld;
        boolean frozenInFlight;
        double maxTotalTurn;
        double maxLeftError;
        StringBuilder trace = new StringBuilder();
    }

    static Run roll(Lane lane, JumpCommit.Mode mode, boolean trace, int intentMode) {
        ShapeGrid g = grid(lane);
        SupportRegion top = new SupportRegion();
        double topY = top.topOf(g, lane.postX, FLOOR, lane.postZ);
        double b0 = top.cellTopBound(0), b1 = top.cellTopBound(1), b2 = top.cellTopBound(2), b3 = top.cellTopBound(3);
        top.clear();
        top.setTop(topY);
        top.addTopBox(b0, b1, b2, b3);

        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        b.sprintWithholdAvailable = true;
        b.setTechniques(new TechniqueLibrary());
        b.setKeyChangeLimits(6, 2, 0); // BALANCED, as the module sets them
        IntentEstimator intent = new IntentEstimator();
        if (HISTORY) {
            // the jumps of the lanes run before this one (other lanes, 8 blocks apart): the line of recent jumps
            intent.noteJump(100.5, 11.4, 100.5, FLOOR + 1.5, 13.4);
            intent.noteJump(108.5, 11.4, 108.5, FLOOR + 1.5, 14.2);
            intent.noteJump(116.5, 10.3, 116.5, FLOOR + 1.0, 12.6);
        }
        boolean carry = mode == JumpCommit.Mode.CARRY;
        float steerRate = mode == JumpCommit.Mode.NUDGE ? 0f : carry ? 12f : 7.5f; // BALANCED, deg per tick
        float steerMax = carry ? 90f : 25f;

        MovementPhysics p = new MovementPhysics().set(lane.startX, FLOOR + 1, START_Z, 0, -0.0784, 0, true);
        Run r = new Run();
        float yaw = lane.startYaw;
        com.utilitysuite.core.input.YawCoSteer steer = new com.utilitysuite.core.input.YawCoSteer();
        int pressTick = -1;
        int takeoffTick = -1;
        int landedAt = -1;
        int lateTicks = 0;
        boolean wasGround = true;
        boolean sprint = true;
        for (int t = 0; t < 400; t++) {
            boolean uj = pressTick < 0 && p.z >= 10.85; // the planned press comes even when the assist took the jump
            if (uj) {
                pressTick = t;
                r.pressTick = t;
            }
            int us = 0;
            if (lane.tapStrafe != 0 && pressTick >= 0 && t - pressTick >= TAP_AT && t - pressTick < TAP_AT + 2) {
                us = lane.tapStrafe;
            }
            intent.setCommitThreshold(carry ? IntentEstimator.CARRY_THRESHOLD : IntentEstimator.GUIDE_THRESHOLD);
            b.intentThreshold = intent.commitThreshold();
            boolean has = intent.estimate(p, yaw, 10f, 1, us, sprint, g) && intent.hasTarget();
            r.maxIntentSim = Math.max(r.maxIntentSim, intent.lastSimTicks());
            if (intentMode == DROPPED_AFTER_PRESS && pressTick >= 0 && t > pressTick) {
                has = false;
                b.clearIntentTarget();
            } else if (intentMode == DROPPED_IN_THE_AIR && !p.onGround) {
                has = false;
                b.clearIntentTarget();
            } else if (intentMode == FORCED || intentMode == DROPPED_IN_THE_AIR) {
                has = true;
                b.setIntentTarget(lane.postX, FLOOR, lane.postZ, 0.9);
            } else if (has) {
                b.setIntentBlocked(intent.targetBlocked() && (intentMode != LATE_BLOCKED || ++lateTicks > 3));
                b.setIntentLook(intent.lookConfidence());
                b.setIntentTarget(intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence());
                r.everTarget = true;
            } else {
                b.clearIntentTarget();
            }
            if (uj) {
                r.targetAtPress = has;
            }
            b.coSteerAllowed = steerRate > 0f;
            b.coSteerMaxDegPerTick = steerRate;
            b.coSteerMaxTotalDeg = steerMax;
            if (!b.commit().live()) {
                steer.reset(); // a new commitment: the allowance starts over (the module begins a new grant)
            }
            b.setCoSteerSpentDeg(steer.totalAddedDeg()); // as the module does from its grant
            if (b.commit().live()) {
                // the brain's count of what is left of the co-steer total, against what the core has added
                double core = Math.max(0.0, steerMax - steer.totalAddedDeg());
                r.maxLeftError = Math.max(r.maxLeftError, Math.abs(b.coSteerLeft() - core));
            }
            b.decide(p, yaw, sprint, 1, us, uj, 20f, g);
            r.committed |= b.commit().live();
            r.maxCommitSim = Math.max(r.maxCommitSim, b.lastCommitSimTicks());
            if (!p.onGround && p.vy < 0.0 && landedAt < 0) {
                r.frozenInFlight = b.targetFrozen() && b.intentActive();
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? us : b.strafe();
            if (us != 0 && s != us) {
                r.freshKeyReplaced = true; // a fresh strafe key of the user's is never replaced
            }
            boolean jump = (uj && !b.withholdJump()) || b.jump();
            if (pressTick >= 0 && takeoffTick < 0 && b.withholdJump()) {
                r.pressHeld = true;
            }
            boolean jumpedThisTick = jump && p.onGround;
            // the view: the real follower of the core (YawCoSteer), one request per tick, applied over the rendered
            // frames AFTER this tick's physics, exactly like MouseHandler.turnPlayer in the client
            steer.beginTick();
            if (b.wantsCoSteer() && steerRate > 0f) {
                steer.request(b.coSteerYaw(), steerRate * 20.0, Math.min(steerMax, b.coSteerTotalDeg()), b.commit().authority());
            }
            float yawUsed = yaw;
            if (trace && p.z > 6.0 && r.trace.length() < 6000 && (landedAt < 0 || t - landedAt < 3)) {
                r.trace.append(String.format(
                        "%n[%d x%.2f z%.2f y%.2f v%.3f yaw%.0f f%d s%d%s%s%s %s int=%s(%d,%d,%d c%.2f n%d) fz%s ap%s]", t,
                        p.x, p.z, p.y, Math.hypot(p.vx, p.vz), yaw, f, s, jump ? " J" : "",
                        b.withholdJump() ? " W" : "", b.sprintOff() ? " S0" : "", b.commit().phase(),
                        has ? (intent.targetBlocked() ? "B" : "Y") : "n", intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence(),
                        intent.lastCandidateCount(), b.targetFrozen() ? "1" : "0", (b.approach().engaged() ? "1" : "0") + (b.approach().exhausted() ? "x" : "") + (b.scriptActive() ? " SC" + b.scriptIndex() + b.activeScript().name : "") + " us" + us));
            }
            sprint = !b.sprintOff() && f > 0;
            p.tick(f, s, yawUsed, sprint, jump, b.sneak(), g);
            for (int k = 0; k < FRAMES_PER_TICK; k++) {
                yaw += (float) steer.onFrame(yaw, 0.0, 0.0, 0.05 / FRAMES_PER_TICK, false);
            }
            r.maxYawStep = Math.max(r.maxYawStep, Math.abs(yaw - yawUsed));
            r.maxTotalTurn = Math.max(r.maxTotalTurn, steer.totalAddedDeg());
            if (wasGround && !p.onGround && Double.isNaN(r.jumpZ)) {
                r.jumpZ = p.z;
                takeoffTick = t;
                r.takeoffTick = t;
                r.takeoffJumped = jumpedThisTick;
            }
            wasGround = p.onGround;
            r.maxZ = Math.max(r.maxZ, p.z);
            boolean onTop = p.onGround && Math.abs(p.y - topY) < 1e-6 && top.contains(p.x, p.z);
            if (landedAt < 0) {
                if (onTop) {
                    landedAt = t;
                    r.landed = true;
                    r.touchX = p.x;
                    r.touchZ = p.z;
                } else if (p.y < FLOOR - 2) {
                    break;
                }
            } else if (!onTop) {
                r.stayed = t - landedAt;
                break;
            } else if (t - landedAt >= STAY_TICKS) {
                r.stayed = STAY_TICKS;
                break;
            }
        }
        return r;
    }

    static float wrap(float d) {
        while (d > 180f) {
            d -= 360f;
        }
        while (d < -180f) {
            d += 360f;
        }
        return d;
    }

    static Run roll(Lane lane, JumpCommit.Mode mode) {
        Run r = roll(lane, mode, lane.name.contains(String.valueOf(System.getenv("LANE_TRACE"))), REAL);
        System.out.println(line(lane, mode, r));
        return r;
    }

    static String line(Lane lane, JumpCommit.Mode mode, Run r) {
        return "LANE " + lane.name + " " + mode + ": landed " + r.landed + " stayed " + r.stayed + " target "
                + r.everTarget + " committed " + r.committed + String.format(
                " takeoff z %.2f, max z %.2f, touchdown x %.2f z %.2f, largest view step %.1f deg", r.jumpZ, r.maxZ,
                r.touchX, r.touchZ, r.maxYawStep) + r.trace;
    }

    static boolean shapes() {
        return LandingJointTest.replicaHonoursShapes();
    }

    /** Hard rotation cap: 450 deg/s. */
    static final float MAX_STEP = 22.5f;

    @Test
    void coPilotAndTakeoverLandEveryLaneAndStay() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        for (Lane lane : LANES) {
            for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
                Run r = roll(lane, mode);
                String id = lane.name + " " + mode;
                if (!r.everTarget || !r.committed) {
                    fails.append(id).append(": never armed (target ").append(r.everTarget).append(", committed ")
                            .append(r.committed).append(")\n");
                }
                if (r.stayed != STAY_TICKS) {
                    fails.append(id).append(": stayed ").append(r.stayed).append(' ').append(line(lane, mode, r))
                            .append('\n');
                }
                if (r.freshKeyReplaced) {
                    fails.append(id).append(": a fresh key of the user's was replaced\n");
                }
                if (r.maxYawStep > MAX_STEP + 1.0e-3f) {
                    fails.append(id).append(": view step ").append(r.maxYawStep).append('\n');
                }
                if (r.maxIntentSim > IntentEstimator.MAX_SIM_TICKS) {
                    fails.append(id).append(": estimator sim ticks ").append(r.maxIntentSim).append('\n');
                }
                if (r.maxCommitSim > MovementAssistBrain.MAX_COMMIT_SIM_TICKS) {
                    fails.append(id).append(": commit sim ticks ").append(r.maxCommitSim).append('\n');
                }
            }
        }
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    /**
     * Corrections (design 3, NUDGE): per-tick corrections and the shared landing guarantee, no commitment, no view
     * change. A landing is not promised (see {@link #correctionsCannotLandTheWallOrThePaneFromThisRunUp}); a landing
     * that is made must hold with W still down.
     */
    @Test
    void correctionsNeverWalksOffALandingItMade() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (Lane lane : LANES) {
            Run r = roll(lane, JumpCommit.Mode.NUDGE);
            assertTrue(r.stayed == -1 || r.stayed == STAY_TICKS, lane.name + ": walked off " + r.stayed
                    + " ticks after a landing");
            assertFalse(r.committed, lane.name + ": Corrections never commits");
            assertEquals(0f, r.maxYawStep, 0f, lane.name + ": Corrections never turns the view");
            assertFalse(r.freshKeyReplaced, lane.name);
            if (lane.columnTo >= 11) {
                // a top that needs a way around is no target in Corrections (it has no technique search): the
                // takeoff stays the user's own press, not an early edge jump at a blocked line
                assertEquals(r.pressTick, r.takeoffTick, lane.name + ": the takeoff was not the user's press");
            }
        }
    }

    /**
     * The view is turned by the core's follower over rendered frames, so the frame rate changes where the view is on
     * each tick by a little. The lanes must not depend on it: 40, 100 and 160 fps (the other tests run at 60).
     */
    @Test
    void theLanesDoNotDependOnTheFrameRate() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        try {
            for (int frames : new int[] {2, 5, 8}) {
                FRAMES_PER_TICK = frames;
                for (Lane lane : LANES) {
                    for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
                        Run r = roll(lane, mode, false, REAL);
                        if (r.stayed != STAY_TICKS) {
                            fails.append(frames * 20).append(" fps ").append(line(lane, mode, r)).append('\n');
                        }
                    }
                }
            }
        } finally {
            FRAMES_PER_TICK = 3;
        }
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    /**
     * The corner lanes must not hang on one lucky timing: other tick phases against the edge (the run-up starts a
     * little further on), the tap earlier or later, the estimator fresh or with earlier jumps noted.
     * <p>
     * Known limit (open item): the wall post across 3 is at the end of the reach (64 clean shapes of 49392). With the
     * run-up starting at 0.71 the last takeoff tick is 0.07 block earlier and Co-pilot finds no script; Takeover still
     * lands it. Nothing shifts the tick phase on the run-up yet. There the press must still go within 4 ticks and
     * nothing may be forced.
     */
    @Test
    void theCornersDoNotDependOnTheTiming() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        int runs = 0;
        int atLimit = 0;
        try {
            for (double z0 : new double[] {0.5, 0.57, 0.64, 0.71}) {
                for (int tap : new int[] {4, 6, 8}) {
                    for (boolean history : new boolean[] {false, true}) {
                        START_Z = z0;
                        TAP_AT = tap;
                        HISTORY = history;
                        for (Lane lane : new Lane[] {CORNER_FENCE, CORNER_WALL}) {
                            for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE,
                                    JumpCommit.Mode.CARRY}) {
                                Run r = roll(lane, mode, false, REAL);
                                runs++;
                                boolean limit = lane == CORNER_WALL && mode == JumpCommit.Mode.GUIDE && z0 > 0.7;
                                if (limit) {
                                    atLimit++;
                                    if (r.freshKeyReplaced || (r.stayed != -1 && r.stayed != STAY_TICKS)
                                            || r.takeoffTick - r.pressTick > ApproachController.MAX_WITHHOLD_TICKS) {
                                        fails.append(String.format("start %.2f tap %d (limit case): ", z0, tap))
                                                .append(line(lane, mode, r)).append('\n');
                                    }
                                } else if (r.stayed != STAY_TICKS
                                        || (r.freshKeyReplaced && mode == JumpCommit.Mode.GUIDE)) {
                                    fails.append(String.format("start %.2f tap %d history %s: ", z0, tap, history))
                                            .append(line(lane, mode, r)).append('\n');
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            START_Z = 0.5;
            TAP_AT = 6;
            HISTORY = false;
        }
        System.out.println("SEARCH corner timing sweep: " + runs + " runs, " + atLimit + " at the known limit");
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    /** The first-built deep corners: nothing lands them yet, and nothing may be forced on the way. */
    @Test
    void deepCornersStayWithinTheRules() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (Lane lane : new Lane[] {CORNER_FENCE_DEEP, CORNER_WALL_DEEP}) {
            for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
                Run r = roll(lane, mode);
                assertTrue(r.stayed == -1 || r.stayed == STAY_TICKS, lane.name + " " + mode);
                assertFalse(r.freshKeyReplaced, lane.name + " " + mode);
                assertTrue(r.maxYawStep <= MAX_STEP + 1.0e-3f, lane.name + " " + mode);
            }
        }
    }

    // ---- the pieces behind the lanes ---------------------------------------------------------------------------------

    /** The scripted run-up (W + sprint, the lane's yaw) until z reaches {@code toZ}, estimating on every tick. */
    static MovementPhysics runUp(Lane lane, ShapeGrid g, double toZ, IntentEstimator e) {
        MovementPhysics p = new MovementPhysics().set(lane.startX, FLOOR + 1, 0.5, 0, -0.0784, 0, true);
        while (p.z < toZ) {
            if (e != null) {
                e.estimate(p, lane.startYaw, 10f, 1, 0, true, g);
            }
            p.tick(1, 0, lane.startYaw, true, false, false, g);
        }
        if (e != null) {
            e.estimate(p, lane.startYaw, 10f, 1, 0, true, g);
        }
        return p;
    }

    /** With one top to go to, the camera looking past it (pitch 10) must not keep the confidence under the commit. */
    @Test
    void theOnlyTopAheadArmsWithoutALookAtIt() {
        ShapeGrid g = grid(PANE1);
        IntentEstimator e = new IntentEstimator();
        runUp(PANE1, g, 10.8, e);
        assertTrue(e.hasTarget(), "confidence " + e.confidence());
        assertEquals(12, e.targetZ());
        assertTrue(e.confidence() >= IntentEstimator.SOLE_CONFIDENCE - 1.0e-9, "confidence " + e.confidence());
        assertFalse(e.targetBlocked());
    }

    /**
     * A teleport or respawn between a takeoff and the next ground tick is not a jump: it must not enter the line of
     * recent jumps or the ring of visited platforms. (In the lane run a fall and the teleport to the next lane was
     * noted as a jump of 13 blocks; the line term then cost every later target 0.075 of its score.)
     */
    @Test
    void aTeleportIsNotNotedAsAJump() {
        ShapeGrid g = grid(PANE1);
        IntentEstimator plain = new IntentEstimator();
        IntentEstimator teleported = new IntentEstimator();
        // took off 13 blocks away and came down on the post: a teleport, not a visit
        teleported.noteJump(8.5, 24.2, 0.5, FLOOR + 1.0, 12.5);
        runUp(PANE1, g, 10.6, plain);
        runUp(PANE1, g, 10.6, teleported);
        assertEquals(plain.confidence(), teleported.confidence(), 0.0);
        // control: a real jump onto the post makes it a visited platform (penalised, no sole-candidate floor)
        IntentEstimator jumped = new IntentEstimator();
        jumped.noteJump(0.5, 9.5, 0.5, FLOOR + 1.0, 12.5);
        runUp(PANE1, g, 10.6, jumped);
        assertTrue(jumped.confidence() != plain.confidence(), "plain " + plain.confidence() + " jumped "
                + jumped.confidence() + " candidates " + plain.lastCandidateCount());
    }

    /** A top behind a column: the straight roll hits the column, the top stays a candidate and says so. */
    @Test
    void aTopBehindAColumnStaysACandidateMarkedBlocked() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        ShapeGrid g = grid(CORNER_WALL);
        IntentEstimator e = new IntentEstimator();
        runUp(CORNER_WALL, g, 10.0, e);
        assertTrue(e.hasTarget(), "confidence " + e.confidence());
        assertEquals(14, e.targetZ());
        assertTrue(e.targetBlocked());
        assertEquals(0, e.feasibility(1, FLOOR, 14), "not feasible on the straight line");
        // a top that is simply out of reach is still dropped: same post 3 blocks further
        ShapeGrid far = new ShapeGrid().fill(-1, FLOOR, 0, 1, FLOOR, 10, ShapeGrid.FULL);
        far.put(0, FLOOR, 16, ShapeGrid.WALL_POST);
        far.fill(-8, 5, -4, 8, 5, 24, ShapeGrid.FULL);
        IntentEstimator f = new IntentEstimator();
        runUp(WALL3, far, 10.8, f);
        assertFalse(f.hasTarget());
        assertFalse(f.targetBlocked());
        // nor is a wide platform across 5: the roll falls short and runs into the platform's own side, which is not a
        // wall in the way (a top marked BLOCKED by mistake stays a rival and a target the body cannot reach)
        ShapeGrid pad = new ShapeGrid().fill(-1, FLOOR, 0, 1, FLOOR, 10, ShapeGrid.FULL);
        pad.fill(-1, FLOOR, 16, 1, FLOOR, 19, ShapeGrid.FULL);
        pad.fill(-8, 5, -4, 8, 5, 24, ShapeGrid.FULL);
        IntentEstimator q = new IntentEstimator();
        runUp(WALL3, pad, 10.8, q);
        assertFalse(q.hasTarget(), "confidence " + q.confidence());
        for (int x = -1; x <= 1; x++) {
            for (int z = 16; z <= 17; z++) {
                assertTrue(q.feasibility(x, FLOOR, z) <= 0, "cell " + x + ", " + z);
            }
        }
        assertEquals(0, q.lastCandidateCount() == 0 ? 0 : (q.confidence() > 0.0 ? 1 : 0), "an unreachable pad scored");
    }

    /**
     * The estimator may drop or change its pick on the takeoff tick (the body is in the air and close): the armed
     * target is the one the jump was planned for and is frozen with the takeoff.
     */
    @Test
    void theArmedTargetSurvivesAnEstimatorDropOnTheTakeoffTick() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            Run r = roll(PANE1, mode, false, DROPPED_IN_THE_AIR);
            assertTrue(r.frozenInFlight, mode + ": the target was not frozen through the flight");
            assertEquals(STAY_TICKS, r.stayed, mode + " " + line(PANE1, mode, r));
        }
        // Corrections has no commitment: there the target follows the estimator on every tick
        Run n = roll(PANE1, JumpCommit.Mode.NUDGE, false, DROPPED_IN_THE_AIR);
        assertFalse(n.frozenInFlight);
    }

    /**
     * "Blocked" may reach the brain a few ticks after the target (the estimator's roll cache can lag its pick): the
     * corners must still land, and the view must stay inside the level's co-steer total over the whole commitment.
     * <p>
     * A script may only ask for what is left of that total. In the client a corner-fence script in Co-pilot predicted
     * a view 31 degrees from the commitment's start against a total of 25 and left its line (2026-11-22). This
     * sequence does not reproduce that case (it passed before the fix too); the count itself is pinned by
     * {@link #theBrainKnowsWhatIsLeftOfTheCoSteerTotal}.
     */
    @Test
    void aScriptPlannedAfterAnEarlyAimStaysInsideTheCoSteerTotal() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (Lane lane : new Lane[] {CORNER_FENCE, CORNER_WALL}) {
            for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
                Run r = roll(lane, mode, false, LATE_BLOCKED);
                assertEquals(STAY_TICKS, r.stayed, line(lane, mode, r));
                assertTrue(r.maxTotalTurn <= (mode == JumpCommit.Mode.CARRY ? 90.0 : 25.0) + 1.0e-3,
                        lane.name + " " + mode + ": turned " + r.maxTotalTurn);
            }
        }
    }

    /**
     * The brain's count of what is left of the co-steer total is the core's own count (the module hands it over every
     * tick), to the whole degree it is rounded down to, through a commitment that turns the view.
     */
    @Test
    void theBrainKnowsWhatIsLeftOfTheCoSteerTotal() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        MovementAssistBrain fresh = new MovementAssistBrain();
        fresh.coSteerMaxTotalDeg = 25f;
        assertEquals(25f, fresh.coSteerLeft(), 0f, "nothing spent yet");
        // a lane that turns the view under the real (authority-scaled) co-steer: the straight wall-3gap turns under 1
        // degree and the corner-wall now takes a keys-only script
        for (Lane lane : new Lane[] {CORNER_FENCE}) {
            Run r = roll(lane, JumpCommit.Mode.GUIDE, false, REAL);
            assertTrue(r.maxTotalTurn > 2.0, lane.name + ": this lane turns the view (" + r.maxTotalTurn + ")");
            assertTrue(r.maxLeftError <= 1.0 + 1.0e-4, lane.name + ": the brain's count was off by " + r.maxLeftError);
        }
    }

    /** Sprint run-up distance per tick at top speed (vanilla ground sprint). */
    static final double SPRINT_TICK = 0.2806;

    /**
     * A press the brain holds back never outlives the support (playtest 2026-11-22 17:37, corner-fence-2gap in
     * Co-pilot: the held press at tick 39 turned into a walk-off at z 11.51, jump false, max y 1.00). Every lane, both
     * committed modes, the run-up started at each quarter-tick phase against the edge and up to 3 whole ticks on: once
     * the user has pressed, the body leaves the ground on a jump, never by walking off. Also with the target dropped
     * right after the press (the search behind the hold goes away mid-hold: the held press must still go).
     */
    @Test
    void aHeldBackPressNeverTurnsIntoAWalkOff() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        int held = 0;
        int runs = 0;
        try {
            for (int ticks = 0; ticks < 4; ticks++) {
                for (int quarter = 0; quarter < 4; quarter++) {
                    START_Z = 0.5 + (ticks + quarter * 0.25) * SPRINT_TICK;
                    for (Lane lane : LANES) {
                        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE,
                                JumpCommit.Mode.CARRY}) {
                            for (int intentMode : new int[] {REAL, DROPPED_AFTER_PRESS}) {
                                Run r = roll(lane, mode, false, intentMode);
                                runs++;
                                if (r.pressHeld) {
                                    held++;
                                }
                                boolean pressedFirst = r.pressTick >= 0 && r.pressTick <= r.takeoffTick;
                                if (pressedFirst && !r.takeoffJumped) {
                                    fails.append(String.format("start %.3f (%s%s): walked off after the press%s: ",
                                            START_Z, mode, intentMode == REAL ? "" : ", target dropped",
                                            r.pressHeld ? " (held back)" : ""))
                                            .append(line(lane, mode, r)).append('\n');
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            START_Z = 0.5;
        }
        System.out.println("SEARCH held-press phase sweep: " + runs + " runs, " + held + " with the press held back");
        assertTrue(held > 0, "the sweep never held a press back: it does not exercise the hold");
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    /** In Co-pilot the user's press is held back at most 4 ticks for a script's takeoff, never swallowed. */
    @Test
    void theUsersPressIsHeldBackAtMostFourTicks() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (Lane lane : LANES) {
            Run r = roll(lane, JumpCommit.Mode.GUIDE, false, REAL);
            if (r.pressTick >= 0 && r.takeoffTick > r.pressTick) {
                assertTrue(r.takeoffTick - r.pressTick <= ApproachController.MAX_WITHHOLD_TICKS, lane.name
                        + ": the press was held back " + (r.takeoffTick - r.pressTick) + " ticks");
            }
        }
    }

    // ---- replica search: which lanes can be landed at all, by which control envelope ------------------------------

    /** What a search may do to the scripted run (W + sprint held, yaw 0 at the start). */
    record Envelope(String name, float degPerTick, float maxTotalDeg, boolean back, boolean keepTap, boolean userTakeoff) {
    }

    /** Keys only (Legit): no yaw; the stale W may be withheld, the free strafe axis filled. */
    static final Envelope KEYS = new Envelope("keys only", 0f, 0f, false, true, false);
    /** Co-pilot on Balanced: co-steer 150 deg/s up to 25 degrees, W withheld but never reversed, the tap stands. */
    static final Envelope GUIDE_ENV = new Envelope("co-pilot", 7.5f, 25f, false, true, false);
    /** Takeover on Balanced: 240 deg/s up to 90 degrees, every key. */
    static final Envelope CARRY_ENV = new Envelope("takeover", 12f, 90f, true, false, false);
    /** A player with a free mouse at the hard rotation cap (450 deg/s), every key. */
    static final Envelope PRO = new Envelope("free hand", 22.5f, 180f, true, false, false);
    /** Corrections: the takeoff is the user press at the lane trigger, the held W is never replaced, no yaw. */
    static final Envelope NUDGE_ENV = new Envelope("corrections", 0f, 0f, false, true, true);

    static final int GENES = 44;
    /** Corrections: corrected ticks per jump (AuthorshipBudget cap). */
    static final int STRAFE_BUDGET = 6;

    static final class Genome {
        final float[] dyaw = new float[GENES];
        final byte[] f = new byte[GENES];
        final byte[] s = new byte[GENES];
        int jump;

        void copy(Genome o) {
            System.arraycopy(o.dyaw, 0, dyaw, 0, GENES);
            System.arraycopy(o.f, 0, f, 0, GENES);
            System.arraycopy(o.s, 0, s, 0, GENES);
            jump = o.jump;
        }
    }

    static final class Search {
        final ShapeGrid g;
        final Lane lane;
        final Envelope env;
        final SupportRegion top = new SupportRegion();
        final double topY;
        final double cx, cz;
        final MovementPhysics start = new MovementPhysics();
        final MovementPhysics p = new MovementPhysics();
        double touchX, touchZ, touchSpeed;
        int evals;

        Search(Lane lane, Envelope env) {
            this.lane = lane;
            this.env = env;
            g = grid(lane);
            topY = top.topOf(g, lane.postX, FLOOR, lane.postZ);
            double b0 = top.cellTopBound(0), b1 = top.cellTopBound(1), b2 = top.cellTopBound(2),
                    b3 = top.cellTopBound(3);
            top.clear();
            top.setTop(topY);
            top.addTopBox(b0, b1, b2, b3);
            cx = 0.5 * (b0 + b2);
            cz = 0.5 * (b1 + b3);
            // the scripted run-up up to z ~ 4.5: W + sprint, the lane's start yaw
            start.set(lane.startX, FLOOR + 1, 0.5, 0, -0.0784, 0, true);
            while (start.z < 4.5) {
                start.tick(1, 0, lane.startYaw, true, false, false, g);
            }
        }

        /** 0 or less = landed on the top (minus the margin inside); otherwise the miss distance. */
        double eval(Genome q) {
            evals++;
            p.copyFrom(start);
            float yaw = lane.startYaw;
            int air = -1;
            int fills = 0;
            double best = 1e9;
            for (int t = 0; t < GENES; t++) {
                float step = Math.max(-env.degPerTick, Math.min(env.degPerTick, q.dyaw[t]));
                float ny = yaw + step;
                ny = Math.max(lane.startYaw - env.maxTotalDeg, Math.min(lane.startYaw + env.maxTotalDeg, ny));
                yaw = ny;
                int f = env.userTakeoff ? 1 : q.f[t];
                int s = q.s[t];
                if (!env.back && f < 0) {
                    f = 0;
                }
                boolean jump = air < 0 && p.onGround && (env.userTakeoff ? p.z >= 10.85 : t == q.jump);
                if (air >= 0) {
                    air++;
                    if (env.keepTap && lane.tapStrafe != 0 && (air == 6 || air == 7)) {
                        s = lane.tapStrafe; // the user's fresh tap is never replaced
                    }
                }
                if (env.userTakeoff && s != 0 && !(air == 6 || air == 7) && ++fills > STRAFE_BUDGET) {
                    s = 0; // Corrections: the authorship budget of corrected ticks per jump
                }
                p.tick(f, s, yaw, f > 0, jump, false, g);
                if (jump && !p.onGround) {
                    air = 0;
                }
                if (air < 0) {
                    if (p.y < FLOOR + 1 - 1e-6) {
                        return 50.0; // walked off
                    }
                    continue;
                }
                if (p.onGround) {
                    if (Math.abs(p.y - topY) < 1e-6 && top.contains(p.x, p.z)) {
                        touchX = p.x;
                        touchZ = p.z;
                        touchSpeed = Math.hypot(p.vx, p.vz);
                        return -top.margin(p.x, p.z);
                    }
                    return 20.0 + Math.hypot(p.x - cx, p.z - cz);
                }
                if (p.vy < 0 && p.y >= topY - 0.6) {
                    // miss measure: horizontal distance to the top plus the height still to lose
                    double d = Math.max(0.0, Math.max(Math.abs(p.x - cx), Math.abs(p.z - cz)) - 0.3)
                            + 0.5 * Math.abs(p.y - topY);
                    best = Math.min(best, d + 0.05);
                }
                if (p.y < topY - 0.6) {
                    break;
                }
            }
            return best >= 1e9 ? 60.0 : best;
        }

        void randomise(Genome q, java.util.Random rnd) {
            q.jump = 14 + rnd.nextInt(12);
            float turn = (rnd.nextFloat() * 2f - 1f) * env.maxTotalDeg;
            int turnAt = rnd.nextInt(GENES);
            int sOn = rnd.nextInt(GENES);
            int sLen = rnd.nextInt(12);
            byte sv = (byte) (rnd.nextInt(3) - 1);
            for (int t = 0; t < GENES; t++) {
                q.dyaw[t] = t >= turnAt && t < turnAt + 8 ? turn / 8f : 0f;
                q.f[t] = 1;
                q.s[t] = t >= sOn && t < sOn + sLen ? sv : 0;
            }
        }

        void mutate(Genome q, java.util.Random rnd) {
            int n = 1 + rnd.nextInt(3);
            for (int k = 0; k < n; k++) {
                int t = rnd.nextInt(GENES);
                int end = Math.min(GENES, t + 1 + rnd.nextInt(6));
                switch (rnd.nextInt(5)) {
                    case 0 -> {
                        float d = (float) (rnd.nextGaussian() * env.degPerTick * 0.6);
                        for (int i = t; i < end; i++) {
                            q.dyaw[i] = Math.max(-env.degPerTick, Math.min(env.degPerTick, q.dyaw[i] + d));
                        }
                    }
                    case 1 -> {
                        byte v = (byte) (rnd.nextInt(3) - 1);
                        for (int i = t; i < end; i++) {
                            q.f[i] = v;
                        }
                    }
                    case 2 -> {
                        byte v = (byte) (rnd.nextInt(3) - 1);
                        for (int i = t; i < end; i++) {
                            q.s[i] = v;
                        }
                    }
                    case 3 -> q.jump = Math.max(4, Math.min(GENES - 12, q.jump + rnd.nextInt(5) - 2));
                    default -> {
                        for (int i = t; i < end; i++) {
                            q.dyaw[i] = 0f;
                        }
                    }
                }
            }
        }

        final Genome winner = new Genome();

        /** The winner's run, one entry per tick from the jump - 6. */
        String describe() {
            StringBuilder sb = new StringBuilder();
            p.copyFrom(start);
            float yaw = lane.startYaw;
            int air = -1;
            for (int t = 0; t < GENES; t++) {
                float step = Math.max(-env.degPerTick, Math.min(env.degPerTick, winner.dyaw[t]));
                yaw = Math.max(lane.startYaw - env.maxTotalDeg, Math.min(lane.startYaw + env.maxTotalDeg, yaw + step));
                int f = !env.back && winner.f[t] < 0 ? 0 : winner.f[t];
                int s = winner.s[t];
                boolean jump = air < 0 && t == winner.jump && p.onGround;
                if (air >= 0) {
                    air++;
                    if (env.keepTap && lane.tapStrafe != 0 && (air == 6 || air == 7)) {
                        s = lane.tapStrafe;
                    }
                }
                sb.append(String.format("%n   t%d x%.2f z%.2f y%.2f yaw%.0f f%d s%d%s", t, p.x, p.z, p.y, yaw, f, s,
                        jump ? " J" : ""));
                p.tick(f, s, yaw, f > 0, jump, false, g);
                if (jump && !p.onGround) {
                    air = 0;
                }
                if (air > 0 && p.onGround) {
                    break;
                }
            }
            return sb.toString();
        }

        /** Best score over {@code restarts} hill climbs of {@code steps} mutations each. */
        double run(int restarts, int steps, long seed) {
            java.util.Random rnd = new java.util.Random(seed);
            Genome cur = new Genome();
            Genome cand = new Genome();
            double best = 1e9;
            for (int r = 0; r < restarts && best > 0.0; r++) {
                randomise(cur, rnd);
                double sc = eval(cur);
                for (int i = 0; i < steps && sc > 0.0; i++) {
                    cand.copy(cur);
                    mutate(cand, rnd);
                    double c = eval(cand);
                    if (c <= sc) {
                        sc = c;
                        cur.copy(cand);
                    }
                }
                if (sc < best) {
                    best = sc;
                    winner.copy(cur);
                }
            }
            return best;
        }
    }

    static String searchLine(Lane lane, Envelope env, int restarts, int steps) {
        Search s = new Search(lane, env);
        double sc = s.run(restarts, steps, 20261122L);
        return "SEARCH " + lane.name + " / " + env.name + ": " + (sc <= 0.0
                ? String.format("LANDS (touchdown x %.3f z %.3f, speed %.3f, margin %.3f, %d rolls)", s.touchX,
                s.touchZ, s.touchSpeed, -sc, s.evals)
                : String.format("no landing found, closest miss %.3f block (%d rolls)", sc, s.evals))
                + (System.getenv("LANE_PLAN") != null ? s.describe() : "");
    }

    /** The full hill-climb table of the run note (slow: a few seconds; printed only on request). */
    @Test
    void searchTable() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        Assumptions.assumeTrue(System.getenv("LANE_SEARCH") != null, "set LANE_SEARCH=1 to print the search table");
        for (Lane lane : new Lane[] {FENCE2, WALL3, PANE1, CORNER_FENCE, CORNER_WALL, CORNER_FENCE_THIN3,
                CORNER_FENCE_DEEP, CORNER_WALL_DEEP}) {
            for (Envelope env : new Envelope[] {NUDGE_ENV, KEYS, GUIDE_ENV, CARRY_ENV, PRO}) {
                System.out.println(searchLine(lane, env, 150, 1500));
            }
        }
    }

    /**
     * Why the Corrections lanes do not expect a landing: with the user's own press at the trigger and their W held
     * (Corrections never replaces a held key and never moves the user's press), no strafe fill lands the wall across
     * 3 or the pane across 1, even without the 6-tick authorship budget.
     */
    @Test
    void correctionsCannotLandTheWallOrThePaneFromThisRunUp() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (Lane lane : new Lane[] {WALL3, PANE1}) {
            Search s = new Search(lane, NUDGE_ENV);
            double miss = s.run(40, 500, 20261122L);
            assertTrue(miss > 0.25, lane.name + ": Corrections envelope landed it (" + miss + ")");
        }
        // the same lanes with the takeoff and W free (keys only) do land: the limit is the envelope, not the lane
        for (Lane lane : new Lane[] {WALL3, PANE1}) {
            assertTrue(new Search(lane, KEYS).run(60, 1500, 20261122L) <= 0.0, lane.name);
        }
    }

    /**
     * Why the corner lanes were rebuilt: across 3 behind a column two blocks deep, no clean corner technique (no wall
     * touch, one turn toward the target, strafe in behind the corner) lands the fence post in any envelope, and the
     * wall post only by 2 of 49392 shapes. The rebuilt lanes have thousands (fence across 2) and dozens (wall across 3).
     */
    @Test
    void deepCornersHaveNoCleanTechniqueAndTheRebuiltOnesDo() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        assertEquals(0, shapeScan(CORNER_FENCE_DEEP, 0f, 0f, 8.8));
        assertEquals(0, shapeScan(CORNER_FENCE_DEEP, 7.5f, 25f, 8.8));
        assertEquals(0, shapeScan(CORNER_FENCE_DEEP, 12f, 90f, 8.8));
        assertEquals(0, shapeScan(CORNER_WALL_DEEP, 0f, 0f, 8.8));
        assertTrue(shapeScan(CORNER_WALL_DEEP, 7.5f, 25f, 8.8) <= 2);
        assertEquals(0, shapeScan(CORNER_WALL_DEEP, 12f, 90f, 8.8));
        assertEquals(0, shapeScan(CORNER_FENCE_THIN3, 7.5f, 25f, 8.8));
        assertEquals(0, shapeScan(CORNER_FENCE_THIN3, 12f, 90f, 8.8));
        assertTrue(shapeScan(CORNER_FENCE, 7.5f, 25f, 8.8) > 1000);
        assertTrue(shapeScan(CORNER_FENCE, 12f, 90f, 8.8) > 1000);
        assertTrue(shapeScan(CORNER_WALL, 7.5f, 25f, 8.8) >= 50);
        assertTrue(shapeScan(CORNER_WALL, 12f, 90f, 8.8) >= 100);
    }

    /**
     * The corner-cut family of the technique library finds a script for the rebuilt corners within the ticks of the
     * approach (the target arms about 9 ticks before the edge), inside the budget of one call, inside the level's
     * view limits.
     */
    @Test
    void cornerCutScriptComesWithinTheApproach() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (Lane lane : new Lane[] {CORNER_FENCE, CORNER_WALL}) {
            for (float[] lv : new float[][] {{7.5f, 25f}, {12f, 90f}}) {
                for (double fromZ : new double[] {8.8, 10.0}) {
                    cut(lane, lv[0], lv[1], fromZ, 6);
                }
            }
        }
        // keys only (Legit): the wall post's wider top is reachable without a view change, the fence post is not
        cut(CORNER_WALL, 0f, 0f, 8.8, 2);
    }

    static int shapeScan(Lane lane, float rate, float maxTurn, double fromZ) {
        ShapeGrid g = grid(lane);
        SupportRegion top = new SupportRegion();
        double topY = top.topOf(g, lane.postX, FLOOR, lane.postZ);
        double b0 = top.cellTopBound(0), b1 = top.cellTopBound(1), b2 = top.cellTopBound(2), b3 = top.cellTopBound(3);
        top.clear();
        top.setTop(topY);
        top.addTopBox(b0, b1, b2, b3);
        MovementPhysics start = new MovementPhysics().set(lane.startX, FLOOR + 1, 0.5, 0, -0.0784, 0, true);
        while (start.z < fromZ) {
            start.tick(1, 0, lane.startYaw, true, false, false, g);
        }
        // plain edge tick
        MovementPhysics p = new MovementPhysics();
        MovementPhysics n = new MovementPhysics();
        p.copyFrom(start);
        int edge = 0;
        for (; edge < 20; edge++) {
            n.copyFrom(p).tick(1, 0, lane.startYaw, true, false, false, g);
            if (!n.supported(g)) {
                break;
            }
            p.copyFrom(n);
        }
        int found = 0;
        int rolls = 0;
        StringBuilder sample = new StringBuilder();
        float step = rate > 0f ? 5f : 1000f;
        for (int away = 0; away <= 6; away++) {
            for (int off = -1; off <= 1; off++) {
                for (float t0 = 0f; t0 <= maxTurn + 0.01f; t0 += step) {
                    for (float t1 = t0; t1 <= maxTurn + 0.01f; t1 += (maxTurn > 30f ? 10f : step)) {
                        for (int turnFrom = 0; turnFrom <= 1; turnFrom++) {
                            for (int straight = 0; straight <= 7; straight++) {
                                for (int release = 0; release <= 6; release++) {
                                    rolls++;
                                    p.copyFrom(start);
                                    float yaw = lane.startYaw;
                                    int jumpAt = edge + off;
                                    boolean hit = false;
                                    boolean landed = false;
                                    for (int i = 0; i < 44; i++) {
                                        int f = 1;
                                        int s = 0;
                                        float goal;
                                        if (i <= jumpAt) {
                                            s = i < away ? -1 : 0;
                                            goal = -t0;
                                        } else {
                                            int a = i - jumpAt - 1;
                                            goal = turnFrom == 0 || a >= straight ? -t1 : -t0;
                                            if (a >= straight) {
                                                s = 1;
                                                if (release > 0 && a >= straight + release) {
                                                    f = 0;
                                                }
                                            }
                                        }
                                        float d = Math.max(-rate, Math.min(rate, goal - yaw));
                                        yaw += d;
                                        p.tick(f, s, yaw, f > 0, i == jumpAt, false, g);
                                        if (p.horizontalCollision) {
                                            hit = true;
                                            break;
                                        }
                                        if (i > jumpAt && p.onGround) {
                                            landed = Math.abs(p.y - topY) < 1e-6 && top.contains(p.x, p.z);
                                            break;
                                        }
                                        if (p.y < FLOOR) {
                                            break;
                                        }
                                    }
                                    if (landed && !hit) {
                                        found++;
                                        if (sample.length() < 1500) {
                                            sample.append(String.format(
                                                    " [away%d off%+d t0 %.0f t1 %.0f from%d str%d rel%d]", away, off,
                                                    t0, t1, turnFrom, straight, release));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println(String.format("SEARCH shape %s rate %.1f max %.0f from z %.2f (edge tick %d): %d of %d land%s",
                lane.name, rate, maxTurn, start.z, edge, found, rolls,
                System.getenv("LANE_SEARCH") != null ? sample : ""));
        return found;
    }

    /**
     * Calls of the real library (600 replica ticks each) from one run-up state: a corner-cut script must come within
     * {@code maxCalls}, inside the per-call budget and the view limits.
     */
    static void cut(Lane lane, float rate, float maxTurn, double fromZ, int maxCalls) {
        ShapeGrid g = grid(lane);
        SupportRegion top = new SupportRegion();
        double topY = top.topOf(g, lane.postX, FLOOR, lane.postZ);
        double b0 = top.cellTopBound(0), b1 = top.cellTopBound(1), b2 = top.cellTopBound(2), b3 = top.cellTopBound(3);
        top.clear();
        top.setTop(topY);
        top.addTopBox(b0, b1, b2, b3);
        MovementPhysics p = new MovementPhysics().set(lane.startX, FLOOR + 1, 0.5, 0, -0.0784, 0, true);
        while (p.z < fromZ) {
            p.tick(1, 0, lane.startYaw, true, false, false, g);
        }
        TechniqueLibrary lib = new TechniqueLibrary();
        lib.setMaxTurn(maxTurn);
        lib.setLineBlocked(true);
        int first = -1;
        for (int call = 1; call <= maxCalls && first < 0; call++) {
            boolean got = lib.plan(p, lane.startYaw, true, 1, 0, top, rate > 0f, rate, false, 20f, g);
            assertTrue(lib.lastSimTicks() <= TechniqueSource.MAX_SIM_TICKS_PER_CALL, "call budget");
            if (got) {
                first = call;
            }
        }
        String id = String.format("%s rate %.1f max %.0f from z %.2f", lane.name, rate, maxTurn, p.z);
        assertTrue(first > 0, id + ": no script within " + maxCalls + " calls");
        FlightScript s = lib.script();
        assertEquals(TechniqueTemplates.NAME_CUT, s.name, id);
        float prev = lane.startYaw;
        for (int i = 0; i <= s.landIndex; i++) {
            assertTrue(Math.abs(s.yaw[i] - prev) <= rate + 1.0e-3f, id + ": view step at " + i);
            assertTrue(Math.abs(s.yaw[i] - lane.startYaw) <= maxTurn + 1.0e-3f, id + ": view turn at " + i);
            assertTrue(s.forward[i] >= 0, id + ": a corner cut never holds back");
            prev = s.yaw[i];
        }
        System.out.println(String.format("SEARCH cut %s: script at call %d, takeoff %d, land %d, margin %.3f, view %.0f",
                id, first, s.takeoffIndex, s.landIndex, s.margin, s.yaw[s.landIndex]));
    }
}
