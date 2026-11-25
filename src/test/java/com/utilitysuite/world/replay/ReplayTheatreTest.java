package com.utilitysuite.world.replay;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayTheatreTest {

    private static void fill(ReplayRing r, int ticks) {
        for (int t = 0; t < ticks; t++) {
            r.beginFrame(t);
            r.addEntity(1, t * 0.5, 64, 0, 170f + t * 4f, 0f, 0.5f, 0f, 0f, 0.6f, 1.8f, ReplayRing.F_SELF);
            if (t % 2 == 0) {
                r.addEntity(7, 10, 64, t, 0f, 0f, 0f, 0f, 1f, 0.6f, 1.95f, ReplayRing.F_HOSTILE);
            }
        }
    }

    @Test
    void ringKeepsLastThirtySecondsOldestFirst() {
        ReplayRing r = new ReplayRing();
        fill(r, ReplayRing.FRAMES + 50);
        assertEquals(ReplayRing.FRAMES, r.frames());
        assertEquals(50, r.tickOf(0));
        assertEquals(ReplayRing.FRAMES + 49, r.tickOf(r.frames() - 1));
        int i = r.at(0, r.find(0, 1));
        assertEquals(25.0, r.x(i), 1e-9);
    }

    @Test
    void frameRejectsOverflowAndFreezeStopsWrites() {
        ReplayRing r = new ReplayRing();
        r.beginFrame(0);
        for (int k = 0; k < ReplayRing.SLOTS; k++) {
            assertTrue(r.addEntity(k, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, (byte) 0));
        }
        assertFalse(r.addEntity(99, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, (byte) 0));
        r.setFrozen(true);
        r.beginFrame(1);
        r.addEvent(1, ReplayRing.EV_HIT_TAKEN, 2f, 3);
        assertEquals(1, r.frames());
        assertEquals(0, r.events());
    }

    @Test
    void interpolatesPositionAndYawTheShortWay() {
        ReplayRing r = new ReplayRing();
        fill(r, 10);
        double[] out = new double[5];
        assertTrue(r.pose(2.5, 1, out));
        assertEquals(1.25, out[0], 1e-9);
        // yaw 178 -> 182 lerps through 180, not back through 0
        assertEquals(180.0, out[3], 1e-6);
        // entity 7 exists only on even frames: frame 3 falls back to frame 2's pose
        assertTrue(r.pose(2.5, 7, out));
        assertEquals(2.0, out[2], 1e-9);
        assertFalse(r.pose(2.5, 42, out));
    }

    @Test
    void yawWrapCrossesMinus180() {
        ReplayRing r = new ReplayRing();
        r.beginFrame(0);
        r.addEntity(1, 0, 0, 0, 170f, 0, 0, 0, 0, 1, 1, (byte) 0);
        r.beginFrame(1);
        r.addEntity(1, 0, 0, 0, -170f, 0, 0, 0, 0, 1, 1, (byte) 0);
        double[] out = new double[5];
        r.pose(0.5, 1, out);
        assertEquals(180.0, out[3], 1e-6);
    }

    @Test
    void tradeTimingFromEvents() {
        ReplayRing r = new ReplayRing();
        r.addEvent(100, ReplayRing.EV_HIT_DEALT, 0f, 7);
        r.addEvent(104, ReplayRing.EV_HIT_TAKEN, 3f, 7);
        assertEquals(Integer.MIN_VALUE, r.tradeGap(102));
        assertEquals(4, r.tradeGap(110));
        assertEquals(100, r.lastEventAtOrBefore(ReplayRing.EV_HIT_DEALT, 103));
    }

    @Test
    void recordingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ReplayRing r = new ReplayRing();
        double[] out = new double[5];
        long[] t = {0};
        long bytes = AllocationProbe.measure(100_000, () -> {
            long k = t[0]++;
            r.beginFrame(k);
            for (int s = 0; s < 8; s++) {
                r.addEntity(s, k, 64, s, k, 0, 0.1f, 0, 0, 0.6f, 1.8f, (byte) 0);
            }
            if ((k & 15) == 0) {
                r.addEvent(k, ReplayRing.EV_HIT_TAKEN, 1f, 3);
            }
            r.pose(r.frames() * 0.5, 3, out);
        });
        assertTrue(bytes < 4096, "recording allocated " + bytes + " bytes");
    }

    @Test
    void timelinePlaysScrubsAndStops() {
        ReplayTimeline tl = new ReplayTimeline();
        tl.load(201);
        assertEquals(200, tl.position(), 1e-9);
        assertFalse(tl.playing());
        tl.togglePlay(); // at the end: restarts from 0
        assertTrue(tl.playing());
        assertEquals(0, tl.position(), 1e-9);
        tl.advance(1.0);
        assertEquals(20, tl.position(), 1e-9);
        tl.slower();
        tl.advance(1.0);
        assertEquals(30, tl.position(), 1e-9);
        tl.advance(100);
        assertEquals(200, tl.position(), 1e-9);
        assertFalse(tl.playing());
        tl.scrubTo(0.25);
        assertEquals(50, tl.position(), 1e-9);
        assertEquals(7.5, tl.secondsBeforeEnd(), 1e-9);
        tl.step(-60);
        assertEquals(0, tl.position(), 1e-9);
    }

    @Test
    void splitsTimeARunAndCompareAgainstGhost() {
        ParkourSplits s = new ParkourSplits();
        s.setStart(0, 64, 0);
        s.setFinish(20, 64, 0);
        s.setRadius(1.0);
        // first run: 1 block per tick -> stands at start, leaves, reaches x>=19
        assertFalse(s.tick(0, 64, 0));
        assertEquals(ParkourSplits.State.ARMED, s.state());
        boolean done = false;
        for (int x = 2; x <= 20 && !done; x++) {
            done = s.tick(x, 64, 0);
        }
        assertTrue(done);
        assertEquals(17, s.lastRunTicks());
        assertEquals(17, s.bestTicks());
        assertTrue(s.ghostLength() > 0);
        // second run, half speed: behind the ghost mid-run
        s.tick(0, 64, 0);
        s.tick(1.5, 64, 0);
        for (int k = 1; k <= 10; k++) {
            s.tick(1.5 + k * 0.5, 64, 0);
        }
        double delta = s.liveDelta(6.5, 64, 0);
        assertTrue(delta > 0, "slower run is behind: " + delta);
        double[] g = new double[3];
        assertTrue(s.ghostAt(3, g));
        assertEquals(5.0, g[0], 1e-9);
    }

    @Test
    void projectorConventionsAndRearView() {
        ViewProjector p = new ViewProjector();
        float[] o = new float[3];
        p.set(0, 0, 0, 0f, 0f, 90, 1.0);
        assertTrue(p.project(0, 0, 5, o)); // yaw 0 faces +Z
        assertEquals(0f, o[0], 1e-6);
        assertTrue(p.project(-1, 0, 5, o)); // -X is to the right when facing +Z
        assertTrue(o[0] > 0);
        assertTrue(p.project(0, 1, 5, o));
        assertTrue(o[1] > 0);
        assertFalse(p.project(0, 0, -5, o));
        p.setRearView(0, 0, 0, 0f, 90, 1.0);
        assertTrue(p.project(0, -0.3, -5, o)); // behind the player is in front of the rear camera
        float[] segs = new float[48];
        int n = p.projectBox(-0.3, -1, -5.3, 0.3, 1, -4.7, segs, 0);
        assertEquals(48, n);
        p.setOrbit(0, 0, 0, 0f, 0f, 10, 70, 1.0);
        assertEquals(-10, p.camZ(), 1e-9);
    }

    @Test
    void frameGateRefreshesEveryN() {
        FrameGate g = new FrameGate();
        g.setEvery(3);
        int passes = 0;
        for (int i = 0; i < 9; i++) {
            if (g.tick()) {
                passes++;
            }
        }
        assertEquals(3, passes);
    }
}
