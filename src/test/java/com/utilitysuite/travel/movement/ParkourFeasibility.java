package com.utilitysuite.travel.movement;

import java.util.List;

/** Brute force: can a perfect player clear this segment from a full sprint run-up? (any jump tick, any strafe) */
final class ParkourFeasibility {

    private ParkourFeasibility() {
    }

    static boolean clearable(ParkourCourse.Segment seg) {
        ParkourCourse c = new ParkourCourse(List.of(seg));
        for (int jumpTick = 1; jumpTick <= 60; jumpTick++) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                for (int steerFrom = 0; steerFrom <= 6; steerFrom += 2) {
                    if (attempt(c, jumpTick, strafe, steerFrom)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean attempt(ParkourCourse c, int jumpTick, int strafe, int steerFrom) {
        ParkourRun run = new ParkourRun(c);
        MovementPhysics p = new MovementPhysics().set(0.5, 1, 0.5, 0, -0.0784, 0, true);
        for (int t = 1; t <= 200; t++) {
            float yaw = run.aimYaw(p.x, p.z);
            boolean air = t > jumpTick;
            int s = air && t - jumpTick > steerFrom ? strafe : 0;
            p.tick(1, s, yaw, true, t == jumpTick, c);
            if (!run.update(t, p.x, p.y, p.z, p.onGround)) {
                return run.finished();
            }
            if (t > jumpTick + 3 && p.onGround) {
                return run.finished();
            }
        }
        return false;
    }
}
