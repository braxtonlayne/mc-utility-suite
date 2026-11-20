package com.utilitysuite.travel.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Surface mode switches itself off when the server keeps correcting the player, and reports that exactly once. */
class SurfaceGuardTest {

    @Test
    void repeatedCorrectionsDisableTheModeOnce() {
        SurfaceGuard g = new SurfaceGuard();
        g.configure(3, 100, 8.0);
        g.activate();
        assertFalse(g.onCorrection(10, 0.4));
        assertFalse(g.onCorrection(20, 0.4));
        assertTrue(g.isActive());
        assertTrue(g.onCorrection(30, 0.4), "third correction switches it off and asks for a warning");
        assertFalse(g.isActive());
        assertFalse(g.onCorrection(31, 0.4), "no second warning while off");
    }

    @Test
    void correctionsWhileOffDoNotCount() {
        SurfaceGuard g = new SurfaceGuard();
        g.configure(2, 100, 8.0);
        assertFalse(g.onCorrection(1, 0.4));
        assertFalse(g.onCorrection(2, 0.4));
        g.activate();
        assertFalse(g.onCorrection(3, 0.4), "count starts fresh on activation");
        assertTrue(g.onCorrection(4, 0.4));
    }

    @Test
    void reactivationResetsTheCount() {
        SurfaceGuard g = new SurfaceGuard();
        g.configure(2, 100, 8.0);
        g.activate();
        g.onCorrection(1, 0.4);
        g.deactivate();
        g.activate();
        assertFalse(g.onCorrection(2, 0.4));
        assertTrue(g.isActive());
    }

    @Test
    void teleportsDoNotDisable() {
        SurfaceGuard g = new SurfaceGuard();
        g.configure(1, 100, 8.0);
        g.activate();
        assertFalse(g.onCorrection(1, 120.0), "a /tp or respawn is not a rejection");
        assertTrue(g.isActive());
        assertTrue(g.onCorrection(2, 0.5));
    }
}
