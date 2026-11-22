package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.SupportRegionTest.ShapeGrid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approach and takeoff control ({@link ApproachController} in {@link MovementAssistBrain}): the in-game lane case. A
 * player sprints at a narrow top with W + sprint held throughout and presses jump for one tick on the ground 0.15
 * before the edge (or never, and the assist takes the jump). In GUIDE and CARRY the body must land on the real top
 * and still be there 40 ticks later with W held; NUDGE may miss, but a landing it makes must hold.
 */
@Tag("needs-shape-physics")
class ApproachControlTest {

    static final int STAY_TICKS = 40;
    /** Run-up edge (the last run-up block is z = 1) and the lane's jump trigger 0.15 before it. */
    static final double EDGE_Z = 2.0;
    static final double TRIGGER_Z = EDGE_Z - 0.15;

    /** Run-up floor at y = 19 for z <= 1, a target block {@code gap} blocks past the edge, a floor far below. */
    static ShapeGrid course(float[] target, int gap) {
        ShapeGrid g = new ShapeGrid().fill(-3, 19, -12, 3, 19, 1, ShapeGrid.FULL).put(0, 19, 2 + gap, target);
        g.fill(-6, 5, -12, 6, 5, 14, ShapeGrid.FULL);
        return g;
    }

    /** Outcome of one roll. */
    static final class Run {
        int stayed = -1; // ticks on top after landing (STAY_TICKS = held), -1 never landed
        int maxCommitSim;
        boolean userPressed;
        double jumpZ = Double.NaN;
        double maxZ = -1e9;
        String trace = "";
        boolean aborted;
        int pressTick = -1;
        int takeoffTick = -1;
    }

    static MovementAssistBrain brain(JumpCommit.Mode mode, boolean walk) {
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        b.sprintWithholdAvailable = walk;
        return b;
    }

