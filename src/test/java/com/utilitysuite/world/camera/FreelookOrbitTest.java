package com.utilitysuite.world.camera;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreelookOrbitTest {

    @Test
    void pitchIsClampedToStraightUpAndDown() {
        FreelookOrbit o = new FreelookOrbit();
        o.addTurn(0, 200, 30);
        assertEquals(90.0, o.cameraPitch(30), 1e-9);
        assertEquals(60.0, o.pitch(), 1e-9);
        o.addTurn(0, -500, 30);
        assertEquals(-90.0, o.cameraPitch(30), 1e-9);
    }

    @Test
    void yawWrapsAndAccumulates() {
        FreelookOrbit o = new FreelookOrbit();
        o.addTurn(100, 0, 0);
        o.addTurn(100, 0, 0);
        assertEquals(-160.0, o.yaw(), 1e-9);
    }

    @Test
    void releaseEasesBackWithoutSnapping() {
        FreelookOrbit o = new FreelookOrbit();
        o.addTurn(120, 40, 0);
        double dt = 1.0 / 60.0;
        double prev = Math.hypot(o.yaw(), o.pitch());
        int frames = 0;
        while (o.active() && frames < 600) {
            o.ease(dt);
            double mag = Math.hypot(o.yaw(), o.pitch());
            double step = prev - mag;
            assertTrue(step <= FreelookOrbit.MAX_RETURN_DEG_PER_S * dt + 1e-9, "step " + step);
            assertTrue(mag <= prev);
            prev = mag;
            frames++;
        }
        assertFalse(o.active());
        // 126 degrees at <= 450 deg/s takes at least 0.28 s (17 frames); the minimum speed bounds it above.
        assertTrue(frames >= 17, "frames " + frames);
        assertTrue(frames < 200, "frames " + frames);
    }

    @Test
    void easeKeepsDirection() {
        FreelookOrbit o = new FreelookOrbit();
        o.addTurn(30, -10, 0);
        o.ease(0.05);
        assertTrue(o.yaw() > 0 && o.yaw() < 30);
        assertTrue(o.pitch() < 0 && o.pitch() > -10);
        assertEquals(-3.0, o.yaw() / o.pitch(), 1e-6);
    }

    @Test
    void noAllocation() {
        if (!AllocationProbe.supported()) {
            return;
        }
        FreelookOrbit o = new FreelookOrbit();
        long bytes = AllocationProbe.measure(50_000, () -> {
            o.addTurn(3, 1, 10);
            o.ease(0.016);
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
