package com.utilitysuite.combat.boss;

import com.utilitysuite.combat.tactics.RangedBallistics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossArcTest {

    private final double[] pts = new double[BossArc.MAX_POINTS * 3];

    private int solvedArc(double dist, double dy) {
        RangedBallistics b = new RangedBallistics();
        assertTrue(b.solve(dist, dy, RangedBallistics.BOW_FULL_SPEED, RangedBallistics.ARROW_WATER_DRAG, false));
        return BossArc.fill(0.5, 70.0, 0.5, 0.5 + dist, 0.5, b.elevationDeg(), RangedBallistics.BOW_FULL_SPEED, pts);
    }

    @Test
    void arcStartsAtTheEyeAndEndsOnTheTarget() {
        int n = solvedArc(40.0, 20.0);
        assertTrue(n >= 3);
        assertEquals(0.5, pts[0], 1e-9);
        assertEquals(70.0, pts[1], 1e-9);
        assertEquals(40.5, pts[(n - 1) * 3], 1e-6, "ends at the target's horizontal distance");
        assertEquals(90.0, pts[(n - 1) * 3 + 1], 0.05, "and at the target's height (solver tolerance)");
        assertEquals(0.5, pts[(n - 1) * 3 + 2], 1e-9);
        for (int i = 1; i < n; i++) {
            assertTrue(pts[i * 3] > pts[(i - 1) * 3], "moves toward the target every tick");
        }
    }

    @Test
    void lineOfSightFindsAWallAcrossTheArcAndIgnoresOpenSky() {
        int n = solvedArc(30.0, 5.0);
        assertEquals(-1, BossArc.firstBlocked(pts, n, (x, y, z) -> false));
        // A wall at x 15 from y 0 to 200 blocks the arc in the segment that crosses x 15.
        int b = BossArc.firstBlocked(pts, n, (x, y, z) -> x == 15);
        assertTrue(b >= 0);
        assertTrue(pts[b * 3] <= 15.0 && pts[(b + 1) * 3] >= 15.0, "blocked segment crosses the wall");
        // A block far below the arc does not count.
        assertEquals(-1, BossArc.firstBlocked(pts, n, (x, y, z) -> y < 60));
    }

    @Test
    void skullLineIsCappedAndStraight() {
        double[] end = new double[3];
        BossArc.skullEnd(0, 80, 0, 1.0, -0.5, 0, 40, 32.0, end);
        double len = Math.sqrt(end[0] * end[0] + (end[1] - 80) * (end[1] - 80) + end[2] * end[2]);
        assertEquals(32.0, len, 1e-9);
        assertEquals(-0.5, (end[1] - 80) / end[0], 1e-9);
        BossArc.skullEnd(0, 80, 0, 0.1, 0, 0, 40, 32.0, end);
        assertEquals(4.0, end[0], 1e-9);
    }
}
