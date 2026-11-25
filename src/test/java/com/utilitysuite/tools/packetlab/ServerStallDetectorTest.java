package com.utilitysuite.tools.packetlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.api.IServerHealth;
import com.utilitysuite.core.api.Services;
import org.junit.jupiter.api.Test;

class ServerStallDetectorTest {

    static final long TICK_NS = 50_000_000L;

    /** Runs {@code ticks} client ticks at 20 Hz; a time packet every 20 ticks when {@code packets}. */
    static long run(ServerStallDetector d, long t, long[] game, int ticks, boolean packets, int gamePerPacket) {
        for (int i = 0; i < ticks; i++) {
            t += TICK_NS;
            if (packets && i % 20 == 19) {
                game[0] += gamePerPacket;
                d.onServerTime(t, game[0]);
            }
            d.tick(t, 0.0);
        }
        return t;
    }

    @Test
    void healthyServerNeverPauses() {
        ServerStallDetector d = new ServerStallDetector();
        long[] g = {1000};
        run(d, 0, g, 400, true, 20);
        assertFalse(d.paused());
        assertEquals(20.0, d.tps(), 0.5);
        assertEquals(0, d.pauses());
    }

    @Test
    void noDecisionBeforeTheFirstPacket() {
        ServerStallDetector d = new ServerStallDetector();
        assertFalse(d.tick(10_000_000_000L, 0.0));
    }

    @Test
    void stallPausesAndResumesOnlyAfterStableTicks() {
        ServerStallDetector d = new ServerStallDetector();
        long[] g = {1000};
        long t = run(d, 0, g, 100, true, 20);
        t = run(d, t, g, 60, false, 0); // 3 s without a time packet
        assertTrue(d.paused());
        assertEquals("stall", d.reason());
        // packets return: still paused until stableTicks healthy ticks went by
        t += TICK_NS;
        g[0] += 60;
        d.onServerTime(t, g[0]);
        int ticks = 0;
        while (d.tick(t += TICK_NS, 0.0)) {
            ticks++;
            if (ticks % 20 == 0) {
                g[0] += 20;
                d.onServerTime(t, g[0]);
            }
            assertTrue(ticks < 500);
        }
        assertEquals(d.stableTicks, ticks + 1);
        assertEquals(1, d.pauses());
    }

    @Test
    void lowTpsPauses() {
        ServerStallDetector d = new ServerStallDetector();
        long[] g = {1000};
        run(d, 0, g, 200, true, 10); // 10 game ticks per real second
        assertTrue(d.paused());
        assertEquals("low tps", d.reason());
        assertTrue(d.tps() < 15.0);
    }

    @Test
    void clientJitterPauses() {
        ServerStallDetector d = new ServerStallDetector();
        long[] g = {1000};
        long t = run(d, 0, g, 40, true, 20);
        assertTrue(d.tick(t + TICK_NS, 200.0));
        assertEquals("jitter", d.reason());
    }

    @Test
    void briefFlickerDoesNotResumeEarly() {
        ServerStallDetector d = new ServerStallDetector();
        d.stableTicks = 10;
        long[] g = {1000};
        long t = run(d, 0, g, 40, true, 20);
        assertTrue(d.tick(t += TICK_NS, 500.0));
        for (int i = 0; i < 5; i++) {
            assertTrue(d.tick(t += TICK_NS, 0.0));
        }
        assertTrue(d.tick(t += TICK_NS, 500.0)); // relapse: the stable count starts over
        for (int i = 0; i < 9; i++) {
            assertTrue(d.tick(t += TICK_NS, 0.0));
        }
        assertFalse(d.tick(t + TICK_NS, 0.0));
    }

    @Test
    void signalIsHealthyWithoutAGuardOrWhileTheGuardIsOff() {
        Services.resetForTests();
        assertFalse(Services.serverHealth().pauseHighRisk());
        LagGuard g = new LagGuard();
        Services.registerServerHealth(g);
        IServerHealth h = Services.serverHealth();
        g.detector().onServerTime(0, 100);
        g.detector().tick(5_000_000_000L, 0.0);
        assertTrue(g.detector().paused());
        assertFalse(h.pauseHighRisk()); // off by default
        g.setEnabled(true);
        g.detector().onServerTime(0, 100);
        g.detector().tick(5_000_000_000L, 0.0);
        assertTrue(h.pauseHighRisk());
        Services.resetForTests();
    }

    /** /tick freeze: time packets keep arriving but the game time stands still (playtest 2026-11-25). */
    @Test
    void frozenWorldPausesWithinTwoPacketsAndRecovers() {
        ServerStallDetector d = new ServerStallDetector();
        long[] g = {1000};
        long t = run(d, 0, g, 100, true, 20);
        assertFalse(d.paused());
        t = run(d, t, g, 40, true, 0); // two packets without game-time progress
        assertTrue(d.paused());
        assertEquals("low tps", d.reason());
        run(d, t, g, 200, true, 20); // unfrozen
        assertFalse(d.paused());
        assertEquals(20.0, d.tps(), 2.0);
    }

    @Test
    void tickIsAllocationFree() {
        ServerStallDetector d = new ServerStallDetector();
        d.onServerTime(0, 0);
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long t = 0;
        for (int i = 0; i < 10_000; i++) {
            d.tick(t += TICK_NS, 0.0); // warm up
        }
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 10_000; i++) {
            d.tick(t += TICK_NS, i % 7 == 0 ? 100.0 : 0.0);
            if (i % 20 == 0) {
                d.onServerTime(t, i);
            }
        }
        assertTrue(mx.getThreadAllocatedBytes(id) - before < 1024);
    }
}
