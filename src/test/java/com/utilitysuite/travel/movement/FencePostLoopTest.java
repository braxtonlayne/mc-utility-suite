package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live trace of 2026-11-23, course J19 (2 air, +0.5, onto a lone fence post), Co-pilot: three falls off the post. Twice
 * the one in-flight trim moved the target to the pad behind the post (out of reach), the commitment aborted and the
 * body slid off the post with no brake and no hold; once the user glanced down at the post and turned the view 24
 * degrees, which ended the commitment the same way.
 */
class FencePostLoopTest {
    /** Prints a tick line per loop tick (set it in a debugger or a one-off test). */
    static boolean debug;
    /** Flight ticks of the last {@link #loop} where the commitment did not own the landing (block assist could start). */
    static int flightNotOwned;
    /**
     * The scripted user of the first version of this harness: the one jump press waits for a ground tick past the
     * edge mark. When the assist takes off before that mark (the approach takes off a tick earlier under a planned
     * reaction delay of 3 or 4 ticks), that press comes on the touchdown tick on the post: a jump off the post, the
     * user's own, which the landing hold never stops. Off by default; only
     * {@link #aJumpPressOnThePostIsTheUsersJumpNotASlide} sets it.
     */
    static boolean pressWaitsForGround;

    /** The takeoff strip (z -8..0), 2 air, a fence post at z 3 (top 20.5), 4 air, a pad at z 8..10. */
    static ShapeGrid scene() {
        return new ShapeGrid().fill(0, 19, -8, 0, 19, 0).fencePost(0, 19, 3).fill(-1, 19, 8, 1, 19, 10)
                .fill(-8, 5, -12, 8, 5, 16);
    }

    /**
     * The whole loop as the module runs it: the user holds W + sprint along the strip, presses jump at the edge and,
     * with {@code glance}, looks down at the post and turns the view from 3 ticks after the takeoff.
     *
     * @return "held" when the body stands on the post 10 ticks after its touchdown, else what happened
     */
    static String loop(JumpCommit.Mode mode, double startX, double startZ, float pitch, int glance) {
        ShapeGrid g = scene();
        SupportRegion post = TechniqueLibraryTest.top(g, 0, 19, 3);
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        b.sprintWithholdAvailable = true;
        b.setTechniques(new TechniqueLibrary());
        LevelUnderTest.apply(b);
        IntentEstimator intent = new IntentEstimator();
        boolean carry = mode == JumpCommit.Mode.CARRY;
        float steerRate = LevelUnderTest.steerRate(carry ? 12f : 7.5f);
        float steerMax = carry ? 90f : 25f;
        com.utilitysuite.core.input.YawCoSteer steer = new com.utilitysuite.core.input.YawCoSteer();
        MovementPhysics p = new MovementPhysics().set(startX, 20, startZ, 0, -0.0784, 0, true);
        float yaw = 0f;
        boolean sprint = true;
        boolean pressed = false;
        int airborne = -1;
        int onPost = 0;
        boolean aborted = false;
        flightNotOwned = 0;
        for (int t = 0; t < 90; t++) {
            // the planned press comes where the finger planned it, also when the assist already took the jump (as in
            // ParkourLaneReplicaTest.roll): in the air it does nothing
            boolean uj = !pressed && p.z >= 0.75 && (p.onGround || !pressWaitsForGround);
            pressed |= uj;
            boolean pressOnPost = uj && onPost > 0;
            if (glance != 0 && airborne >= 0 && t - airborne >= 3 && t - airborne <= 10) {
                pitch = Math.min(80f, pitch + 9f);
                yaw += 3f * glance; // 24 degrees over 8 ticks
            }
            intent.setCommitThreshold(carry ? IntentEstimator.CARRY_THRESHOLD : IntentEstimator.GUIDE_THRESHOLD);
            b.intentThreshold = intent.commitThreshold();
            if (intent.estimate(p, yaw, pitch, 1, 0, sprint, g) && intent.hasTarget()) {
                b.setIntentBlocked(intent.targetBlocked());
                b.setIntentLook(intent.lookConfidence());
                b.setIntentTarget(intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence());
            } else {
                b.clearIntentTarget();
            }
            b.coSteerAllowed = steerRate > 0f;
            b.coSteerMaxDegPerTick = steerRate;
            b.coSteerMaxTotalDeg = steerMax;
            if (!b.commit().live()) {
                steer.reset();
            }
            b.setCoSteerSpentDeg(steer.totalAddedDeg()); // as the module does from its grant
            b.decide(p, yaw, sprint, 1, 0, uj, 20f, g);
            LevelUnderTest.afterDecide(b, p.onGround, 1, 0);
            aborted |= b.commit().phase() == JumpCommit.Phase.ABORT;
            if (b.commit().phase() == JumpCommit.Phase.FLIGHT && !b.commitOwnsLanding()) {
                flightNotOwned++;
            }
            if (debug) {
                System.out.printf("FDBG %d x%.2f y%.2f z%.2f yaw%.1f p%.0f %s tgt%s lands%s narrow%s f%s s%s%n", t, p.x, p.y, p.z, yaw, pitch,
                        b.commit().phase(), b.intentActive() ? b.targetZ() : "-", b.planLands(), b.commit().narrowTarget, b.forward(), b.strafe());
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            boolean jump = (uj && !b.withholdJump()) || b.jump();
            steer.beginTick();
            if (b.wantsCoSteer() && steerRate > 0f) {
                steer.request(b.coSteerYaw(), steerRate * 20.0, Math.min(steerMax, b.coSteerTotalDeg()), b.commit().authority());
            }
            sprint = (sprint || b.sprint()) && !b.sprintOff() && f > 0;
            boolean wasGround = p.onGround;
            p.tick(f, s, yaw, sprint, jump, b.sneak(), g);
            if (wasGround && !p.onGround && airborne < 0) {
                airborne = t;
            }
            for (int k = 0; k < 3; k++) {
                yaw += (float) steer.onFrame(yaw, 0.0, 0.0, 0.05 / 3, false);
            }
            if (p.onGround && Math.abs(p.y - post.top()) < 1e-6 && post.contains(p.x, p.z)) {
                if (++onPost >= 10) {
                    return aborted ? "held after an abort" : "held";
                }
            } else if (onPost > 0) {
                if (pressOnPost && jump) {
                    return "jumped off the post (the user's press)";
                }
                return String.format("slid off the post at x%.2f z%.2f (%s)", p.x, p.z, b.commit().phase());
            }
            if (p.y < 18) {
                return String.format("fell at x%.2f z%.2f (%s)", p.x, p.z, b.commit().phase());
            }
        }
        return String.format("stuck at x%.2f y%.2f z%.2f", p.x, p.y, p.z);
    }

    @Test
    void coPilotHoldsTheFencePostWhetherTheUserGlancesDownOrNot() {
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            for (int glance : new int[] {0, -1, 1}) {
                int held = 0;
                int tried = 0;
                StringBuilder out = new StringBuilder();
                for (double x = 0.3; x <= 0.71; x += 0.2) {
                    for (double z = -6.0; z <= -4.9; z += 0.5) {
                        for (float pitch : new float[] {10f, 25f}) {
                            tried++;
                            String r = loop(mode, x, z, pitch, glance);
                            if (r.equals("held")) {
                                held++;
                            } else {
                                out.append(String.format(" [x%.1f z%.1f p%.0f: %s]", x, z, pitch, r));
                            }
                        }
                    }
                }
                System.out.println("FENCE LOOP " + mode + (glance != 0 ? " glance " + glance + " " : " ") + held + "/" + tried + out);
                assertTrue(held * 10 >= tried * 9, mode + (glance != 0 ? " glance " + glance + ": " : ": ") + held + " of " + tried + out);
            }
        }
    }

