package com.utilitysuite.travel.movement;

import com.utilitysuite.combat.tactics.HitTradeSolver;
import com.utilitysuite.combat.tactics.TradeInput;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.BodySim;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unified sim step 1: the combat body sims behind the "Exact world shapes" switch. Off = the full-cube view and the
 * plain body, exactly as before; on = the exact-shape view Movement Assist plans on, with the movement model (speed
 * attribute, Jump Boost, sprint state) seeded through {@link BodySim#movement}.
 */
class ExactWorldBodySimTest {

    /** The LevelVoxels reading of a shaped grid: any collision box = solid, a unit cube = full; never shaped. */
    static MovementPhysics.Voxels cubesOf(MovementPhysics.Voxels exact) {
        return new MovementPhysics.Voxels() {
            @Override
            public boolean solid(int x, int y, int z) {
                return exact.solid(x, y, z);
            }

            @Override
            public boolean fullBlock(int x, int y, int z) {
                return exact.fullBlock(x, y, z);
            }
        };
    }

    /**
     * Engine-free {@link LevelShapes} (through its cell-source seam): a stone floor at y 0, a row of bottom slabs at
     * y 1 from z 3 on, a fence post at (4, 1, 0) and a wall of stone at z -3.
     */
    static LevelShapes arena() {
        LevelShapes s = new LevelShapes();
        int air = s.defineEntry(null, MovementPhysics.SURFACE_NONE, 0.6f);
        int stone = s.defineEntry(new float[]{0, 0, 0, 1, 1, 1}, MovementPhysics.SURFACE_NORMAL, 0.6f);
        int slab = s.defineEntry(new float[]{0, 0, 0, 1, 0.5f, 1}, MovementPhysics.SURFACE_NORMAL, 0.6f);
        int fence = s.defineEntry(new float[]{0.375f, 0, 0.375f, 0.625f, 1.5f, 0.625f},
                MovementPhysics.SURFACE_NORMAL, 0.6f);
        s.begin((x, y, z) -> {
            boolean inside = x >= -8 && x <= 8 && z >= -8 && z <= 12;
            if (!inside) {
                return air;
            }
            if (y == 0) {
                return stone;
            }
            if (y == 1 && z >= 3 && x >= -1 && x <= 1) {
                return slab;
            }
            if (y == 1 && x == 4 && z == 0) {
                return fence;
            }
            return air;
        });
        return s;
    }

    // ---- (a) flag off: predictions unchanged ---------------------------------------------------------------------

    @Test
    void switchOffIsTheCubePathBitForBit() {
        LevelShapes exact = arena();
        MovementPhysics.Voxels cubes = cubesOf(exact);
        WorldViewSwitch view = new WorldViewSwitch(cubes, exact);
        assertFalse(view.usingExact(), "the switch starts on the cube view");
        assertFalse(view.shaped());

        // old path: a bare MovementPhysics on the cube view, set() only (speed 0.1, no jump boost)
        MovementPhysics old = new MovementPhysics().set(0.5, 1, 0.5, 0, 0, 0, true);
        // new path, setting off: a body over the switch, seeded with the plain movement model
        PhysicsBodySim now = new PhysicsBodySim(view);
        now.set(0.5, 1, 0.5, 0, 0, 0, true).movement(BodySim.BASE_SPEED, 0.0, false);
        for (int t = 0; t < 40; t++) {
            boolean jump = t == 5 || t == 25;
            float yaw = t * 7f;
            old.tick(1, t % 3 - 1, yaw, true, jump, cubes);
            now.tick(1, t % 3 - 1, yaw, true, jump);
            assertEquals(old.x, now.x(), 0.0, "x at tick " + t);
            assertEquals(old.y, now.y(), 0.0, "y at tick " + t);
            assertEquals(old.z, now.z(), 0.0, "z at tick " + t);
            assertEquals(old.vx, now.vx(), 0.0);
            assertEquals(old.vy, now.vy(), 0.0);
            assertEquals(old.vz, now.vz(), 0.0);
            assertEquals(old.onGround, now.onGround());
            assertEquals(old.horizontalCollision, now.horizontalCollision());
        }
    }

    @Test
    void tradeSolverScoresUnchangedWithTheSettingOff() {
        LevelShapes exact = arena();
        MovementPhysics.Voxels cubes = cubesOf(exact);
        WorldViewSwitch view = new WorldViewSwitch(cubes, exact);
        HitTradeSolver before = new HitTradeSolver(() -> new PhysicsBodySim(cubes));
        HitTradeSolver after = new HitTradeSolver(() -> new PhysicsBodySim(view));
        for (int k = 0; k < 64; k++) {
            TradeInput in = duelTowardSlabs(2.6 + (k & 7) * 0.2);
            in.sReadyIn = k % 13;
            in.oReadyIn = (k >> 2) % 13;
            in.allowJump = (k & 1) == 0;
            before.solve(in);
            after.solve(in);
            for (int c = 0; c < HitTradeSolver.COMBOS; c++) {
                assertEquals(before.scoreOf(c), after.scoreOf(c), 0.0, "combo " + c + " case " + k);
            }
            assertEquals(before.forward(), after.forward());
            assertEquals(before.strafe(), after.strafe());
            assertEquals(before.jump(), after.jump());
        }
    }

    @Test
    void settingOnChangesTheRolloutOverSlabs() {
        LevelShapes exact = arena();
        WorldViewSwitch view = new WorldViewSwitch(cubesOf(exact), exact);
        HitTradeSolver solver = new HitTradeSolver(() -> new PhysicsBodySim(view));
        TradeInput in = duelTowardSlabs(3.4);
        in.sReadyIn = 0;
        in.oReadyIn = 6;
        solver.solve(in);
        double cubeForward = solver.scoreOf(HitTradeSolver.combo(1, 0));
        view.useExact(true);
        assertTrue(view.usingExact());
        assertTrue(view.shaped());
        solver.reset();
        solver.solve(in);
        assertNotEquals(cubeForward, solver.scoreOf(HitTradeSolver.combo(1, 0)),
                "stepping in over the slabs is scored on the step-up, not on a wall");
    }

    /** We stand at (0.5, 1, 0.5) on the floor; the opponent {@code dist} blocks toward +Z, on the slabs if past 3. */
    static TradeInput duelTowardSlabs(double dist) {
        TradeInput in = new TradeInput();
        in.sx = 0.5;
        in.sy = 1;
        in.sz = 0.5;
        in.sOnGround = true;
        in.sYaw = 0f;
        in.ox = 0.5;
        in.oz = 0.5 + dist;
        in.oy = in.oz >= 3.0 ? 1.5 : 1.0;
        in.oOnGround = true;
        in.oForward = 0;
        return in;
    }

    // ---- (b) exact shapes: step-up and fence tops ----------------------------------------------------------------

    @Test
    void exactViewStepsUpOntoSlabsWhereCubesBlock() {
        LevelShapes exact = arena();
        WorldViewSwitch view = new WorldViewSwitch(cubesOf(exact), exact);
        PhysicsBodySim body = new PhysicsBodySim(view);

        body.set(0.5, 1, 0.5, 0, 0, 0, true);
        for (int t = 0; t < 30; t++) {
            body.tick(1, 0, 0f, false, false); // walk toward +Z
        }
        assertEquals(1.0, body.y(), 1e-9, "cube view: the slab row is a one-block wall");
        assertTrue(body.z() < 3.0 - 0.3 + 1e-6, "cube view: blocked at the slab face, z = " + body.z());
        assertTrue(body.horizontalCollision());

        view.useExact(true);
        body.set(0.5, 1, 0.5, 0, 0, 0, true);
        for (int t = 0; t < 30; t++) {
            body.tick(1, 0, 0f, false, false);
        }
        assertEquals(1.5, body.y(), 1e-9, "exact view: stepped up 0.5 onto the slabs");
        assertTrue(body.z() > 4.0, "exact view: walked on over the slabs, z = " + body.z());
        assertTrue(body.onGround());
    }

    @Test
    void exactViewStandsOnAFenceTopAtOnePointFive() {
        LevelShapes exact = arena();
        WorldViewSwitch view = new WorldViewSwitch(cubesOf(exact), exact);
        PhysicsBodySim body = new PhysicsBodySim(view);

        body.set(4.5, 4, 0.5, 0, 0, 0, false);
        for (int t = 0; t < 40; t++) {
            body.tick(0, 0, 0f, false, false);
        }
        assertEquals(2.0, body.y(), 1e-9, "cube view: the fence reads as a cube, top at 2.0");

        view.useExact(true);
        body.set(4.5, 4, 0.5, 0, 0, 0, false);
        for (int t = 0; t < 40; t++) {
            body.tick(0, 0, 0f, false, false);
        }
        assertEquals(2.5, body.y(), 1e-9, "exact view: the fence top is 1.5 above its cell");
        assertTrue(body.onGround());
    }

    // ---- (c) speed attribute -------------------------------------------------------------------------------------

    @Test
    void speedTwoCoversThePlainDistanceTimesOnePointFour() {
        // vanilla Speed: ADD_MULTIPLIED_TOTAL 0.2 per level, so Speed II = 0.1 x 1.4 = 0.14
        double speed2 = 0.1 * (1.0 + 0.2 * 2);
        LevelShapes exact = arena();
        MovementPhysics.Voxels cubes = cubesOf(exact);
        // cube path: double arithmetic, so ground motion from rest is exactly linear in the speed
        assertRatio(cubes, speed2, false, 1e-9);
        assertRatio(cubes, speed2, true, 1e-9);
        // full path (exact view): the speed goes through floats, as Player.getSpeed does
        assertRatio(exact, speed2, false, 1e-6);
        assertRatio(exact, speed2, true, 1e-6);
    }

    private static void assertRatio(MovementPhysics.Voxels w, double speed, boolean sprint, double tol) {
        PhysicsBodySim plain = new PhysicsBodySim(w);
        PhysicsBodySim fast = new PhysicsBodySim(w);
        // walk toward -X along the open floor (yaw 90 = -X), away from slabs and fence; a body at rest on the ground
        // carries vanilla's resting vy (one tick of gravity), so the full path keeps it on the ground from tick one
        double rest = (0.0 - MovementPhysics.GRAVITY) * (double) 0.98F;
        plain.set(6.5, 1, -1.5, 0, rest, 0, true).movement(BodySim.BASE_SPEED, 0.0, sprint);
        fast.set(6.5, 1, -1.5, 0, rest, 0, true).movement(speed, 0.0, sprint);
        double lastPlain = 0;
        double lastFast = 0;
        for (int t = 0; t < 25; t++) {
            double px = plain.x();
            double fx = fast.x();
            plain.tick(1, 0, 90f, sprint, false);
            fast.tick(1, 0, 90f, sprint, false);
            lastPlain = px - plain.x();
            lastFast = fx - fast.x();
        }
        double ratio = speed / BodySim.BASE_SPEED;
        assertEquals(ratio, lastFast / lastPlain, tol * 10, "per-tick ground speed ratio (sprint " + sprint + ")");
        assertEquals(ratio, (6.5 - fast.x()) / (6.5 - plain.x()), tol * 10, "distance ratio (sprint " + sprint + ")");
        assertTrue(lastPlain > 0.1, "the plain body is moving");
    }

    // ---- (d) jump boost ------------------------------------------------------------------------------------------

    @Test
    void jumpBoostRaisesTheJumpByTheVanillaAmount() {
        LevelShapes exact = arena();
        MovementPhysics.Voxels cubes = cubesOf(exact);
        double base = peak(cubes, 0.0);
        assertEquals(1.2522, base, 1e-4, "plain vanilla jump height");
        for (int level = 1; level <= 2; level++) {
            double boost = 0.1 * level; // LivingEntity.getJumpBoostPower: 0.1 x (amplifier + 1)
            assertEquals(referencePeak(0.42 + boost, false), peak(cubes, boost), 1e-9, "cube path, level " + level);
            assertEquals(referencePeak((double) (0.42F + (float) boost), true), peak(exact, boost), 1e-9,
                    "full path, level " + level);
            assertTrue(peak(cubes, boost) > base + 0.4 * level, "higher with Jump Boost " + level);
        }
    }

    /** Peak feet height gained by one standing jump from the open floor. */
    private static double peak(MovementPhysics.Voxels w, double boost) {
        PhysicsBodySim b = new PhysicsBodySim(w);
        b.set(6.5, 1, 6.5, 0, 0, 0, true).movement(BodySim.BASE_SPEED, boost, false);
        double top = 1;
        b.tick(0, 0, 0f, false, true);
        top = Math.max(top, b.y());
        for (int t = 0; t < 30; t++) {
            b.tick(0, 0, 0f, false, false);
            top = Math.max(top, b.y());
        }
        return top - 1;
    }

    /** Vanilla integration: move by vy, then vy = (vy - 0.08) x 0.98 (the full path zeroes |vy| < 0.003 first). */
    private static double referencePeak(double vy0, boolean fullPath) {
        double y = 0;
        double top = 0;
        double vy = vy0;
        for (int t = 0; t < 40; t++) {
            if (fullPath && t > 0 && Math.abs(vy) < MovementPhysics.MIN_VELOCITY) {
                vy = 0.0;
            }
            y += vy;
            if (y <= 0) {
                break;
            }
            top = Math.max(top, y);
            vy = (vy - MovementPhysics.GRAVITY) * (fullPath ? (double) 0.98F : MovementPhysics.DRAG);
        }
        return top;
    }

    // ---- allocation ----------------------------------------------------------------------------------------------

    @Test
    void exactBodiesAndTheSolverAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        LevelShapes exact = arena();
        WorldViewSwitch view = new WorldViewSwitch(cubesOf(exact), exact);
        view.useExact(true);
        HitTradeSolver solver = new HitTradeSolver(() -> new PhysicsBodySim(view));
        TradeInput in = duelTowardSlabs(3.2);
        in.sSpeed = 0.14;
        in.oSpeed = 0.12;
        in.oJumpBoost = 0.1;
        in.sBodySprinting = true;
        in.oBodySprinting = true;
        in.horizon = 8;
        in.allowJump = true;
        PhysicsBodySim body = new PhysicsBodySim(view);
        solver.solve(in); // warm the cell cache
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(20_000, () -> {
            int k = i[0]++;
            in.oz = 0.5 + 2.8 + (k & 15) * 0.1;
            in.sReadyIn = k % 13;
            solver.solve(in);
            body.set(0.5, 1, 0.5, 0, 0, 0, true).movement(0.14, 0.1, true);
            for (int t = 0; t < 8; t++) {
                body.tick(1, 0, 0f, true, t == 0);
            }
            sink[0] += solver.score() + body.z();
        });
        assertTrue(bytes < 1_024, "exact-view solve + body ticks allocated " + bytes + " bytes over 20k calls");
        assertTrue(sink[0] != 12345.678);
    }
}
