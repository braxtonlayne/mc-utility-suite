package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.tactics.FeintPlanner.Move;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opponent at the origin. In the strafe scenarios we circle it at radius 3: our bearing from it (Minecraft yaw)
 * advances {@link #W} degrees per tick per unit of strafe.
 */
class FeintPlannerTest {

    static final double R = 3.0;
    static final double W = 4.0;

    /** One circling tick; returns the planner's move. {@code leadTicks} = how far ahead of us the opponent aims. */
    static final class Circle {
        double theta;
        int base = 1;
        int strafe = 1;
        final List<Long> starts = new ArrayList<>();

        Move tick(FeintPlanner f, int id, long t, double leadTicks, double unit) {
            int dir = strafe;
            double rad = Math.toRadians(theta);
            double px = -R * Math.sin(rad);
            double pz = R * Math.cos(rad);
            double w = Math.toRadians(W) * dir;
            double vx = -R * Math.cos(rad) * w;
            double vz = -R * Math.sin(rad) * w;
            float yaw = (float) (theta + leadTicks * W * dir);
            int before = f.feints();
            f.observe(id, t, 0, 0, yaw, 0, 0, false, px, pz, vx, vz, dir);
            Move m = f.plan(id, t, dir, R, 5, 5, true, unit); // opponent mid-cooldown: no bait
            if (f.feints() > before) {
                starts.add(t);
            }
            strafe = f.strafeOverride() != FeintPlanner.KEEP ? f.strafeOverride() : base;
            theta += W * strafe;
            if (theta > 180) {
                theta -= 360;
            } else if (theta < -180) {
                theta += 360;
            }
            return m;
        }
    }

    @Test
    void aimLeadSignAndMagnitude() {
        // us at (0, 3) moving -X at 0.2: the bearing turns +3.82 deg/tick as seen from the origin
        double rate = Math.toDegrees(0.2 / 3.0);
        FeintPlanner f = new FeintPlanner();
        f.observe(1, 0, 0, 0, (float) (2 * rate), 0, 0, false, 0, 3, -0.2, 0, 1);
        assertEquals(2.0, f.aimLeadTicks(1), 1e-3);

        FeintPlanner g = new FeintPlanner();
        g.observe(1, 0, 0, 0, (float) (-rate), 0, 0, false, 0, 3, -0.2, 0, 1);
        assertEquals(-1.0, g.aimLeadTicks(1), 1e-3, "aiming behind us lags");

        FeintPlanner h = new FeintPlanner();
        h.observe(1, 0, 0, 0, (float) (2 * rate), 0, 0, false, 0, 3, 0.2, 0, -1);
        assertEquals(-2.0, h.aimLeadTicks(1), 1e-3, "same facing, we move the other way");

        FeintPlanner k = new FeintPlanner();
        k.observe(1, 0, 0, 0, 0f, 0, 0, false, 0, 3, -0.2, 0, 1);
        assertEquals(0.0, k.aimLeadTicks(1), 1e-6);
        assertEquals(0.0, k.aimLeadTicks(99), 0.0, "unknown opponent");
    }

    @Test
    void preAimTriggersOneBreakThenCooldown() {
        FeintPlanner f = new FeintPlanner();
        Circle c = new Circle();
        long start = -1;
        double preAimAtStart = 0;
        int breakTicks = 0;
        for (long t = 0; t < 300; t++) {
            double pa = f.preAim(1);
            Move m = c.tick(f, 1, t, 2.0, 0.1);
            if (m == Move.BREAK_PATTERN) {
                assertEquals(-1, f.strafeOverride(), "reverse the shown left strafe");
                assertEquals(FeintPlanner.KEEP, f.forwardOverride());
                if (start < 0) {
                    start = t;
                    preAimAtStart = pa;
                }
                if (t < start + 20) {
                    breakTicks++;
                }
            } else {
                assertEquals(FeintPlanner.KEEP, f.strafeOverride());
            }
            if (start >= 0 && t == start + 50) {
                assertEquals(1, f.feints(), "one break, then a cooldown");
            }
        }
        assertTrue(start > FeintPlanner.HOLD_TICKS, "needs a held strafe first: " + start);
        // preAimAtStart is the value before that tick's sample (a pre-aiming sample moves it 10% toward 1)
        double withSample = preAimAtStart + FeintPlanner.PRE_AIM_ALPHA * (1 - preAimAtStart);
        assertTrue(withSample > FeintPlanner.PRE_AIM_TRIGGER, "preAim " + withSample);
        assertTrue(f.preAim(1) > 0.3, "still a pre-aimer: " + f.preAim(1));
        assertTrue(breakTicks >= 6 && breakTicks <= 10, "break length " + breakTicks);
        assertEquals(2.0, f.aimLeadTicks(1), 0.6);
        for (int i = 1; i < c.starts.size(); i++) {
            assertTrue(c.starts.get(i) - c.starts.get(i - 1) >= FeintPlanner.MIN_GAP + 6);
        }
    }

    @Test
    void opponentAimingAtUsNeverTriggersABreak() {
        FeintPlanner f = new FeintPlanner();
        Circle c = new Circle();
        for (long t = 0; t < 600; t++) {
            assertEquals(Move.NONE, c.tick(f, 1, t, 0.0, 0.0));
        }
        assertEquals(0, f.feints());
        assertTrue(f.preAim(1) < 0.05, "preAim " + f.preAim(1));
        assertEquals(0.0, f.aimLeadTicks(1), 0.05);
    }

    @Test
    void laggingAimNeverTriggersABreak() {
        FeintPlanner f = new FeintPlanner();
        Circle c = new Circle();
        for (long t = 0; t < 600; t++) {
            c.tick(f, 1, t, -2.0, 0.0);
        }
        assertEquals(0, f.feints());
        assertTrue(f.aimLeadTicks(1) < -1.5);
    }

    @Test
    void feintsAreNotPeriodic() {
        List<Long> a = run(new Random(11));
        List<Long> b = run(new Random(29));
        assertTrue(a.size() >= 4 && b.size() >= 4, a + " / " + b);
        assertNotEquals(a, b);
        for (List<Long> s : List.of(a, b)) {
            boolean varied = false;
            for (int i = 1; i < s.size(); i++) {
                long gap = s.get(i) - s.get(i - 1);
                assertTrue(gap >= FeintPlanner.MIN_GAP, "gap " + gap + " in " + s);
                if (i > 1 && gap != s.get(i - 1) - s.get(i - 2)) {
                    varied = true;
                }
            }
            assertTrue(varied, "periodic feints " + s);
        }
    }

    private static List<Long> run(Random rnd) {
        FeintPlanner f = new FeintPlanner();
        Circle c = new Circle();
        for (long t = 0; t < 3000; t++) {
            c.tick(f, 1, t, 2.0, rnd.nextDouble());
        }
        return c.starts;
    }

    // ------------------------------------------------------------------ bait

    /** We backpedal from (0, 3) for 6 ticks; with {@code runIn} the opponent sprints in on the 6th. */
    static long trainBait(FeintPlanner f, int id, long t, boolean runIn, int rounds) {
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 6; k++) {
                boolean in = runIn && k == 5;
                f.observe(id, t++, 0, 0, 0f, 0, in ? 0.3 : 0, in, 0, 3 + 0.15 * k, 0, 0.15, 0);
            }
            for (int k = 0; k < 12; k++) {
                f.observe(id, t++, 0, 0, 0f, 0, 0, false, 0, 3, 0, 0, 0);
            }
        }
        return t;
    }

    @Test
    void sprintInOnBackpedalsRaisesBaitRateThenBaitAndPunish() {
        FeintPlanner f = new FeintPlanner();
        long t = trainBait(f, 2, 0, true, 4);
        assertTrue(f.baitRate(2) > 0.9, "baitRate " + f.baitRate(2));
        f.reset();
        assertTrue(f.baitRate(2) > 0.9, "reset keeps habits");

        // too close or too far: no bait
        assertEquals(Move.NONE, f.plan(2, t++, 0, 1.8, 0, 3, true, 0.0));
        assertEquals(Move.NONE, f.plan(2, t++, 0, 5.0, 0, 3, true, 0.0));
        // its swing still charging: no bait
        assertEquals(Move.NONE, f.plan(2, t++, 0, 3.0, 4, 3, true, 0.0));

        f.observe(2, t, 0, 0, 0f, 0, 0, false, 0, 3, 0, 0, 0);
        assertEquals(Move.BAIT_RETREAT, f.plan(2, t++, 0, 3.0, 0, 3, true, 0.1));
        assertEquals(-1, f.forwardOverride());
        assertFalse(f.allowSprint());
        assertEquals(FeintPlanner.KEEP, f.strafeOverride());
        assertEquals(1, f.feints());

        f.observe(2, t, 0, 0, 0f, 0, 0, false, 0, 3.15, 0, 0.15, 0);
        assertEquals(Move.BAIT_RETREAT, f.plan(2, t++, 0, 3.15, 0, 2, true, 0.5));

        // it sprints in and swings (its cooldown restarts)
        f.observe(2, t, 0, 0.4, 0f, 0, 0.4, true, 0, 3.3, 0, 0.15, 0);
        assertEquals(Move.PUNISH, f.plan(2, t++, 0, 2.9, 9, 1, true, 0.5));
        assertEquals(1, f.forwardOverride());
        assertTrue(f.allowSprint());
        int punish = 1;
        while (f.plan(2, t++, 0, 2.5, 8, 0, true, 0.5) == Move.PUNISH) {
            assertEquals(1, f.forwardOverride());
            punish++;
        }
        assertEquals(FeintPlanner.PUNISH_TICKS, punish);
        assertEquals(FeintPlanner.KEEP, f.forwardOverride());
        assertEquals(1, f.feints(), "the punish belongs to the same feint");
    }

    @Test
    void committedSprintInsideReachIsPunishedWhenWeAreReady() {
        FeintPlanner f = new FeintPlanner();
        long t = trainBait(f, 2, 0, true, 3);
        f.observe(2, t, 0, 0, 0f, 0, 0, false, 0, 3, 0, 0, 0);
        assertEquals(Move.BAIT_RETREAT, f.plan(2, t++, 0, 3.0, 0, 2, true, 0.1));
        // sprinting in but holding its swing, still outside 3.2: keep baiting
        f.observe(2, t, 0, 0.2, 0f, 0, 0.4, true, 0, 3.6, 0, 0.15, 0);
        assertEquals(Move.BAIT_RETREAT, f.plan(2, t++, 0, 3.4, 0, 0, true, 0.5));
        // committed inside 3.2 while ours is ready
        f.observe(2, t, 0, 0.7, 0f, 0, 0.4, true, 0, 3.7, 0, 0.1, 0);
        assertEquals(Move.PUNISH, f.plan(2, t++, 0, 3.0, 0, 0, true, 0.5));
    }

    @Test
    void noBaitWithoutRoomAndAbortMidBait() {
        FeintPlanner f = new FeintPlanner();
        long t = trainBait(f, 2, 0, true, 4);
        for (int i = 0; i < 200; i++) {
            assertEquals(Move.NONE, f.plan(2, t++, 0, 3.0, 0, 0, false, 0.0));
        }
        assertEquals(0, f.feints());

        assertEquals(Move.BAIT_RETREAT, f.plan(2, t++, 0, 3.0, 0, 0, true, 0.1));
        assertEquals(Move.BAIT_RETREAT, f.plan(2, t++, 0, 3.1, 0, 0, true, 0.1));
        // a hazard appears behind us: abort at once
        assertEquals(Move.NONE, f.plan(2, t++, 0, 3.2, 0, 0, false, 0.1));
        assertEquals(FeintPlanner.KEEP, f.forwardOverride());
        assertTrue(f.allowSprint());
        // and no new feint right after
        for (int i = 0; i < FeintPlanner.MIN_GAP - 2; i++) {
            assertEquals(Move.NONE, f.plan(2, t++, 0, 3.0, 0, 0, true, 0.0));
        }
    }

    @Test
    void opponentThatNeverRunsInIsNotBaitedAfterOneTest() {
        FeintPlanner f = new FeintPlanner();
        // untested: a small chance to try once
        f.observe(3, 0, 0, 0, 0f, 0, 0, false, 0, 3, 0, 0, 0);
        assertEquals(Move.NONE, f.plan(3, 1, 0, 3.0, 0, 0, true, 0.5));
        assertEquals(Move.BAIT_RETREAT, f.plan(3, 2, 0, 3.0, 0, 0, true, 0.01));
        f.clear();

        long t = trainBait(f, 3, 0, false, 2);
        assertEquals(0.0, f.baitRate(3), 1e-9);
        for (int i = 0; i < 500; i++) {
            assertEquals(Move.NONE, f.plan(3, t++, 0, 3.0, 0, 0, true, 0.0));
        }
    }

    @Test
    void clearDropsHabitsAndSlotsAreReused() {
        FeintPlanner f = new FeintPlanner();
        long t = trainBait(f, 2, 0, true, 2);
        f.clear();
        assertEquals(0.0, f.baitRate(2), 0.0);
        assertEquals(0, f.feints());

        t = trainBait(f, 100, t, true, 2);
        for (int id = 0; id < FeintPlanner.MAX_OPPONENTS; id++) {
            f.observe(id, t++, 0, 0, 0f, 0, 0, false, 0, 3, 0, 0, 0);
        }
        assertEquals(0.0, f.baitRate(100), 0.0, "least recently seen slot reused");
        assertEquals(Move.NONE, f.plan(100, t, 0, 3.0, 0, 0, true, 0.1), "unknown id");
    }
}
