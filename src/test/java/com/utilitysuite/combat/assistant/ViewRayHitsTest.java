package com.utilitysuite.combat.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The predicted pick: a view ray against the target's box, the way vanilla's next-tick pick will see it. */
class ViewRayHitsTest {

    // a player box 2.7 blocks ahead along +z (yaw 0 looks toward +z), feet at y 64, our eye at 65.62
    private static boolean hits(double zFront, float yaw, float pitch, double range) {
        return AimSolver.viewRayHits(0.0, 65.62, 0.0, yaw, pitch, -0.3, 64.0, zFront, 0.3, 65.8, zFront + 0.6,
                range);
    }

    @Test
    void straightAheadInsideReachHits() {
        assertTrue(hits(2.7, 0f, 0f, 3.0));
    }

    @Test
    void beyondReachMisses() {
        assertFalse(hits(3.2, 0f, 0f, 3.0));
    }

    @Test
    void lookingAwayMisses() {
        assertFalse(hits(2.0, 30f, 0f, 3.0)); // 30 degrees off at 2 blocks clears a 0.3 half-width
        assertFalse(hits(2.0, 180f, 0f, 3.0));
    }

    @Test
    void steepPitchOverTheHeadMisses() {
        assertFalse(hits(2.0, 0f, -45f, 3.0));
    }

    @Test
    void eyeInsideTheBoxHits() {
        assertTrue(AimSolver.viewRayHits(0.0, 65.0, 0.0, 90f, 10f, -0.3, 64.0, -0.3, 0.3, 65.8, 0.3, 3.0));
    }
}
