package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Angles;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteFollowerTest {

    /** The rotation cap: 450 deg/s = 22.5 deg per tick. */
    static final float TURN = 22.5f;

    static final class Run {
        int ticks;
        boolean finished;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        float damage;
        int collisions;
        int maxSim;
        int maxSamples;
        final Set<Long> cells = new HashSet<>();
        boolean cutCorner;
    }

    /** A route of feet cells given as x, y, z triples. */
    static long[] route(int... xyz) {
        long[] r = new long[xyz.length / 3];
        for (int i = 0; i < r.length; i++) {
            r[i] = Packed.pack(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
        }
        return r;
    }

    /** Straight route along +Z at x = 0, feet y = 20, z in [from, to]. */
    static int[] line(int from, int to) {
        int[] a = new int[(to - from + 1) * 3];
        for (int z = from, i = 0; z <= to; z++, i += 3) {
            a[i] = 0;
            a[i + 1] = 20;
            a[i + 2] = z;
        }
        return a;
    }

    static int[] concat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) {
            n += p.length;
        }
        int[] out = new int[n];
        int o = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    /** Drives a body with the follower's keys, turning the camera toward wantYaw at no more than {@link #TURN}. */
    static Run drive(RouteFollower f, MovementPhysics p, MovementPhysics.Voxels w, float yaw, int maxTicks) {
        Run r = new Run();
        int lastProgress = 0;
        for (int t = 0; t < maxTicks; t++) {
            f.update(p, yaw, 20f, w);
            assertTrue(f.progressIndex() >= lastProgress, "progress went backwards at tick " + t);
            lastProgress = f.progressIndex();
            assertFalse(f.offRoute(), "off route at tick " + t + " at " + p.x + "," + p.y + "," + p.z);
            r.maxSim = Math.max(r.maxSim, f.lastSimTicks());
            r.maxSamples = Math.max(r.maxSamples, f.lastSamples());
            if (f.finished()) {
                r.finished = true;
                r.ticks = t;
                return r;
            }
            boolean wasAir = !p.onGround;
            double fallBefore = p.fallDistance;
            double yBefore = p.y;
            p.tick(f.forward(), f.strafe(), yaw, f.sprint(), f.jump(), w);
            if (p.onGround && wasAir) {
                r.damage += MovementPhysics.fallDamage(fallBefore + Math.max(0, yBefore - p.y));
            }
            if (p.horizontalCollision) {
                r.collisions++;
            }
            yaw += (float) Math.max(-TURN, Math.min(TURN, Angles.delta(yaw, f.wantYaw())));
            r.minY = Math.min(r.minY, p.y);
            r.maxX = Math.max(r.maxX, p.x);
            if (p.onGround) {
                r.cells.add(Packed.pack(MovementPhysics.floor(p.x), MovementPhysics.floor(p.y + 0.01),
                        MovementPhysics.floor(p.z)));
            }
        }
        r.ticks = maxTicks;
        return r;
    }

    static MovementPhysics body(double x, double z) {
        return new MovementPhysics().set(x, 20, z, 0, 0, 0, true);
    }

    static void assertBudgets(Run r) {
        assertTrue(r.maxSim <= RouteFollower.SIM_BUDGET, "simulated ticks per update " + r.maxSim);
        assertTrue(r.maxSamples <= RouteFollower.SAMPLE_BUDGET, "corridor samples per update " + r.maxSamples);
    }

    @Test
    void straightFlatRouteIsSprintJumpedAndBeatsSprinting() {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 2, 19, 45);
        long[] r = route(line(0, 40));
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 600);
        assertTrue(run.finished, "finished");
        assertEquals(r.length - 1, f.progressIndex());
        assertTrue(f.jumpsTaken() >= 3, "sprint hops on the flat: " + f.jumpsTaken());
        assertTrue(run.minY >= 19.99);
        assertBudgets(run);
        // baseline: sprinting straight without jumps until the last cell
        MovementPhysics p = body(0.5, 0.5);
        int sprintTicks = 0;
        while (p.z < 40.0) {
            p.tick(1, 0, 0f, true, false, g);
            sprintTicks++;
        }
        assertTrue(run.ticks < sprintTicks, "follower " + run.ticks + " ticks vs sprinting " + sprintTicks);
        // walking is slower still
        assertTrue(run.ticks < 40 / 0.216);
    }

    @Test
    void finishedLatchesAndKeysRelease() {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 2, 19, 20);
        long[] r = route(line(0, 6));
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        MovementPhysics p = body(0.5, 0.5);
        Run run = drive(f, p, g, 0f, 200);
        assertTrue(run.finished);
        f.update(p, 0f, 20f, g);
        assertTrue(f.finished());
        assertEquals(0, f.forward());
        assertEquals(0, f.strafe());
        assertFalse(f.jump());
        f.setRoute(r, 0);
        assertFalse(f.hasRoute());
    }

    /** L route: up +Z along x = 0 to z = 8, then +X along z = 8 to x = 8. */
    static long[] lRoute() {
        int[] a = line(0, 8);
        int[] b = new int[8 * 3];
        for (int x = 1, i = 0; x <= 8; x++, i += 3) {
            b[i] = x;
            b[i + 1] = 20;
            b[i + 2] = 8;
        }
        return route(concat(a, b));
    }

    @Test
    void openCornerIsCutDiagonally() {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 11, 19, 11);
        long[] r = lRoute();
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 400);
        assertTrue(run.finished);
        boolean cut = false;
        for (long c : run.cells) {
            if (Packed.x(c) >= 2 && Packed.z(c) <= 6) {
                cut = true;
            }
        }
        assertTrue(cut, "ran through the inside of the corner");
        assertBudgets(run);
    }

    @Test
    void cornerBesideAWallIsNotCut() {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 11, 19, 11);
        g.fill(1, 20, -2, 11, 21, 7); // the inside of the L is a wall
        long[] r = lRoute();
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 400);
        assertTrue(run.finished);
        assertTrue(run.collisions <= 2, "bumped the wall " + run.collisions + " times");
        assertBudgets(run);
    }

    @Test
    void cornerBesideAHoleIsNotCut() {
        Grid g = new Grid();
        g.fill(-1, 19, -2, 0, 19, 9); // the first leg, two wide
        g.fill(-1, 19, 8, 11, 19, 9); // the second leg; the inside of the L is a void
        long[] r = lRoute();
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 400);
        assertTrue(run.finished);
        assertTrue(run.minY >= 19.99, "never fell into the hole: " + run.minY);
        assertBudgets(run);
    }

    private static void gapRun(int air) {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 2, 19, 5);
        g.fill(-2, 19, 6 + air, 2, 19, 25);
        long[] r = route(concat(line(0, 5), line(6 + air, 6 + air + 8)));
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 400);
        assertTrue(run.finished, air + "-block gap: finished");
        assertTrue(run.minY >= 19.99, air + "-block gap landed, min y " + run.minY);
        assertTrue(f.jumpsTaken() >= 1);
        assertEquals(0f, run.damage);
        assertBudgets(run);
    }

    @Test
    void twoBlockGapOnTheRouteIsJumped() {
        gapRun(2);
    }

    @Test
    void threeBlockGapOnTheRouteIsJumped() {
        gapRun(3);
    }

    /** Detour route around a hole x in [-2, 1], z in [6, 6 + air): along x = 0, out to x = 2, back to x = 0. */
    static long[] detourRoute(int air) {
        int back = 6 + air;
        int[] a = line(0, 5);
        int[] out = {1, 20, 5, 2, 20, 5};
        int[] lane = new int[(air + 1) * 3];
        for (int z = 6, i = 0; z <= back; z++, i += 3) {
            lane[i] = 2;
            lane[i + 1] = 20;
            lane[i + 2] = z;
        }
        int[] in = {1, 20, back, 0, 20, back};
        return route(concat(a, out, lane, in, line(back + 1, back + 6)));
    }

    static Grid detourGrid(int air) {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 3, 19, 5);
        g.fill(2, 19, 6, 3, 19, 5 + air);
        g.fill(-2, 19, 6 + air, 3, 19, 6 + air + 8);
        return g;
    }

    @Test
    void detourAroundATwoBlockGapIsShortcutByAJump() {
        Grid g = detourGrid(2);
        long[] r = detourRoute(2);
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 400);
        assertTrue(run.finished);
        assertTrue(f.jumpsTaken() >= 1);
        assertFalse(run.cells.contains(Packed.pack(2, 20, 6)) || run.cells.contains(Packed.pack(2, 20, 7)),
                "did not walk the detour lane");
        assertTrue(run.minY >= 19.99);
        assertEquals(0f, run.damage);
        assertBudgets(run);
    }

    @Test
    void gapTooWideIsNotAttemptedAndTheDetourIsWalked() {
        Grid g = detourGrid(5);
        long[] r = detourRoute(5);
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 600);
        assertTrue(run.finished);
        assertTrue(run.cells.contains(Packed.pack(2, 20, 8)), "walked the detour lane");
        assertTrue(run.minY >= 19.99, "never fell: " + run.minY);
        assertBudgets(run);
    }

    @Test
    void aJumpThatWouldCostHealthIsNotTaken() {
        // across the gap the far side is 2 lower (a jump lands with 1 damage); the route walks down a side stair
        Grid g = new Grid();
        g.fill(-2, 19, -2, 3, 19, 5);
        g.fill(2, 18, 6, 3, 18, 6);
        g.fill(2, 17, 7, 3, 17, 7);
        g.fill(-2, 17, 8, 3, 17, 16);
        long[] r = route(concat(line(0, 5), new int[]{1, 20, 5, 2, 20, 5, 2, 19, 6, 2, 18, 7, 2, 18, 8, 1, 18, 8,
                0, 18, 8, 0, 18, 9, 0, 18, 10, 0, 18, 11, 0, 18, 12}));
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        MovementPhysics p = body(0.5, 0.5);
        Run run = drive(f, p, g, 0f, 500);
        assertTrue(run.finished);
        assertEquals(0f, run.damage, "no fall damage taken");
        assertTrue(run.maxX >= 1.5, "went down the side stair, max x " + run.maxX);
        assertBudgets(run);
    }

    @Test
    void oneBlockStepUpIsJumped() {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 2, 19, 5);
        g.fill(-2, 20, 6, 2, 20, 15);
        int[] up = new int[10 * 3];
        for (int z = 6, i = 0; z <= 15; z++, i += 3) {
            up[i] = 0;
            up[i + 1] = 21;
            up[i + 2] = z;
        }
        long[] r = route(concat(line(0, 5), up));
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        Run run = drive(f, body(0.5, 0.5), g, 0f, 300);
        assertTrue(run.finished);
        assertBudgets(run);
    }

    @Test
    void offRouteAfterTeleportAndNoKeys() {
        Grid g = new Grid();
        g.fill(-2, 19, -2, 2, 19, 30);
        g.fill(15, 19, 15, 25, 19, 25);
        long[] r = route(line(0, 25));
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        MovementPhysics p = body(0.5, 0.5);
        for (int t = 0; t < 10; t++) {
            f.update(p, 0f, 20f, g);
            p.tick(f.forward(), f.strafe(), 0f, f.sprint(), f.jump(), g);
        }
        assertFalse(f.offRoute());
        int before = f.progressIndex();
        p.set(20.5, 20, 20.5, 0, 0, 0, true);
        f.update(p, 0f, 20f, g);
        assertTrue(f.offRoute());
        assertEquals(0, f.forward());
        assertFalse(f.jump());
        assertEquals(before, f.progressIndex(), "progress kept");
        // fell 5 below the route
        p.set(0.5, 14, 4.5, 0, 0, 0, true);
        f.update(p, 0f, 20f, g);
        assertTrue(f.offRoute());
    }

    @Test
    void neverJumpsAtTheLipOfADropTheRouteDoesNotTake() {
        // route ends at the edge of a 6-deep drop; the follower must stop on it, never jump or step off
        Grid g = new Grid();
        g.fill(-2, 19, -2, 2, 19, 6);
        g.fill(-2, 13, 7, 2, 13, 20);
        long[] r = route(line(0, 6));
        RouteFollower f = new RouteFollower();
        f.setRoute(r, r.length);
        MovementPhysics p = body(0.5, 0.5);
        Run run = drive(f, p, g, 0f, 200);
        assertTrue(run.finished);
        assertTrue(run.minY >= 19.99);
        for (int t = 0; t < 20; t++) {
            f.update(p, 0f, 20f, g);
            assertFalse(f.jump());
        }
    }
}
