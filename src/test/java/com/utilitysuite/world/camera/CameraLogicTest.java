package com.utilitysuite.world.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraLogicTest {

    @Test
    void zoomEasesInScrollsAndEasesOut() {
        ZoomState z = new ZoomState();
        z.configure(4.0, 50.0, 2.0, 0.1);
        assertFalse(z.affectsView());
        z.setActive(true);
        z.update(0.016);
        assertTrue(z.factor() > 1.0 && z.factor() < 4.0, "eases, does not jump");
        for (int i = 0; i < 200; i++) {
            z.update(0.016);
        }
        assertEquals(4.0, z.factor(), 1e-9);
        assertEquals(70.0 / 4.0, z.apply(70.0), 1e-9);
        z.scroll(1);
        assertEquals(8.0, z.target(), 1e-9);
        z.scroll(-1);
        z.scroll(-1);
        z.scroll(-1);
        z.scroll(-1);
        assertEquals(1.0, z.target(), 1e-9, "never below 1x");
        z.setActive(false);
        z.scroll(1);
        assertEquals(1.0, z.target(), 1e-9, "scroll is ignored when not zooming");
        for (int i = 0; i < 200; i++) {
            z.update(0.016);
        }
        assertFalse(z.affectsView());
    }

    @Test
    void zoomEasingIsFrameRateIndependent() {
        ZoomState fast = new ZoomState();
        ZoomState slow = new ZoomState();
        fast.configure(4, 50, 1.25, 0.1);
        slow.configure(4, 50, 1.25, 0.1);
        fast.setActive(true);
        slow.setActive(true);
        for (int i = 0; i < 12; i++) {
            fast.update(1.0 / 120);
        }
        for (int i = 0; i < 3; i++) {
            slow.update(1.0 / 30);
        }
        assertEquals(slow.factor(), fast.factor(), 1e-9);
    }

    @Test
    void freecamMovesRelativeToYaw() {
        FreecamMotion m = new FreecamMotion();
        m.configure(1.0, 2.5, 0.0);
        m.reset(0, 64, 0);
        m.tick(1f, 0f, false, false, false, 0f); // yaw 0 faces +Z
        assertEquals(0, m.x(), 1e-9);
        assertEquals(1, m.z(), 1e-9);
        assertEquals(0, m.prevZ(), 1e-9);
        m.tick(1f, 0f, false, false, false, 90f); // yaw 90 faces -X
        assertEquals(-1, m.x(), 1e-9);
        m.tick(0f, 1f, false, false, false, 0f); // strafe left at yaw 0 is +X
        assertEquals(0, m.x(), 1e-9);
        m.tick(0f, 0f, true, false, true, 0f); // up, boosted
        assertEquals(64 + 2.5, m.y(), 1e-9);
        double y = m.y();
        m.tick(0f, 0f, false, false, false, 0f); // no input, damping 0: stops
        assertEquals(y, m.y(), 1e-9);
    }

    @Test
    void freecamDiagonalIsNotFaster() {
        FreecamMotion m = new FreecamMotion();
        m.configure(1.0, 2.5, 0.0);
        m.reset(0, 0, 0);
        m.tick(1f, 1f, false, false, false, 37f);
        assertEquals(1.0, Math.hypot(m.x(), m.z()), 1e-9);
    }
}
