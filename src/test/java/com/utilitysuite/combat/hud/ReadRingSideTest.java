package com.utilitysuite.combat.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Predicted strafe direction to the screen side of the reticle chevron. */
class ReadRingSideTest {

    @Test
    void facingSouthEastIsOnTheLeft() {
        // yaw 0 faces +Z; the viewer's right is -X
        assertEquals(-1, ReadRingGeometry.screenSide(1f, 0f, 0f));
        assertEquals(1, ReadRingGeometry.screenSide(-1f, 0f, 0f));
    }

    @Test
    void facingWestRightIsNorth() {
        // yaw 90 faces -X; the viewer's right is -Z
        assertEquals(1, ReadRingGeometry.screenSide(0f, -0.8f, 90f));
        assertEquals(-1, ReadRingGeometry.screenSide(0f, 0.8f, 90f));
    }

    @Test
    void weakOrAlongTheViewHasNoSide() {
        assertEquals(0, ReadRingGeometry.screenSide(0.1f, 0f, 0f)); // below the confidence floor
        assertEquals(0, ReadRingGeometry.screenSide(0f, 1f, 0f)); // straight away from the viewer
    }
}