    /**
     * @param userJumps the user presses jump for one tick at the trigger; otherwise the assist takes the jump
     */
    static Run roll(MovementAssistBrain b, ShapeGrid g, int gap, boolean userJumps) {
        int tz = 2 + gap;
        // the target's own top only: a pane level with the run-up must not count the platform edge as the target
        SupportRegion top = new SupportRegion();
        double topY = top.topOf(g, 0, 19, tz);
        double b0 = top.cellTopBound(0), b1 = top.cellTopBound(1), b2 = top.cellTopBound(2), b3 = top.cellTopBound(3);
        top.clear();
        top.setTop(topY);
        top.addTopBox(b0, b1, b2, b3);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        Run r = new Run();
        StringBuilder tr = new StringBuilder();
        int landedAt = -1;
        float yaw = 0f;
        boolean wasGround = true;
        for (int t = 0; t < 400; t++) {
            double dx = 0.5 - p.x;
            double dz = tz + 0.5 - p.z;
            if (dx * dx + dz * dz > 1.0) {
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            // the lane script: one press on the run-up (never again after any takeoff, the assist's included)
            boolean uj = userJumps && !r.userPressed && Double.isNaN(r.jumpZ) && p.onGround && p.z >= TRIGGER_Z;
            r.userPressed |= uj;
            if (uj) {
                r.pressTick = t;
            }
            b.setIntentTarget(0, 19, tz, 0.9);
            b.decide(p, yaw, p.sprinting, 1, 0, uj, 20f, g);
            r.maxCommitSim = Math.max(r.maxCommitSim, b.lastCommitSimTicks());
            r.aborted |= b.commit().phase() == JumpCommit.Phase.ABORT;
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            boolean jump = (uj && !b.withholdJump()) || b.jump();
            if (tr.length() < 3000 && p.z > 1.0 && (landedAt < 0 || t - landedAt < 4) && p.y > 17) {
                ApproachController a = b.approach();
                tr.append(String.format("[%d z%.2f y%.2f f%d%s%s%s %s e%s c%d tt%d n%d s%d fm%.2f]", t, p.z, p.y, f,
                        jump ? " J" : "", b.withholdJump() ? " W" : "", b.sprintOff() ? " S0" : "",
                        b.commit().phase(), a.engaged() ? "1" : "0", a.planCost(), a.ticksToTakeoff(), a.evaluated(),
                        a.simTicks(), b.flight().found() ? b.flight().bestMargin() : -9.0) + (b.sneak() ? " SN" : "") + " L" + b.hold().latched() + " g" + p.onGround + String.format(" x%.2f vz%.3f", p.x, p.vz));
            }
            p.tick(f, s, yaw, !b.sprintOff(), jump, b.sneak(), g);
            if (wasGround && !p.onGround && Double.isNaN(r.jumpZ)) {
                r.jumpZ = p.z;
                r.takeoffTick = t;
            }
            wasGround = p.onGround;
            r.maxZ = Math.max(r.maxZ, p.z);
            boolean onTop = p.onGround && Math.abs(p.y - topY) < 1e-6 && top.contains(p.x, p.z);
            if (landedAt < 0) {
                if (onTop) {
                    landedAt = t;
                } else if (p.y < 15) {
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
        if (landedAt >= 0 && r.stayed == -1) {
            r.stayed = STAY_TICKS;
        }
        r.trace = String.format("jumpZ %.2f maxZ %.2f %s", r.jumpZ, r.maxZ, tr);
        return r;
    }

    static boolean shapes() {
        return LandingJointTest.replicaHonoursShapes();
    }

    private static final Object[][] TARGETS = {
            {ShapeGrid.FENCE_POST, "fence", new int[]{1, 2, 3}},
            {ShapeGrid.WALL_POST, "wall", new int[]{1, 2, 3}},
            {ShapeGrid.PANE_POST, "pane", new int[]{1, 2}},
    };

    private static void matrix(boolean userJumps, boolean walk) {
        matrix(userJumps, walk, true);
    }

    private static void matrix(boolean userJumps, boolean walk, boolean edgeJump) {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        for (Object[] tg : TARGETS) {
            float[] shape = (float[]) tg[0];
            for (int gap : (int[]) tg[2]) {
                for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
                    MovementAssistBrain b = brain(mode, walk);
                    b.edgeJump = edgeJump;
                    Run r = roll(b, course(shape, gap), gap, userJumps);
                    String id = tg[1] + " gap " + gap + " " + mode + (walk ? " walk" : "")
                            + (edgeJump ? "" : " no-edge-jump");
                    if (userJumps && !edgeJump && !r.userPressed) {
                        fails.append(id).append(": the user's press never came\n");
                    } else if (userJumps && !edgeJump
                            && r.takeoffTick - r.pressTick > ApproachController.MAX_WITHHOLD_TICKS) {
                        fails.append(id).append(": the press was held back ").append(r.takeoffTick - r.pressTick)
                                .append(" ticks\n");
                    }
                    maxSimSeen = Math.max(maxSimSeen, r.maxCommitSim);
                    if (r.maxCommitSim > MovementAssistBrain.MAX_COMMIT_SIM_TICKS) {
                        fails.append(id).append(": commit sim ").append(r.maxCommitSim).append('\n');
                    }
                    if (mode == JumpCommit.Mode.NUDGE) {
                        if (r.stayed != -1 && r.stayed != STAY_TICKS) {
                            fails.append(id).append(": walked off after ").append(r.stayed).append('\n');
                        }
                        continue;
                    }
                    if (r.stayed != STAY_TICKS) {
                        fails.append(id).append(": stayed ").append(r.stayed).append(' ').append(r.trace)
                                .append('\n');
                    }
                }
            }
        }
        assertTrue(fails.length() == 0, "\n" + fails);
        System.out.println("approach matrix user=" + userJumps + " walk=" + walk + " edge=" + edgeJump
                + ": max commit sim ticks per decision " + maxSimSeen);
    }

    /** Largest {@link MovementAssistBrain#lastCommitSimTicks()} seen by the matrices (printed for the notes). */
    static int maxSimSeen;

    @Test
    void userJumpAtTheTriggerKeysOnly() {
        matrix(true, false);
    }

    @Test
    void userJumpAtTheTriggerWithSprintWithhold() {
        matrix(true, true);
    }

    /** Edge jump off: the takeoff is the user's own press, which the assist may only hold back a few ticks. */
    @Test
    void userJumpOnlyKeysOnly() {
        matrix(true, false, false);
    }

    @Test
    void userJumpOnlyWithSprintWithhold() {
        matrix(true, true, false);
    }

    @Test
    void assistJumpKeysOnly() {
        matrix(false, false);
    }

    @Test
    void assistJumpWithSprintWithhold() {
        matrix(false, true);
    }

    // ---- manual input wins -------------------------------------------------------------------------------------------

    private static final JumpCommit.Mode[] COMMITTED = {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY};

    /** Releasing W on the run-up (far out, and inside the takeoff window) ends everything: no jump, no key writes. */
    @Test
    void releasingWOnTheRunUpProducesNoJumpAndNoKeyWrites() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (double releaseZ : new double[]{-2.0, 1.2, 1.6}) {
            for (JumpCommit.Mode mode : COMMITTED) {
                for (boolean walk : new boolean[]{false, true}) {
                    ShapeGrid g = course(ShapeGrid.PANE_POST, 1);
                    MovementAssistBrain b = brain(mode, walk);
                    MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
                    boolean released = false;
                    String id = mode + " release at " + releaseZ + (walk ? " walk" : "");
                    boolean tookOffFirst = false;
                    for (int t = 0; t < 120; t++) {
                        if (!released && p.z >= releaseZ) {
                            if (!p.onGround) {
                                tookOffFirst = true; // the jump came before the release: not this test's case
                                break;
                            }
                            released = true;
                        }
                        int uf = released ? 0 : 1;
                        b.setIntentTarget(0, 19, 3, 0.9);
                        b.decide(p, 0f, p.sprinting, uf, 0, false, 20f, g);
                        if (released) {
                            assertFalse(b.jump(), id + ": jump at tick " + t);
                            assertEquals(MovementAssistBrain.KEEP, b.forward(), id + ": forward written");
                            assertEquals(MovementAssistBrain.KEEP, b.strafe(), id + ": strafe written");
                            assertFalse(b.sprintOff(), id + ": sprint withheld");
                            assertFalse(b.sprint(), id + ": sprint engaged");
                            assertFalse(b.withholdJump(), id + ": jump withheld");
                        }
                        int f = b.forward() == MovementAssistBrain.KEEP ? uf : b.forward();
                        int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                        p.tick(f, s, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
                        if (released) {
                            assertTrue(p.onGround, id + ": left the ground after W was released");
                        }
                    }
                    assertTrue(released || (tookOffFirst && releaseZ > 1.5), id + ": never released");
                }
            }
        }
    }

    /** Rolls the run-up with W + sprint held until the approach controller owns the takeoff (a plan or a press). */
    private static MovementPhysics runUntilOwned(MovementAssistBrain b, ShapeGrid g, int tz) {
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        for (int t = 0; t < 80; t++) {
            b.setIntentTarget(0, 19, tz, 0.9);
            b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
            if (b.approach().owns()) {
                return p;
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            p.tick(f, 0, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
        }
        throw new AssertionError("the approach never took the takeoff");
    }

    @Test
    void userSneakOnTheRunUpEndsTheApproachAtOnce() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (JumpCommit.Mode mode : COMMITTED) {
            ShapeGrid g = course(ShapeGrid.PANE_POST, 1);
            MovementAssistBrain b = brain(mode, false);
            MovementPhysics p = runUntilOwned(b, g, 3);
            b.noteUserSneak();
            b.setIntentTarget(0, 19, 3, 0.9);
            b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
            assertTrue(b.commit().phase() != JumpCommit.Phase.ARMED, mode + ": still armed after sneak");
            assertFalse(b.approach().owns(), mode + ": the approach still owns the takeoff");
            assertFalse(b.sprintOff(), mode.name());
            assertEquals(MovementAssistBrain.KEEP, b.forward(), mode.name());
        }
    }

    @Test
    void aHardTurnInFlightAbortsAsBefore() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (JumpCommit.Mode mode : COMMITTED) {
            ShapeGrid g = course(ShapeGrid.FENCE_POST, 2);
            MovementAssistBrain b = brain(mode, false);
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
            float yaw = 0f;
            int air = 0;
            boolean aborted = false;
            for (int t = 0; t < 120 && p.y > 15; t++) {
                double rate = 0.0;
                if (!p.onGround && ++air == 3) {
                    yaw += 40f; // a hard turn of the hand in one tick
                    rate = 40.0;
                }
                b.setYawRate(rate);
                b.setIntentTarget(0, 19, 4, 0.9);
                b.decide(p, yaw, p.sprinting, 1, 0, false, 20f, g);
                if (air == 3) {
                    aborted = b.commit().phase() == JumpCommit.Phase.ABORT;
                    break;
                }
                int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                p.tick(f, 0, yaw, !b.sprintOff(), b.jump(), b.sneak(), g);
            }
            assertTrue(aborted, mode + ": a 40 degree turn in one tick is a hard override");
        }
    }

    /**
     * LEGIT-style limits (one key change per jump, reaction delay): a plan that needs an eased run-up and an air brake
     * is not offered, so an eased takeoff is never followed by a brake in the flight.
     */
    @Test
    void perLevelLimitsBoundTheWholePlan() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (Object[] tg : TARGETS) {
            for (int gap : (int[]) tg[2]) {
                for (JumpCommit.Mode mode : COMMITTED) {
                    MovementAssistBrain b = brain(mode, false);
                    b.edgeJump = false;
                    b.setKeyChangeLimits(1, 2, 3);
                    ShapeGrid g = course((float[]) tg[0], gap);
                    MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
                    boolean pressed = false;
                    boolean easedTakeoff = false;
                    boolean airChange = false;
                    for (int t = 0; t < 120 && p.y > 15; t++) {
                        boolean uj = !pressed && p.onGround && p.z >= TRIGGER_Z;
                        pressed |= uj;
                        b.setIntentTarget(0, 19, 2 + gap, 0.9);
                        b.decide(p, 0f, p.sprinting, 1, 0, uj, 20f, g);
                        boolean jump = (uj && !b.withholdJump()) || b.jump();
                        if (p.onGround && jump) {
                            easedTakeoff = b.approach().planKind() != ApproachController.KIND_NONE
                                    && b.forward() == 0;
                        }
                        if (!p.onGround && b.commit().driving() && b.forward() != MovementAssistBrain.KEEP) {
                            airChange = true;
                        }
                        int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                        p.tick(f, 0, 0f, !b.sprintOff(), jump, b.sneak(), g);
                        if (p.onGround && pressed && p.z > EDGE_Z + 0.3) {
                            break;
                        }
                    }
                    assertFalse(easedTakeoff && airChange,
                            tg[1] + " gap " + gap + " " + mode + ": an eased takeoff and an air brake under one change");
                }
            }
        }
    }

    // ---- target freeze -------------------------------------------------------------------------------------------------

    @Test
    void theTargetIsFrozenFromTakeoffWithOneTrimBeforeTheApex() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (boolean beforeApex : new boolean[]{true, false}) {
            ShapeGrid g = course(ShapeGrid.FENCE_POST, 2).put(0, 19, 6, ShapeGrid.FENCE_POST);
            MovementAssistBrain b = brain(JumpCommit.Mode.GUIDE, false);
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
            int air = 0;
            int checked = 0;
            for (int t = 0; t < 120 && p.y > 15 && checked < 2; t++) {
                boolean airborne = !p.onGround;
                if (airborne) {
                    air++;
                }
                boolean sweep = airborne && checked == 0 && (beforeApex ? air == 2 : p.vy < 0);
                boolean back = airborne && checked == 1;
                // the look sweeps to the far post once; the next tick it sweeps back (before the apex) or stays there
                int lookZ = sweep ? 6 : back ? (beforeApex ? 4 : 6) : 4;
                b.setIntentTarget(0, 19, lookZ, 0.9);
                b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
                if (sweep) {
                    assertTrue(b.targetFrozen(), "frozen in flight");
                    assertEquals(beforeApex ? 6 : 4, b.targetZ(), beforeApex
                            ? "one trim before the apex follows the look" : "after the apex the target stays");
                    checked = 1;
                } else if (back) {
                    // a second change in the same flight is ignored
                    assertEquals(beforeApex ? 6 : 4, b.targetZ(), "only one trim per flight");
                    checked = 2;
                }
                int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                p.tick(f, 0, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
            }
            assertEquals(2, checked, "the flight was observed");
        }
    }

    // ---- co-steer -------------------------------------------------------------------------------------------------------

    @Test
    void coSteerOnlyWhenAllowedAndWithinThirtyDegreesOfTravel() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (boolean allowed : new boolean[]{false, true}) {
            ShapeGrid g = new ShapeGrid().fill(-3, 19, -12, 3, 19, 1, ShapeGrid.FULL).put(1, 19, 4,
                    ShapeGrid.FENCE_POST);
            g.fill(-6, 5, -12, 6, 5, 14, ShapeGrid.FULL);
            MovementAssistBrain b = brain(JumpCommit.Mode.GUIDE, false);
            b.coSteerAllowed = allowed;
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
            int asked = 0;
            for (int t = 0; t < 80 && p.y > 15; t++) {
                b.setIntentTarget(1, 19, 4, 0.9);
                b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
                if (b.wantsCoSteer()) {
                    asked++;
                    double h2 = p.vx * p.vx + p.vz * p.vz;
                    float ref = h2 > 1.0e-4 ? (float) Math.toDegrees(Math.atan2(-p.vx, p.vz)) : 0f;
                    float d = b.coSteerYaw() - ref;
                    d = ((d % 360f) + 540f) % 360f - 180f;
                    assertTrue(Math.abs(d) <= MovementAssistBrain.NATURAL_CLAMP_DEG + 1e-3f,
                            "co-steer " + b.coSteerYaw() + " is " + d + " off the travel direction");
                }
                int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                p.tick(f, s, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
            }
            if (allowed) {
                assertTrue(asked > 0, "an off-line target asks the co-steer for the takeoff yaw");
            } else {
                assertEquals(0, asked, "never without coSteerAllowed");
            }
        }
    }

    // ---- technique scripts ------------------------------------------------------------------------------------------

    /** A technique source that "finds" a fixed key pattern and rolls the replica for its predicted line. */
    static final class FakeTechniques implements TechniqueSource {
        final FlightScript s = new FlightScript();
        final MovementPhysics m = new MovementPhysics();
        int calls;
        boolean usesYaw;
        static final int SIMS = 60;

        @Override
        public boolean plan(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                            SupportRegion target, boolean yawAllowed, float maxYawDegPerTick, boolean safeOnly,
                            float health, MovementPhysics.Voxels w) {
            calls++;
            s.clear();
            s.name = "probe";
            s.usesYaw = usesYaw;
            m.copyFrom(now);
            s.length = 10;
            for (int i = 0; i < s.length; i++) {
                s.forward[i] = 1;
                s.strafe[i] = (byte) (i < 3 ? 1 : 0);
                s.sprint[i] = true;
                s.yaw[i] = yaw + (usesYaw ? i : 0);
                m.tick(1, s.strafe[i], s.yaw[i], true, false, w);
                s.x[i] = m.x;
                s.y[i] = m.y;
                s.z[i] = m.z;
            }
            return true;
        }

        @Override
        public FlightScript script() {
            return s;
        }

        @Override
        public int lastSimTicks() {
            return SIMS;
        }

        @Override
        public void reset() {
        }
    }

    /** A pane five blocks out: nothing in the approach family lands, so the technique source is asked. */
    @Test
    void aTechniqueScriptIsReplayedAndDroppedOffItsLine() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        for (JumpCommit.Mode mode : COMMITTED) {
            ShapeGrid g = course(ShapeGrid.PANE_POST, 5);
            MovementAssistBrain b = brain(mode, false);
            FakeTechniques fake = new FakeTechniques();
            b.setTechniques(fake);
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
            int replayed = 0;
            boolean dropped = false;
            for (int t = 0; t < 80 && !dropped; t++) {
                if (replayed == 5) {
                    p.x += 0.2; // pushed off the predicted line
                }
                b.setIntentTarget(0, 19, 7, 0.9);
                b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
                if (replayed == 5) {
                    dropped = !b.scriptActive();
                    break;
                }
                if (b.scriptActive()) {
                    int i = b.scriptIndex();
                    assertEquals(replayed, i, mode + ": script index");
                    if (i == 0) {
                        assertTrue(b.lastCommitSimTicks() >= FakeTechniques.SIMS,
                                mode + ": the source's ticks count into the commit budget");
                        assertTrue(b.lastCommitSimTicks() <= MovementAssistBrain.MAX_COMMIT_SIM_TICKS, mode.name());
                    }
                    assertEquals(i < 3 ? 1 : MovementAssistBrain.KEEP, b.strafe(), mode + ": script strafe at " + i);
                    assertEquals(MovementAssistBrain.KEEP, b.forward(), mode + ": forward is the user's own");
                    replayed++;
                }
                int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                p.tick(f, s, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
            }
            assertTrue(fake.calls > 0, mode + ": the source was asked");
            assertEquals(5, replayed, mode + ": replayed on its line");
            assertTrue(dropped, mode + ": dropped once the body left the predicted line");
        }
    }

    @Test
    void aScriptThatTurnsTheViewIsSkippedWithoutCoSteer() {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        ShapeGrid g = course(ShapeGrid.PANE_POST, 5);
        MovementAssistBrain b = brain(JumpCommit.Mode.CARRY, false);
        FakeTechniques fake = new FakeTechniques();
        fake.usesYaw = true;
        b.setTechniques(fake);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        for (int t = 0; t < 40; t++) {
            b.setIntentTarget(0, 19, 7, 0.9);
            b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
            assertFalse(b.scriptActive(), "a yaw script without co-steer");
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            p.tick(f, 0, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
        }
        assertTrue(fake.calls > 0, "the source was asked");
    }
}
