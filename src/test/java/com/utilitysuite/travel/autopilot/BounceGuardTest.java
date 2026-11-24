package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.BlockQuery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward obstacle check of the Nether highway bounce. The test highway runs along +z with its floor top at y=120
 * (floor blocks at y=119), walls at x=-2 and x=2, and a ceiling whose lowest block is at {@code ceiling}.
 */
class BounceGuardTest {

    private static final int FLOOR = 120;

    /** A tunnel with a few extra solid cells. */
    private static final class Tunnel implements BlockQuery {
        final int ceiling;
        final int[] extra; // x, y, z triples

        Tunnel(int ceiling, int... extra) {
            this.ceiling = ceiling;
            this.extra = extra;
        }

        @Override
        public boolean blocks(int x, int y, int z) {
            if (y < FLOOR || y >= ceiling || x <= -2 || x >= 2) {
                return true;
            }
            for (int i = 0; i < extra.length; i += 3) {
                if (extra[i] == x && extra[i + 1] == y && extra[i + 2] == z) {
                    return true;
                }
            }
            return false;
        }
    }

    private static BounceGuard grounded() {
        BounceGuard g = new BounceGuard();
        g.observe(FLOOR, true);
        return g;
    }

    private static BounceGuard.Verdict check(BounceGuard g, double y, double z, double speed, BlockQuery q) {
        return g.check(0.5, y, z, 0.0, 1.0, speed, q, q);
    }

    @Test
    void openHighwayIsClearInEveryPhaseOfABounce() {
        for (int ceiling : new int[]{FLOOR + 2, FLOOR + 3, FLOOR + 5}) {
            Tunnel q = new Tunnel(ceiling);
            BounceGuard g = grounded();
            double top = ceiling == FLOOR + 2 ? 0.2 : 1.25; // a two-high tunnel stops the jump at the roof
            for (double dy = 0.0; dy <= top; dy += 0.05) {
                g.observe(FLOOR + dy, dy == 0.0);
                assertEquals(BounceGuard.Verdict.CLEAR, check(g, FLOOR + dy, 0.5, 0.6, q),
                        "ceiling " + ceiling + " height " + dy);
            }
        }
    }

    @Test
    void pillarAndWallStopWithinTheStopDistance() {
        Tunnel pillar = new Tunnel(FLOOR + 4, 0, FLOOR, 6, 0, FLOOR + 1, 6);
        BounceGuard g = grounded();
        assertEquals(BounceGuard.Verdict.CLEAR, check(g, FLOOR, 0.5, 0.3, pillar), "5.5 blocks away: not yet");
        assertEquals(BounceGuard.Verdict.BLOCKED, check(g, FLOOR, 3.5, 0.3, pillar), "2.5 blocks away");
        // Same answer from the top of the jump and gliding low: the rows are taken from the floor, not the body.
        assertEquals(BounceGuard.Verdict.BLOCKED, check(g, FLOOR + 1.25, 3.5, 0.3, pillar));
        assertEquals(BounceGuard.Verdict.BLOCKED, check(g, FLOOR + 0.1, 3.5, 0.3, pillar));
    }

    @Test
    void stopDistanceStretchesWithSpeed() {
        Tunnel pillar = new Tunnel(FLOOR + 4, 0, FLOOR, 6, 0, FLOOR + 1, 6);
        BounceGuard g = grounded();
        assertEquals(BounceGuard.Verdict.CLEAR, check(g, FLOOR, 1.5, 0.6, pillar), "4.5 away at 0.6 b/t");
        assertEquals(BounceGuard.Verdict.BLOCKED, check(g, FLOOR, 1.5, 1.5, pillar), "4.5 away at 1.5 b/t");
        Tunnel far = new Tunnel(FLOOR + 4, 0, FLOOR + 1, 12);
        assertEquals(BounceGuard.Verdict.CLEAR, check(g, FLOOR, 0.5, 5.0, far), "nothing beyond the ray range");
    }

    @Test
    void loweredCeilingAtHeadHeightStops() {
        // The roof comes down to one block above the floor from z=5 on (a block at head height only).
        Tunnel q = new Tunnel(FLOOR + 3, 0, FLOOR + 1, 5, 0, FLOOR + 2, 5);
        assertEquals(BounceGuard.Verdict.BLOCKED, check(grounded(), FLOOR, 2.5, 0.5, q));
    }

    @Test
    void aLipTheJumpClearsDoesNotStop() {
        // One block high at z=4 with room on top, in a tall tunnel.
        Tunnel q = new Tunnel(FLOOR + 4, 0, FLOOR, 4);
        BounceGuard g = grounded();
        assertEquals(BounceGuard.Verdict.CLEAR, check(g, FLOOR, 0.5, 0.5, q));
        assertEquals(BounceGuard.Verdict.LIP, check(g, FLOOR, 1.5, 0.5, q));
        assertEquals(BounceGuard.Verdict.LIP, check(g, FLOOR + 0.9, 2.8, 0.5, q), "mid-jump, right in front of it");
        // Landed on top: the floor reference follows, and the way on is clear.
        g.observe(FLOOR + 1, true);
        assertEquals(BounceGuard.Verdict.CLEAR, check(g, FLOOR + 1, 4.5, 0.5, q));
    }

