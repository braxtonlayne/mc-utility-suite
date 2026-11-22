package com.utilitysuite.combat.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AimSolverLeadTest {

    private static CombatSnapshot target() {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0.3;
        s.pz = -0.4;
        s.eyeY = 65.62;
        s.tx = 1.5;
        s.ty = 64;
        s.tz = 3.2;
        s.minX = s.tx - 0.3;
        s.maxX = s.tx + 0.3;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = s.tz - 0.3;
        s.maxZ = s.tz + 0.3;
        s.tvx = 0.18;
        s.tvy = -0.08;
        s.tvz = -0.05;
        return s;
    }

    @Test
    void offsetOverloadEqualsTheVelocityLead() {
        CombatSnapshot s = target();
        float[] a = new float[2];
        float[] b = new float[2];
        for (double lead = 0; lead <= 5; lead += 0.5) {
            AimSolver.solve(s, lead, a);
            AimSolver.solve(s, s.tvx * lead, s.tvy * lead, s.tvz * lead, b);
            assertEquals(a[0], b[0], 0.0, "yaw at lead " + lead);
            assertEquals(a[1], b[1], 0.0, "pitch at lead " + lead);
        }
    }

    @Test
    void neededYawRateIsTheRelativeLateralSpeedOverDistance() {
        CombatSnapshot s = new CombatSnapshot();
        s.tz = 3;
        s.tvx = 0.2; // target moving +x; facing +z our right is -x, so this is to our left
        assertEquals(Math.toDegrees(0.2 / 3), AimSolver.neededYawRateDegPerTick(s, 0.0), 1e-9);
        assertEquals(0.0, AimSolver.neededYawRateDegPerTick(s, -0.2), 1e-9); // we move left with it
        assertEquals(Math.toDegrees(0.4 / 3), AimSolver.neededYawRateDegPerTick(s, 0.2), 1e-9);
        s.tvx = 0;
        s.tvz = 0.3; // purely radial: no turn needed
        assertEquals(0.0, AimSolver.neededYawRateDegPerTick(s, 0.0), 1e-9);
    }
}
