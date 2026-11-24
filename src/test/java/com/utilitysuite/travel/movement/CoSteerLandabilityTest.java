package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live traces of 2026-11-23/24 (course J29 and J32): a jump that had already missed its block still asked for a
 * co-steer toward the block's centre, 100+ degrees off the view, while the body fell past it. The view is the user's
 * once the body can no longer land on the locked target: below its top and not rising, or with no air plan landing.
 */
class CoSteerLandabilityTest {

    /** The takeoff strip (z -8..0, top 20), 3 air, a pad at z 4..6 (top 20), a floor far below. */
    static ShapeGrid scene() {
        return new ShapeGrid().fill(0, 19, -8, 0, 19, 0).fill(-1, 19, 4, 1, 19, 6).fill(-8, 5, -12, 8, 5, 16);
    }

    static final double TOP = 20.0;

    /**
     * The user sprints off the strip and jumps for the pad. A few ticks into the flight the body is put where the jump
     * has missed (as after clipping a wall).
     *
     * @return the number of ticks a co-steer was asked for although the body could no longer land, or -1 when the
     *         flight never reached the miss with a live commitment
     */
    static int badRequests(JumpCommit.Mode mode, double missX, double missY, double missZ, double missVy) {
        ShapeGrid g = scene();
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        b.sprintWithholdAvailable = true;
        b.setKeyChangeLimits(6, 2, 0);
        boolean carry = mode == JumpCommit.Mode.CARRY;
        float steerRate = carry ? 12f : 7.5f;
        float steerMax = carry ? 90f : 25f;
        com.utilitysuite.core.input.YawCoSteer steer = new com.utilitysuite.core.input.YawCoSteer();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -5.0, 0, -0.0784, 0, true);
        float yaw = 0f;
        boolean sprint = true;
        boolean pressed = false;
        int airborne = -1;
        boolean missed = false;
        boolean liveAtMiss = false;
        int bad = 0;
        for (int t = 0; t < 90 && p.y > 12; t++) {
            boolean uj = !pressed && p.onGround && p.z >= 0.75;
            pressed |= uj;
            if (airborne >= 0 && t - airborne == 4 && !missed) {
                missed = true;
                liveAtMiss = b.commit().live();
                p.set(missX, missY, missZ, 0, missVy, 0, false);
            }
            b.setIntentTarget(0, 19, 4, 0.9);
            b.coSteerAllowed = true;
            b.coSteerMaxDegPerTick = steerRate;
            b.coSteerMaxTotalDeg = steerMax;
            if (!b.commit().live()) {
                steer.reset();
            }
            b.setCoSteerSpentDeg(steer.totalAddedDeg());
            b.decide(p, yaw, sprint, 1, 0, uj, 20f, g);
            boolean below = !p.onGround && p.vy <= 0.0 && p.y < TOP;
            if (missed && !p.onGround && b.wantsCoSteer() && (below || !b.planLands())) {
                bad++;
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            boolean jump = (uj && !b.withholdJump()) || b.jump();
            steer.beginTick();
            if (b.wantsCoSteer()) {
                steer.request(b.coSteerYaw(), steerRate * 20.0, Math.min(steerMax, b.coSteerTotalDeg()), 1.0);
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
        }
        return missed && liveAtMiss ? bad : -1;
    }

    @Test
    void noCoSteerTowardATargetTheBodyHasFallenBelow() {
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            // beside the pad's near face, under its top, falling: the target's centre is far off the view
            assertEquals(0, badRequests(mode, 1.9, 19.6, 3.5, -0.3), mode + ": co-steer asked for below the top");
        }
    }

    @Test
    void noCoSteerTowardATargetNoAirPlanLandsOn() {
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            // still above the top but stopped dead three blocks short and to the side: nothing lands from here
            assertEquals(0, badRequests(mode, 2.4, 20.6, 0.9, -0.4), mode + ": co-steer asked for with no landing");
        }
    }

    /** The brain counts the co-steer allowance from what the core has really added, not from where the view points. */
    @Test
    void theAllowanceLeftIsWhatTheCoreHasNotAddedYet() {
        MovementAssistBrain b = new MovementAssistBrain();
        b.coSteerMaxTotalDeg = 25f;
        assertEquals(25f, b.coSteerLeft(), 0f);
        b.setCoSteerSpentDeg(10.4); // e.g. 5.2 one way and 5.2 back: the view is where it started, 10.4 are spent
        assertEquals(14f, b.coSteerLeft(), 0f);
        b.setCoSteerSpentDeg(40.0);
        assertEquals(0f, b.coSteerLeft(), 0f);
        b.setCoSteerSpentDeg(Double.NaN);
        assertEquals(25f, b.coSteerLeft(), 0f, "an unusable read-out counts as nothing spent");
        b.setCoSteerSpentDeg(0.0);
        assertEquals(25f, b.coSteerLeft(), 0f, "a new grant: the allowance starts over");
        assertTrue(b.coSteerLeft() <= b.coSteerMaxTotalDeg);
    }
}
