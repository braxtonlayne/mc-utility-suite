package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Technique templates on hand-built shaped scenes: a column beside the gap that the straight line clips (corner, onto
 * a block and onto a fence post), a low ceiling over the takeoff (headhitter, 2 and 3 gap) and a 1-block wall in the
 * line with the landing right behind it (neo). Every found script is replayed through a fresh replica: it must
 * reproduce its recorded positions exactly, touch no wall, end on the target top, keep its view steps under the limit
 * and never turn the view without permission.
 */
class TechniqueLibraryTest {

    static final float RATE = 6f;
    static final int MAX_CALLS = 200;

    /** Result of a search along a run-up. */
    record Run(boolean found, int calls, FlightScript script, MovementPhysics from, float yaw, int maxTicks,
               TechniqueLibrary lib, SupportRegion top) {
    }

    static SupportRegion top(MovementPhysics.Voxels g, int tx, int ty, int tz) {
        SupportRegion top = new SupportRegion();
        double topY = top.topOf(g, tx, ty, tz);
        assertTrue(top.build(g, tx + 0.5, topY, tz + 0.5), "target top");
        return top;
    }

    static float aim(SupportRegion top, double x, double z) {
        double cx = (top.topBound(0) + top.topBound(2)) * 0.5;
        double cz = (top.topBound(1) + top.topBound(3)) * 0.5;
        return (float) Math.toDegrees(Math.atan2(-(cx - x), cz - z));
    }

    /**
     * A user holding W + sprint along the line from the start to the target, calling plan once per tick (as the brain
     * does while ARMED); the run restarts when the body leaves the ground.
     */
    static Run search(MovementPhysics.Voxels g, SupportRegion top, double startX, double startY, double startZ,
                      boolean yawAllowed, int maxCalls) {
        return search(g, top, startX, startY, startZ, yawAllowed, maxCalls, false);
    }

    /** As above; {@code blocked} is the brain's line-blocked flag (it walks the blocked-line family order). */
    static Run search(MovementPhysics.Voxels g, SupportRegion top, double startX, double startY, double startZ,
                      boolean yawAllowed, int maxCalls, boolean blocked) {
        float yaw = aim(top, startX, startZ);
        TechniqueLibrary lib = new TechniqueLibrary();
        lib.setLineBlocked(blocked);
        MovementPhysics p = new MovementPhysics().set(startX, startY, startZ, 0, -0.0784, 0, true);
        int maxTicks = 0;
        for (int c = 0; c < maxCalls; c++) {
            boolean ok = lib.plan(p, yaw, true, 1, 0, top, yawAllowed, RATE, true, 20f, g);
            maxTicks = Math.max(maxTicks, lib.lastSimTicks());
            assertTrue(lib.lastSimTicks() <= TechniqueSource.MAX_SIM_TICKS_PER_CALL, "budget");
            if (ok) {
                FlightScript s = new FlightScript();
                s.copyFrom(lib.script());
                return new Run(true, c + 1, s, new MovementPhysics().copyFrom(p), yaw, maxTicks, lib, top);
            }
            p.tick(1, 0, yaw, true, false, false, g);
            if (!p.onGround) {
                p.set(startX, startY, startZ, 0, -0.0784, 0, true);
            }
        }
        return new Run(false, maxCalls, null, p, yaw, maxTicks, lib, top);
    }

    static ShapeGrid floor() {
        return new ShapeGrid().fill(-3, 19, -12, 3, 19, 1).fill(-8, 5, -12, 8, 5, 12);
    }

    /** LandingJointTest.cornerJumpOntoAFence: a column at (1, 20..21, 2) beside the gap clips the straight line. */
    static ShapeGrid cornerScene(boolean fence) {
        ShapeGrid g = floor().fill(1, 20, 2, 1, 21, 2);
        return fence ? g.fencePost(1, 19, 4) : g.cube(1, 19, 4);
    }

    /** A 2-high room (ceiling blocks at y 22) over the last two takeoff blocks, then a gap onto a wide pad. */
    static ShapeGrid headhitterScene(int gap) {
        ShapeGrid g = floor().fill(-3, 19, 2 + gap, 3, 19, 6 + gap);
        return g.fill(-3, 22, 0, 3, 22, 1);
    }

    /** A 1-wide strip, a 1-block wall column in the line over the gap, the landing block right behind it. */
    static ShapeGrid neoScene() {
        return new ShapeGrid().fill(0, 19, -12, 0, 19, 1).fill(0, 20, 2, 0, 22, 2).cube(0, 19, 4)
                .fill(-8, 5, -12, 8, 5, 12);
    }

