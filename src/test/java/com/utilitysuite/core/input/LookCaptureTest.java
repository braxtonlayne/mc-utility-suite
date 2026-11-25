package com.utilitysuite.core.input;

import com.utilitysuite.ui.radial.RadialSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookCaptureTest {

    @AfterEach
    void tearDown() {
        LookCapture.end();
    }

    @Test
    void heldCameraSwallowsTheTurnAndAccumulatesTheMotion() {
        assertEquals(5.0, ManualInputHooks.onTurnYaw(5.0)); // not held: untouched (no hooks instance in tests)
        LookCapture.begin();
        assertEquals(0.0, ManualInputHooks.onTurnYaw(12.0));
        assertEquals(0.0, ManualInputHooks.onTurnPitch(-4.0));
        assertEquals(0.0, ManualInputHooks.onTurnYaw(3.0));
        assertEquals(15.0, LookCapture.takeX());
        assertEquals(-4.0, LookCapture.takeY());
        assertEquals(0.0, LookCapture.takeX());
    }

    @Test
    void holdLapsesWithoutRenewal() {
        LookCapture.begin();
        for (int i = 0; i < LookCapture.TTL_TICKS - 1; i++) {
            LookCapture.tick();
            assertTrue(LookCapture.active());
        }
        LookCapture.renew();
        for (int i = 0; i < LookCapture.TTL_TICKS; i++) {
            LookCapture.tick();
        }
        assertFalse(LookCapture.active());
        assertEquals(7.0, ManualInputHooks.onTurnYaw(7.0));
    }

    @Test
    void wheelHotPathAllocatesNothing() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        RadialSelector s = new RadialSelector(8, 40);
        LookCapture.begin();
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long before = mx.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 10_000; i++) {
                ManualInputHooks.onTurnYaw(i % 7 - 3);
                ManualInputHooks.onTurnPitch(i % 5 - 2);
                s.add(LookCapture.takeX(), LookCapture.takeY());
                LookCapture.renew();
            }
            best = Math.min(best, mx.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before);
        }
        assertEquals(0L, best);
    }
}
