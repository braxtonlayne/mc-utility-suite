package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.NavigationStatus;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.walk.ReachWalker;
import com.utilitysuite.jobs.schematic.walk.StandingSpotFinder;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Walk to reach blocks": standing-spot selection on a voxel grid and the stall -> walk -> resume state machine. */
class WalkToReachTest {

    private static final double REACH = 4.5;

    // ---- standing spots ---------------------------------------------------------------------------------------------

    private static SchematicVolume slab3x3() {
        SchematicVolume.Builder b = SchematicVolume.builder("pad", "test", 3, 1, 3);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                b.set(x, 0, z, "minecraft:stone");
            }
        }
        return b.build();
    }

    private static FakeWorld ground(SchematicVolume v) {
        FakeWorld w = new FakeWorld(v);
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                w.set(x, 0, z, "minecraft:stone");
            }
        }
        return w;
    }

    @Test
    void picksReachableSupportedSpotOutsideTheBuild() {
        SchematicVolume v = slab3x3(); // build cells at y=1, x/z 0..2 (origin 0,1,0); all still missing
        FakeWorld w = ground(v);
        long[] out = new long[4];
        int n = new StandingSpotFinder(4).find(w, v, 0, 1, 0, 0, 1, 0, -6, 1, 0.5, REACH, out);
        assertTrue(n > 0, "a spot next to the pad");
        double prev = -1;
        for (int i = 0; i < n; i++) {
            int x = BlockPos.getX(out[i]), y = BlockPos.getY(out[i]), z = BlockPos.getZ(out[i]);
            assertEquals(1, y, "stands on the ground");
            assertFalse(x >= 0 && x <= 2 && z >= 0 && z <= 2, "never inside the build's own cells");
            assertEquals(PlacementStatus.W_OTHER, w.probe(x, y - 1, z, -1));
            double ex = x + 0.5 - 0.5, ey = y + StandingSpotFinder.EYE_HEIGHT - 1.5, ez = z + 0.5 - 0.5;
            assertTrue(ex * ex + ey * ey + ez * ez <= REACH * REACH, "eye within reach of the target");
            double d = (x + 0.5 + 6) * (x + 0.5 + 6) + (z + 0.5 - 0.5) * (z + 0.5 - 0.5);
            assertTrue(d >= prev, "sorted by distance to the player");
            prev = d;
        }
        assertTrue(BlockPos.getX(out[0]) < 0, "nearest spot is on the player's side");
    }

    @Test
    void noSpotWhenBoxedIn() {
        SchematicVolume v = slab3x3();
        FakeWorld w = new FakeWorld(v);
        for (int x = -8; x <= 8; x++) {
            for (int y = -6; y <= 8; y++) {
                for (int z = -8; z <= 8; z++) {
                    w.set(x, y, z, "minecraft:stone");
                }
            }
        }
        w.set(0, 1, 0, "minecraft:air"); // only the target cell is open
        long[] out = new long[4];
        assertEquals(0, new StandingSpotFinder(4).find(w, v, 0, 1, 0, 0, 1, 0, -3, 1, 0, REACH, out));
    }

    @Test
    void needsTwoBlocksOfHeadroom() {
        FakeWorld w = ground(slab3x3());
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                w.set(x, 2, z, "minecraft:stone"); // 1-high crawlspace everywhere
            }
        }
        long[] out = new long[4];
        assertEquals(0, new StandingSpotFinder(4).find(w, null, 0, 1, 0, 0, 1, 0, -3, 1, 0, REACH, out));
    }

    @Test
    void rejectsSpotsThatOnlySeeTheBackOfTheSupport() {
        // Target hangs off the side of a wall at x=1 (its only support is EAST); the clicked face looks west, so a
        // spot east of the wall (behind it) must not be chosen.
        FakeWorld w = ground(null);
        for (int y = 1; y <= 4; y++) {
            w.set(1, y, 0, "minecraft:stone");
        }
        long[] out = new long[4];
        int n = new StandingSpotFinder(4).find(w, null, 0, 0, 0, 0, 3, 0, 6, 1, 0.5, REACH, out);
        assertTrue(n > 0);
        for (int i = 0; i < n; i++) {
            assertTrue(BlockPos.getX(out[i]) <= 0, "stands on the open side of the wall");
        }
    }

    // ---- state machine ----------------------------------------------------------------------------------------------

    static final class FakeRun implements NavigationRun {
        boolean active = true;
        NavigationStatus status = NavigationStatus.PATHING;
        LeaseEndReason reason;
        boolean cancelled;

        void end(NavigationStatus s, LeaseEndReason why) {
            active = false;
            status = s;
            reason = why;
        }

        @Override public boolean isActive() { return active; }
        @Override public NavigationStatus status() { return status; }
        @Override public LeaseEndReason endReason() { return reason; }
        @Override public void cancel() { cancelled = true; active = false; status = NavigationStatus.IDLE; }
    }

    static final class FakeHost implements ReachWalker.Host {
        boolean available = true;
        boolean refuse;
        final int[] cells = {7, 9};
        final List<String> calls = new ArrayList<>();
        FakeRun lastRun;

        @Override public boolean navigatorAvailable() { return available; }

        @Override
        public int nearestStalledCell(BitSet skip) {
            for (int c : cells) {
                if (!skip.get(c)) {
                    return c;
                }
            }
            return -1;
        }

        @Override
        public int standingSpots(int cell, long[] out) {
            out[0] = BlockPos.asLong(cell, 64, 0);
            return 1;
        }

        @Override public void releaseOwnLease() { calls.add("release"); }

        @Override
        public Optional<NavigationRun> startWalk(int x, int y, int z) {
            calls.add("walk " + x);
            if (refuse) {
                return Optional.empty();
            }
            lastRun = new FakeRun();
            return Optional.of(lastRun);
        }
    }

    private static final long HERE = BlockPos.asLong(0, 64, 0);
    private static final long ELSEWHERE = BlockPos.asLong(3, 64, 0);

    private static boolean stallFor(ReachWalker walker, FakeHost host, int ticks, long pos) {
        boolean walking = false;
        for (int i = 0; i < ticks; i++) {
            walking = walker.tick(true, pos, host);
        }
        return walking;
    }

    @Test
    void stallReleasesLeaseThenWalksAndResumesOnArrival() {
        ReachWalker walker = new ReachWalker(40, 1200);
        FakeHost host = new FakeHost();
        assertFalse(stallFor(walker, host, 39, HERE), "not before the stall threshold");
        assertTrue(host.calls.isEmpty());
        assertTrue(walker.tick(true, HERE, host), "walk starts on the 40th stalled tick");
        assertEquals(List.of("release", "walk 7"), host.calls, "own lease released before the walk is requested");
        assertTrue(walker.tick(false, HERE, host), "hands-off while walking");
        host.lastRun.end(NavigationStatus.ARRIVED, null);
        assertFalse(walker.tick(false, ELSEWHERE, host), "arrived: Build Assist resumes");
        assertEquals(ReachWalker.Phase.IDLE, walker.phase());
    }

    @Test
    void notStalledResetsTheCounter() {
        ReachWalker walker = new ReachWalker(40, 1200);
        FakeHost host = new FakeHost();
        stallFor(walker, host, 30, HERE);
        walker.tick(false, HERE, host); // placed something
        assertFalse(stallFor(walker, host, 30, HERE));
        stallFor(walker, host, 30, ELSEWHERE); // the user walks around: counter restarts per block
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void failedRunMarksCellUnreachableAndTriesTheNext() {
        ReachWalker walker = new ReachWalker(40, 1200);
        FakeHost host = new FakeHost();
        assertTrue(stallFor(walker, host, 40, HERE));
        host.lastRun.end(NavigationStatus.FAILED, null);
        assertFalse(walker.tick(true, HERE, host));
        assertTrue(walker.isUnreachable(7));
        assertTrue(stallFor(walker, host, 40, HERE));
        assertEquals("walk 9", host.calls.get(host.calls.size() - 1));
    }

    @Test
    void cellStillStuckAfterArrivalIsSkippedNextTime() {
        ReachWalker walker = new ReachWalker(40, 1200);
        FakeHost host = new FakeHost();
        stallFor(walker, host, 40, HERE);
        host.lastRun.end(NavigationStatus.ARRIVED, null);
        walker.tick(true, ELSEWHERE, host);
        assertTrue(stallFor(walker, host, 40, ELSEWHERE));
        assertEquals("walk 9", host.calls.get(host.calls.size() - 1));
        assertTrue(walker.isUnreachable(7));
    }

    @Test
    void manualOverrideAndStopAllPauseUntilThePlayerMoves() {
        for (LeaseEndReason why : new LeaseEndReason[]{LeaseEndReason.MANUAL_OVERRIDE, LeaseEndReason.PREEMPTED,
                LeaseEndReason.STOP_ALL}) {
            ReachWalker walker = new ReachWalker(40, 1200);
            FakeHost host = new FakeHost();
            stallFor(walker, host, 40, HERE);
            host.lastRun.end(NavigationStatus.IDLE, why);
            assertFalse(walker.tick(true, HERE, host));
            assertEquals(ReachWalker.Phase.PAUSED, walker.phase(), why.name());
            int before = host.calls.size();
            assertFalse(stallFor(walker, host, 400, HERE), "no restart while the player stays put");
            assertEquals(before, host.calls.size());
            assertFalse(walker.isUnreachable(7), "a cut-short walk is not a pathing failure");
            walker.tick(true, ELSEWHERE, host); // the user moved
            assertEquals(ReachWalker.Phase.IDLE, walker.phase());
            assertTrue(stallFor(walker, host, 40, ELSEWHERE), "walks again after a fresh stall");
        }
    }

    @Test
    void resetCancelsTheRunAndClearsPause() {
        ReachWalker walker = new ReachWalker(40, 1200);
        FakeHost host = new FakeHost();
        stallFor(walker, host, 40, HERE);
        FakeRun run = host.lastRun;
        walker.reset();
        assertTrue(run.cancelled);
        assertEquals(ReachWalker.Phase.IDLE, walker.phase());
    }

    @Test
    void withoutNavigatorKeepsTodaysBehaviour() {
        ReachWalker walker = new ReachWalker(40, 1200);
        FakeHost host = new FakeHost();
        host.available = false;
        assertFalse(stallFor(walker, host, 200, HERE));
        assertEquals(ReachWalker.Phase.UNAVAILABLE, walker.phase());
        assertTrue(host.calls.isEmpty(), "never releases the lease or walks");
    }

    @Test
    void refusedWalkRetriesLater() {
        ReachWalker walker = new ReachWalker(40, 1200);
        FakeHost host = new FakeHost();
        host.refuse = true;
        stallFor(walker, host, 40, HERE);
        assertEquals(2, host.calls.size());
        stallFor(walker, host, 100, HERE);
        assertEquals(2, host.calls.size(), "waits before asking again");
        host.refuse = false;
        assertTrue(stallFor(walker, host, 40, HERE));
    }
}