    /** Replays the script in a fresh replica and checks every property of the contract. */
    static void verify(Run r, MovementPhysics.Voxels g, boolean yawAllowed) {
        assertTrue(r.found(), "no script found in " + r.calls() + " calls");
        FlightScript s = r.script();
        assertTrue(s.length > 0 && s.length <= FlightScript.MAX_TICKS);
        assertEquals(s.length - 1, s.landIndex, "the script ends at its touchdown");
        assertTrue(s.takeoffIndex >= 0 && s.takeoffIndex < s.landIndex, "takeoff before the touchdown");
        MovementPhysics p = new MovementPhysics().copyFrom(r.from());
        float prevYaw = r.yaw();
        float maxStep = 0f;
        for (int i = 0; i < s.length; i++) {
            assertEquals(i == s.takeoffIndex, s.jump[i], "the jump is on the takeoff tick only");
            if (s.forward[i] <= 0) {
                assertFalse(s.sprint[i], "no sprint without forward");
            }
            float step = Math.abs(s.yaw[i] - prevYaw);
            maxStep = Math.max(maxStep, step);
            assertTrue(step <= RATE + 1e-3f, "yaw step " + step + " at " + i);
            if (!yawAllowed) {
                assertEquals(r.yaw(), s.yaw[i], 0f, "keys-only script turned the view");
            }
            prevYaw = s.yaw[i];
            p.tick(s.forward[i], s.strafe[i], s.yaw[i], s.sprint[i], s.jump[i], false, g);
            // the neo with the view may graze the pillar in the air (the replica slides along it like vanilla)
            boolean graze = yawAllowed && s.name.equals(TechniqueTemplates.NAME_NEO) && i > s.takeoffIndex && !p.onGround;
            assertFalse(p.horizontalCollision && !graze, "wall touch at tick " + i);
            assertEquals(s.x[i], p.x, 0.0, "x at " + i);
            assertEquals(s.y[i], p.y, 0.0, "y at " + i);
            assertEquals(s.z[i], p.z, 0.0, "z at " + i);
        }
        assertTrue(p.onGround, "on the ground at the end");
        assertEquals(r.top().top(), p.y, 1e-6, "on the target's top");
        assertTrue(r.top().contains(p.x, p.z), "inside the target's top");
        assertEquals(maxStep, s.maxYawStep, 1e-4f);
        if (!yawAllowed) {
            assertFalse(s.usesYaw);
        }
        assertTrue(s.margin >= -TechniqueRoller.HOLD_REACH);
    }

    /** After the brain plays index 0, the next call re-bases the kept script: it still starts at the body. */
    static void rebases(Run r, MovementPhysics.Voxels g, boolean yawAllowed) {
        FlightScript s = r.script();
        MovementPhysics p = new MovementPhysics().copyFrom(r.from());
        p.tick(s.forward[0], s.strafe[0], s.yaw[0], s.sprint[0], s.jump[0], false, g);
        assertTrue(r.lib().plan(p, s.yaw[0], true, 1, 0, r.top(), yawAllowed, RATE, true, 20f, g),
                "the kept script survives one played tick");
        FlightScript next = r.lib().script();
        assertTrue(next.length >= 1);
        assertTrue(next.onLine(0, s.x[1], s.y[1], s.z[1]) || next.margin >= s.margin,
                "re-based onto the played line (or replaced by a better one)");
    }

    /** The user's own plain jump (straight line, sprint jump on the last supported tick) does not land. */
    static boolean plainLands(MovementPhysics.Voxels g, SupportRegion top, double startX, double startZ) {
        float yaw = aim(top, startX, startZ);
        MovementPhysics p = new MovementPhysics().set(startX, 20, startZ, 0, -0.0784, 0, true);
        MovementPhysics probe = new MovementPhysics();
        boolean jumped = false;
        for (int t = 0; t < 120; t++) {
            boolean jump = false;
            if (!jumped) {
                probe.copyFrom(p).tick(1, 0, yaw, true, false, false, g);
                jump = !probe.supported(g);
            }
            jumped |= jump;
            p.tick(1, 0, yaw, true, jump, false, g);
            if (jumped && !jump && p.onGround) {
                return Math.abs(p.y - top.top()) < 1e-6 && top.contains(p.x, p.z);
            }
        }
        return false;
    }

    // ---- corner ------------------------------------------------------------------------------------------------------

    @Test
    void cornerOntoABlockKeysOnly() {
        ShapeGrid g = cornerScene(false);
        SupportRegion top = top(g, 1, 19, 4);
        assertFalse(plainLands(g, top, 0.5, -4), "the straight line clips the column");
        Run r = search(g, top, 0.5, 20, -4, false, MAX_CALLS);
        verify(r, g, false);
        rebases(r, g, false);
    }

