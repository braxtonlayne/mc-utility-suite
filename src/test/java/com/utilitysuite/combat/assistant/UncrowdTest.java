package com.utilitysuite.combat.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The don't-crowd guard: no stepping into an opponent we can no longer keep the crosshair on. */
class UncrowdTest {

    @Test
    void forwardStepsOutsideTheCrowdDistanceAreKept() {
        assertEquals(SpacingController.FORWARD, SpacingController.uncrowd(SpacingController.FORWARD, 2.0, false));
        assertEquals(SpacingController.HOLD, SpacingController.uncrowd(SpacingController.HOLD, 2.0, false));
        assertEquals(SpacingController.BACK, SpacingController.uncrowd(SpacingController.BACK, 2.0, false));
    }

    @Test
    void noForwardStepInsideTheCrowdDistance() {
        assertEquals(SpacingController.HOLD, SpacingController.uncrowd(SpacingController.FORWARD, 1.4, false));
        // holding or backing off is left alone
        assertEquals(SpacingController.HOLD, SpacingController.uncrowd(SpacingController.HOLD, 1.4, false));
        assertEquals(SpacingController.BACK, SpacingController.uncrowd(SpacingController.BACK, 1.4, false));
    }

    @Test
    void overlappingStepsBackUnlessAHazardIsBehind() {
        assertEquals(SpacingController.BACK, SpacingController.uncrowd(SpacingController.FORWARD, 0.5, false));
        assertEquals(SpacingController.BACK, SpacingController.uncrowd(SpacingController.HOLD, 0.5, false));
        assertEquals(SpacingController.HOLD, SpacingController.uncrowd(SpacingController.FORWARD, 0.5, true));
    }
}