    /**
     * What looked like Co-pilot sliding off the post at the LEGIT key limits with a reaction delay of 3 or 4 ticks
     * (12 of 18 runs) was the scripted user's press: the assist took off before the press mark, the press came on the
     * touchdown tick, and a jump key down at the touchdown is the user's next jump (no hold, by design: manual input
     * wins). With the press where the finger planned it (in the air, where it does nothing) the same runs hold.
     */
    @Test
    void aJumpPressOnThePostIsTheUsersJumpNotASlide() {
        for (int reaction = 3; reaction <= 4; reaction++) {
            LevelUnderTest.legit(reaction);
            try {
                int jumped = 0;
                for (double x = 0.3; x <= 0.71; x += 0.2) {
                    for (double z = -6.0; z <= -4.9; z += 0.5) {
                        for (float pitch : new float[] {10f, 25f}) {
                            pressWaitsForGround = true;
                            String late = loop(JumpCommit.Mode.GUIDE, x, z, pitch, 0);
                            pressWaitsForGround = false;
                            String planned = loop(JumpCommit.Mode.GUIDE, x, z, pitch, 0);
                            String at = String.format("reaction %d x%.1f z%.1f p%.0f", reaction, x, z, pitch);
                            assertTrue(late.equals("held") || late.startsWith("jumped off the post"),
                                    at + ", press on a ground tick: " + late);
                            assertTrue(planned.equals("held"), at + ", press at the edge mark: " + planned);
                            if (!late.equals("held")) {
                                jumped++;
                            }
                        }
                    }
                }
                System.out.println("FENCE LOOP legit reaction " + reaction + ", press waits for the ground: " + jumped
                        + "/18 presses came on the post and jumped off it");
                assertTrue(jumped > 0, "reaction " + reaction + ": no press came on the post");
            } finally {
                pressWaitsForGround = false;
                LevelUnderTest.balanced();
            }
        }
    }

    /**
     * Live trace of 2026-11-23, J19 reverse: the block assist's planner (the user's raw W) saw an overshoot and turned
     * the camera down to place a block, and its key rotation dropped the brain's brake. A landing commitment owns
     * every flight tick, so no placement starts on top of it.
     */
    @Test
    void aLandingCommitmentOwnsEveryFlightTickSoNoBlockPlacementStarts() {
        StringBuilder out = new StringBuilder();
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            for (double z = -6.0; z <= -4.9; z += 0.5) {
                String r = loop(mode, 0.5, z, 21f, 0);
                if (r.equals("held") && flightNotOwned > 0) {
                    out.append(String.format(" [%s z%.1f: %d ticks]", mode, z, flightNotOwned));
                }
            }
        }
        assertTrue(out.length() == 0, "flight ticks not owned:" + out);
    }
}