    @Test
    void cornerOntoABlockWithYaw() {
        ShapeGrid g = cornerScene(false);
        Run r = search(g, top(g, 1, 19, 4), 0.5, 20, -4, true, MAX_CALLS);
        verify(r, g, true);
        rebases(r, g, true);
    }

    /** The fence post behind the corner, approached on a line left of the column (where a player lines up). */
    @Test
    void cornerOntoAFencePostKeysOnly() {
        ShapeGrid g = cornerScene(true);
        SupportRegion top = top(g, 1, 19, 4);
        assertFalse(plainLands(g, top, -1.5, -4), "the plain jump misses the post");
        Run r = search(g, top, -1.5, 20, -4, false, MAX_CALLS);
        verify(r, g, false);
        rebases(r, g, false);
    }

    @Test
    void cornerOntoAFencePostWithYaw() {
        ShapeGrid g = cornerScene(true);
        Run r = search(g, top(g, 1, 19, 4), -1.5, 20, -4, true, MAX_CALLS);
        verify(r, g, true);
    }

    // ---- headhitter --------------------------------------------------------------------------------------------------

    @Test
    void headhitterTwoGap() {
        ShapeGrid g = headhitterScene(2);
        SupportRegion top = top(g, 0, 19, 4);
        Run r = search(g, top, 0.5, 20, -4, false, MAX_CALLS);
        verify(r, g, false);
        rebases(r, g, false);
    }

    @Test
    void headhitterThreeGap() {
        ShapeGrid g = headhitterScene(3);
        Run keys = search(g, top(g, 0, 19, 5), 0.5, 20, -4, false, MAX_CALLS);
        verify(keys, g, false);
        Run yaw = search(g, top(g, 0, 19, 5), 0.5, 20, -4, true, MAX_CALLS);
        verify(yaw, g, true);
    }

    // ---- neo ---------------------------------------------------------------------------------------------------------

    @Test
    void oneBlockNeoKeysOnly() {
        ShapeGrid g = neoScene();
        SupportRegion top = top(g, 0, 19, 4);
        assertFalse(plainLands(g, top, 0.5, -4), "straight into the wall");
        Run r = search(g, top, 0.5, 20, -4, false, MAX_CALLS);
        verify(r, g, false);
        rebases(r, g, false);
    }

    @Test
    void oneBlockNeoWithYaw() {
        ShapeGrid g = neoScene();
        Run r = search(g, top(g, 0, 19, 4), 0.5, 20, -4, true, MAX_CALLS);
        verify(r, g, true);
    }

    /**
     * The course's J36 (mirrored onto +z): a 1-wide strip 4 long, a 4-high pillar standing in the single gap cell
     * (from the floor up, so nothing passes under or through), the 1x3 landing strip right behind it.
     */
    static ShapeGrid courseNeoScene() {
        return new ShapeGrid().fill(0, 19, -1, 0, 19, 2).fill(0, 19, 3, 0, 22, 3).fill(0, 19, 4, 0, 19, 6)
                .fill(-8, 5, -12, 8, 5, 12);
    }

    /**
     * Keys only, J36 is one tick-perfect input (a brute force of 34k strafe timings found 2 landings, both on the rim),
     * so the assist does not offer it without the view; the view variant below is the one that lands.
     */
    @Test
    void courseNeoStraightRunHitsThePillar() {
        ShapeGrid g = courseNeoScene();
        assertFalse(plainLands(g, top(g, 0, 19, 5), 0.5, -0.5), "straight into the pillar");
    }

    @Test
    void courseNeoWithYawFromWhereverJ35Lands() {
        ShapeGrid g = courseNeoScene();
        SupportRegion top = top(g, 0, 19, 5);
        int found = 0, tried = 0;
        for (double x = 0.3; x <= 0.71; x += 0.2) {
            for (double z = -0.9; z <= 0.11; z += 0.25) {
                tried++;
                Run r = search(g, top, x, 20, z, true, MAX_CALLS, true);
                if (r.found()) {
                    verify(r, g, true);
                    found++;
                }
            }
        }
        System.out.println("COURSE NEO starts " + found + "/" + tried);
        assertTrue(found * 10 >= tried * 8, found + " of " + tried + " starts");
    }

    @Test
    void courseNeoWithYaw() {
        ShapeGrid g = courseNeoScene();
        Run r = search(g, top(g, 0, 19, 5), 0.5, 20, -0.5, true, MAX_CALLS, true);
        verify(r, g, true);
    }

