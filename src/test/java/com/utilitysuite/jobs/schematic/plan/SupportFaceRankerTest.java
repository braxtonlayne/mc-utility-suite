package com.utilitysuite.jobs.schematic.plan;

import com.utilitysuite.core.AllocationProbe;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Build Assist's support face choice (item 4.3): the valid face nearest the look direction comes first. */
class SupportFaceRankerTest {

    private static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final int ALL = 0b111111;

    @Test
    void faceIndicesMatchDirectionOrder() {
        Direction[] dirs = Direction.values();
        assertEquals(SupportFaceRanker.FACES, dirs.length);
        for (int d = 0; d < dirs.length; d++) {
            assertEquals(dirs[d].getStepX(), SupportFaceRanker.STEP_X[d], dirs[d].name());
            assertEquals(dirs[d].getStepY(), SupportFaceRanker.STEP_Y[d], dirs[d].name());
            assertEquals(dirs[d].getStepZ(), SupportFaceRanker.STEP_Z[d], dirs[d].name());
        }
    }

    @Test
    void lookVectorMatchesMinecraftConvention() {
        // yaw 0 looks south (+z), yaw 90 west (-x), pitch 90 straight down
        assertEquals(1.0, SupportFaceRanker.lookZ(0, 0), 1e-9);
        assertEquals(-1.0, SupportFaceRanker.lookX(90, 0), 1e-9);
        assertEquals(-1.0, SupportFaceRanker.lookY(90), 1e-9);
    }

    @Test
    void picksTheFaceClosestToTheLookDirectionNotTheFirstDirection() {
        SupportFaceRanker r = new SupportFaceRanker();
        // Target at (0,64,0); player north of it, already looking at the north neighbour's face centre.
        double ex = 0.5, ey = 65.6, ez = -2.5;
        int n = r.rank(0, 64, 0, ex, ey, ez, 0.5 - ex, 64.5 - ey, 0.0 - ez,
                (1 << DOWN) | (1 << NORTH) | (1 << SOUTH));
        assertEquals(3, n);
        // The north neighbour's face is straight ahead; the first Direction (DOWN) would have been chosen before.
        assertEquals(NORTH, r.best());
        assertEquals(NORTH, r.face(0));
        assertEquals(SOUTH, r.face(2)); // the far side is the furthest turn
    }

    @Test
    void lookingDownPrefersTheFloor() {
        SupportFaceRanker r = new SupportFaceRanker();
        // Pillaring: the target is under the player, who looks straight down.
        r.rank(0, 64, 0, 0.5, 67.6, 0.5, SupportFaceRanker.lookX(0, 90), SupportFaceRanker.lookY(90),
                SupportFaceRanker.lookZ(0, 90), ALL & ~(1 << UP));
        assertEquals(DOWN, r.best());
    }

    @Test
    void turningTowardsASideFaceWins() {
        SupportFaceRanker r = new SupportFaceRanker();
        // Looking east (yaw -90): the east neighbour's face beats the west one even though WEST comes first.
        r.rank(0, 64, 0, -1.5, 64.6, 0.5, SupportFaceRanker.lookX(-90, 0), SupportFaceRanker.lookY(0),
                SupportFaceRanker.lookZ(-90, 0), (1 << WEST) | (1 << EAST));
        assertEquals(EAST, r.best());
        // Facing back west the order flips.
        r.rank(0, 64, 0, 2.5, 64.6, 0.5, SupportFaceRanker.lookX(90, 0), SupportFaceRanker.lookY(0),
                SupportFaceRanker.lookZ(90, 0), (1 << WEST) | (1 << EAST));
        assertEquals(WEST, r.best());
    }

    @Test
    void ranksEveryValidFaceOnceInDescendingOrder() {
        SupportFaceRanker r = new SupportFaceRanker();
        int n = r.rank(3, 70, -2, 0.2, 71.6, 0.3, 0.3, -0.5, -0.8, ALL);
        assertEquals(6, n);
        int seen = 0;
        double prev = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int d = r.face(i);
            seen |= 1 << d;
            double s = cosine(3, 70, -2, 0.2, 71.6, 0.3, 0.3, -0.5, -0.8, d);
            assertTrue(s <= prev + 1e-12, "descending at " + i);
            prev = s;
        }
        assertEquals(ALL, seen);
    }

    @Test
    void noValidFaceGivesMinusOne() {
        SupportFaceRanker r = new SupportFaceRanker();
        assertEquals(0, r.rank(0, 0, 0, 0, 1.6, 0, 0, 0, 1, 0));
        assertEquals(-1, r.best());
    }

    @Test
    void rankingAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        SupportFaceRanker r = new SupportFaceRanker();
        int[] sink = new int[1];
        long bytes = AllocationProbe.measure(10_000, () -> {
            r.rank(5, 64, -3, 1.2, 65.6, 0.4, SupportFaceRanker.lookX(37, 12), SupportFaceRanker.lookY(12),
                    SupportFaceRanker.lookZ(37, 12), 0b101101);
            sink[0] += r.best();
        });
        assertEquals(0, bytes, "support face ranking must not allocate");
    }

    private static double cosine(int tx, int ty, int tz, double ex, double ey, double ez, double lx, double ly,
                                 double lz, int d) {
        double px = tx + 0.5 + SupportFaceRanker.STEP_X[d] * 0.5 - ex;
        double py = ty + 0.5 + SupportFaceRanker.STEP_Y[d] * 0.5 - ey;
        double pz = tz + 0.5 + SupportFaceRanker.STEP_Z[d] * 0.5 - ez;
        return (px * lx + py * ly + pz * lz) / Math.sqrt(px * px + py * py + pz * pz)
                / Math.sqrt(lx * lx + ly * ly + lz * lz);
    }
}