    @Test
    void aLipWithoutRoomOnTopStops() {
        // Two-high tunnel: the jump cannot rise onto the lip.
        Tunnel low = new Tunnel(FLOOR + 2, 0, FLOOR, 4);
        assertEquals(BounceGuard.Verdict.BLOCKED, check(grounded(), FLOOR, 1.5, 0.5, low));
        // Tall tunnel, but a block hangs two above the floor right over the lip.
        Tunnel capped = new Tunnel(FLOOR + 4, 0, FLOOR, 4, 0, FLOOR + 2, 4);
        assertEquals(BounceGuard.Verdict.BLOCKED, check(grounded(), FLOOR, 1.5, 0.5, capped));
        // A two-high step right behind the lip (stairs up that are too steep).
        Tunnel stairs = new Tunnel(FLOOR + 5, 0, FLOOR, 4, 0, FLOOR, 5, 0, FLOOR + 1, 5);
        assertEquals(BounceGuard.Verdict.BLOCKED, check(grounded(), FLOOR, 1.5, 0.5, stairs));
    }

    @Test
    void aLipThatStaysInFrontEventuallyStops() {
        Tunnel q = new Tunnel(FLOOR + 4, 0, FLOOR, 4);
        BounceGuard g = grounded();
        for (int t = 0; t < BounceGuard.LIP_STALL_TICKS; t++) {
            assertEquals(BounceGuard.Verdict.LIP, check(g, FLOOR, 3.2, 0.0, q), "tick " + t);
        }
        assertEquals(BounceGuard.Verdict.BLOCKED, check(g, FLOOR, 3.2, 0.0, q));
        // Passing lips one after another never adds up: a clear tick in between resets the count.
        BounceGuard h = grounded();
        for (int t = 0; t < 10 * BounceGuard.LIP_STALL_TICKS; t++) {
            double z = t % 10 == 0 ? -6.5 : 2.0;
            assertTrue(check(h, FLOOR, z, 0.5, q) != BounceGuard.Verdict.BLOCKED, "tick " + t);
        }
    }

    @Test
    void footRowUsesItsOwnQuery() {
        // Carpet at z=4 collides (body query) but is stepped over (foot query leaves it out).
        Tunnel body = new Tunnel(FLOOR + 2, 0, FLOOR, 4);
        Tunnel foot = new Tunnel(FLOOR + 2);
        assertEquals(BounceGuard.Verdict.CLEAR,
                grounded().check(0.5, FLOOR, 1.5, 0.0, 1.0, 0.5, foot, body));
    }

    @Test
    void raysFollowTheTravelDirection() {
        Tunnel q = new Tunnel(FLOOR + 3);
        BounceGuard g = grounded();
        assertEquals(BounceGuard.Verdict.CLEAR, g.check(0.5, FLOOR, 0.5, 0.0, 1.0, 0.5, q, q), "along the tunnel");
        assertEquals(BounceGuard.Verdict.BLOCKED, g.check(0.5, FLOOR, 0.5, 1.0, 0.0, 0.5, q, q), "into the wall");
        assertEquals(BounceGuard.Verdict.CLEAR, g.check(-0.7, FLOOR, 0.5, 0.0, 1.0, 0.5, q, q),
                "sliding along the wall, moving parallel to it");
    }

    @Test
    void withoutAFloorReferenceTheBodyHeightIsUsed() {
        Tunnel pillar = new Tunnel(FLOOR + 4, 0, FLOOR, 6, 0, FLOOR + 1, 6);
        BounceGuard g = new BounceGuard(); // started in the air
        assertEquals(BounceGuard.Verdict.BLOCKED, check(g, FLOOR + 0.3, 3.5, 0.3, pillar));
        // A stale reference (the body is far below the remembered floor) is ignored too.
        BounceGuard stale = new BounceGuard();
        stale.observe(FLOOR + 10, true);
        assertEquals(FLOOR, stale.baseY(FLOOR));
        assertEquals(FLOOR + 10, stale.baseY(FLOOR + 11.2));
        stale.reset();
        assertEquals(FLOOR + 11.2, stale.baseY(FLOOR + 11.2));
    }

    @Test
    void headRowStartingInsideABlockIsNotAnObstacle() {
        // Gliding (0.6 tall) under a roof one block above an unknown floor: the head row is inside the roof.
        Tunnel q = new Tunnel(FLOOR + 1);
        assertEquals(BounceGuard.Verdict.CLEAR, check(new BounceGuard(), FLOOR + 0.2, 0.5, 0.5, q));
    }

    @Test
    void allocatesNothingPerTick() {
        Assumptions.assumeTrue(AllocationProbe.supported(), "thread allocation counters unavailable");
        Tunnel q = new Tunnel(FLOOR + 4, 0, FLOOR, 30, 0, FLOOR, 60, 0, FLOOR + 1, 60);
        BounceGuard g = grounded();
        int[] i = new int[1];
        int[] sink = new int[1];
        long bytes = AllocationProbe.measure(50_000, () -> {
            int t = i[0]++ % 640;
            double z = t * 0.1; // clear, a lip, then a pillar
            double y = FLOOR + (t % 12) * 0.1;
            g.observe(y, t % 12 == 0);
            sink[0] += g.check(0.5, y, z, 0.02, 1.0, 0.6, q, q).ordinal();
        });
        assertTrue(bytes < 1_024, "guard allocated " + bytes + " bytes");
    }
}
