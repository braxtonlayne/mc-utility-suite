package com.utilitysuite.tools.packetlab;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AcousticRadar} and {@link ServerPhysicsMonitor}. */
class AcousticAndCalibrationTest {

    @Test
    void acousticRadarWrapsAndExpiresOldEntries() {
        AcousticRadar radar = new AcousticRadar(4);
        assertEquals(4, radar.capacity());

        radar.recordSound(1.0, 64.0, 2.0, 1000L, AcousticRadar.CONTAINER);
        radar.recordSound(3.0, 65.0, 4.0, 2000L, AcousticRadar.PORTAL);
        radar.recordSound(5.0, 66.0, 6.0, 3000L, AcousticRadar.EXPLOSION);
        radar.recordSound(7.0, 67.0, 8.0, 4000L, AcousticRadar.PISTON);
        // Wraps and overwrites the 1000L CONTAINER entry
        radar.recordGlobalBearing(3.0, 4.0, 5000L, AcousticRadar.PLAYER);

        assertEquals(4, radar.size());

        int[] count = {0};
        boolean[] sawGlobal = {false};
        int visited = radar.forEachActive(6000L, 2500L, (global, x, y, z, dx, dz, ts, cat) -> {
            count[0]++;
            assertTrue(ts >= 3500L, "expired entries must be skipped");
            if (global) {
                sawGlobal[0] = true;
                assertEquals(0.6, dx, 1.0e-6);
                assertEquals(0.8, dz, 1.0e-6);
                assertEquals(AcousticRadar.PLAYER, cat);
            } else {
                assertEquals(7.0, x, 1.0e-6);
                assertEquals(AcousticRadar.PISTON, cat);
            }
        });
        assertEquals(2, visited);
        assertEquals(2, count[0]);
        assertTrue(sawGlobal[0]);

        radar.clear();
        assertEquals(0, radar.size());
    }

    @Test
    void acousticRadarIterationAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        AcousticRadar radar = new AcousticRadar(64);
        for (int i = 0; i < 64; i++) {
            radar.recordSound(i, 64, i, 1000L + i, AcousticRadar.OTHER);
        }
        AcousticRadar.Visitor noopVisitor = (g, x, y, z, dx, dz, ts, cat) -> {
        };
        long bytes = AllocationProbe.measure(10_000, () -> {
            radar.recordSound(1.0, 64.0, 2.0, 2000L, AcousticRadar.CONTAINER);
            radar.forEachActive(2000L, 5000L, noopVisitor);
        });
        assertTrue(bytes < 16 * 1024, "allocated " + bytes + " bytes");
    }

    @Test
    void serverPhysicsMonitorNoOpDefaultsToZeroAndFalse() {
        ServerPhysicsMonitor.setCurrent(null);
        ServerPhysicsMonitor current = ServerPhysicsMonitor.current();
        assertEquals(0.0, current.extraLeadTicks(), 1.0e-9);
        assertEquals(0.0, current.movementSafetyMargin(), 1.0e-9);
        assertFalse(current.shouldStepDownPreset());
    }

    @Test
    void serverPhysicsMonitorComputesRttJitterAndLead() {
        ServerPhysicsMonitor monitor = new ServerPhysicsMonitor();
        // 19 samples at 40ms, 1 spike at 140ms -> median 40ms, p95 140ms, jitter 100ms -> 2.0 extra lead ticks
        for (int i = 0; i < 19; i++) {
            monitor.recordRttMs(40.0);
        }
        monitor.recordRttMs(140.0);

        assertEquals(40.0, monitor.rttMedianMs(), 1.0e-6);
        assertEquals(140.0, monitor.rttP95Ms(), 1.0e-6);
        assertEquals(2.0, monitor.extraLeadTicks(), 1.0e-6);
        assertTrue(monitor.movementSafetyMargin() > 0.0);
    }

    @Test
    void serverPhysicsMonitorTickJitterAndMarginCapped() {
        ServerPhysicsMonitor monitor = new ServerPhysicsMonitor();
        for (int i = 0; i < 10; i++) {
            monitor.recordTickIntervalNs(150_000_000L); // 100ms deviation from 50ms ideal
        }
        assertEquals(100.0, monitor.tickJitterMs(), 1.0e-6);
        assertTrue(monitor.movementSafetyMargin() > 0.0);
        assertTrue(monitor.movementSafetyMargin() <= 0.15);
    }

    @Test
    void serverPhysicsMonitorSetbackStepDownRespectsWindowAndCooldown() {
        ServerPhysicsMonitor monitor = new ServerPhysicsMonitor();
        monitor.recordSetback(1_000L);
        assertFalse(monitor.shouldStepDownPreset(), "single setback should not trigger step-down");

        // Second setback > 10s later: should not trigger
        monitor.recordSetback(12_000L);
        assertFalse(monitor.shouldStepDownPreset());

        // Third setback within 10s of 12_000L: should trigger once
        monitor.recordSetback(16_000L);
        assertTrue(monitor.shouldStepDownPreset());
        assertFalse(monitor.shouldStepDownPreset(), "step-down must clear after read");

        // Another burst inside 15s cooldown (16_000 + 15_000 = 31_000): suppressed
        monitor.recordSetback(20_000L);
        assertFalse(monitor.shouldStepDownPreset());

        // After cooldown expires (31_000+): two setbacks within 10s trigger again
        monitor.recordSetback(32_000L);
        monitor.recordSetback(35_000L);
        assertTrue(monitor.shouldStepDownPreset());
    }
}