    // ---- template grid -----------------------------------------------------------------------------------------------

    @Test
    void keysOnlyGridsCarryNoYawAndFortyFiveIsYawOnly() {
        assertEquals(0, TechniqueTemplates.size(TechniqueTemplates.FORTY_FIVE, false));
        assertTrue(TechniqueTemplates.size(TechniqueTemplates.FORTY_FIVE, true) > 0);
        TechniqueTemplates.Candidate c = new TechniqueTemplates.Candidate();
        for (int t = 0; t < TechniqueTemplates.COUNT; t++) {
            for (int i = 0; i < TechniqueTemplates.size(t, false); i++) {
                if (!TechniqueTemplates.decode(t, i, false, 6, 10f, 25f, c)) {
                    continue;
                }
                assertEquals(10f, c.takeoffYaw, 0f);
                for (int k = 0; k < c.segments; k++) {
                    assertTrue(Float.isNaN(c.segYaw[k]), "keys-only segment with a yaw goal");
                }
            }
            for (int i = 0; i < TechniqueTemplates.size(t, true); i++) {
                if (TechniqueTemplates.decode(t, i, true, 6, 10f, 25f, c)) {
                    assertTrue(c.jump >= 0 && c.segments >= 1 && c.segments <= TechniqueTemplates.MAX_SEGMENTS);
                    float off = Math.abs(c.takeoffYaw - 10f);
                    assertTrue(off <= TechniqueTemplates.CORNER_MAX_ANGLE + 15f + 1e-3f, "takeoff yaw " + off);
                }
            }
        }
        // 45-degree: takeoff on the line to the target, then the view 45 degrees off with the matching strafe key
        assertTrue(TechniqueTemplates.decode(TechniqueTemplates.FORTY_FIVE, 0, true, 6, 10f, 25f, c));
        assertEquals(25f, c.takeoffYaw, 0f);
        assertEquals(25f + c.segStrafe[1] * TechniqueTemplates.FORTY_FIVE_ANGLE, c.segYaw[1], 1e-4f);
    }

    // ---- negatives ---------------------------------------------------------------------------------------------------

    @Test
    void anImpossibleJumpNeverPlansAndStaysInBudget() {
        ShapeGrid g = floor().cube(0, 19, 9); // 7 blocks of gap
        SupportRegion top = top(g, 0, 19, 9);
        for (boolean yawAllowed : new boolean[]{false, true}) {
            Run r = search(g, top, 0.5, 20, -4, yawAllowed, 300);
            assertFalse(r.found(), "an impossible jump planned");
            assertTrue(r.maxTicks() <= TechniqueSource.MAX_SIM_TICKS_PER_CALL, "ticks " + r.maxTicks());
            assertTrue(r.maxTicks() > TechniqueSource.MAX_SIM_TICKS_PER_CALL / 2, "the budget is used");
        }
    }

    @Test
    void noForwardNoScript() {
        ShapeGrid g = cornerScene(false);
        SupportRegion top = top(g, 1, 19, 4);
        Run r = search(g, top, 0.5, 20, -4, false, MAX_CALLS);
        assertTrue(r.found());
        assertFalse(r.lib().plan(r.from(), r.yaw(), true, 0, 0, top, false, RATE, true, 20f, g), "W released");
        assertEquals(0, r.lib().lastSimTicks());
        assertFalse(r.lib().plan(r.from(), r.yaw(), true, -1, 0, top, false, RATE, true, 20f, g), "S held");
    }

    @Test
    void aChangedTargetOrResetClearsTheKeptScript() {
        ShapeGrid g = cornerScene(false).cube(0, 19, 11);
        SupportRegion top = top(g, 1, 19, 4);
        Run r = search(g, top, 0.5, 20, -4, false, MAX_CALLS);
        assertTrue(r.found());
        SupportRegion far = top(g, 0, 19, 11);
        assertFalse(r.lib().plan(r.from(), r.yaw(), true, 1, 0, far, false, RATE, true, 20f, g),
                "the corner script is not offered for another target");
        r.lib().reset();
        assertTrue(r.lib().script().isEmpty());
    }

    @Test
    void keysOnlyNeverOffersYawEvenWhenTheKeptScriptUsedIt() {
        ShapeGrid g = cornerScene(false);
        SupportRegion top = top(g, 1, 19, 4);
        Run r = search(g, top, 0.5, 20, -4, true, MAX_CALLS);
        assertTrue(r.found());
        if (r.lib().plan(r.from(), r.yaw(), true, 1, 0, top, false, RATE, true, 20f, g)) {
            assertFalse(r.lib().script().usesYaw);
        }
    }
}
