package com.utilitysuite.tools.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RodSwapAndReconnectSafetyTest {

    private static final int N = RodSwap.NO_ROD;

    @Test
    void picksTheRodWithTheMostUsesLeftAboveTheMinimum() {
        int[] uses = {N, 3, N, 40, N, 12, N, N, N};
        assertEquals(3, RodSwap.pick(uses, 1, 3));
    }

    @Test
    void ignoresTheCurrentRodAndRodsAtOrBelowTheMinimum() {
        int[] uses = {N, 3, N, 2, N, 3, N, N, N};
        assertEquals(-1, RodSwap.pick(uses, 1, 3), "slot 3 and 5 are not above the minimum");
        int[] only = {N, 50, N, N, N, N, N, N, N};
        assertEquals(-1, RodSwap.pick(only, 1, 3), "the rod in hand is never its own replacement");
    }

    @Test
    void unbreakableRodQualifies() {
        int[] uses = {Integer.MAX_VALUE, 2, N, N, N, N, N, N, N};
        assertEquals(0, RodSwap.pick(uses, 1, 3));
    }

    @Test
    void safeWhenNothingIsWrong() {
        assertNull(ReconnectSafety.unsafeReason(false, false, 1000, false));
        assertNull(ReconnectSafety.unsafeReason(false, false, ReconnectSafety.RECENT_HURT_TICKS, false));
    }

    @Test
    void eachConditionHoldsTheJob() {
        assertEquals("in lava", ReconnectSafety.unsafeReason(true, false, 1000, false));
        assertEquals("falling", ReconnectSafety.unsafeReason(false, true, 1000, false));
        assertEquals("under attack", ReconnectSafety.unsafeReason(false, false, 20, false));
        assertEquals("in danger", ReconnectSafety.unsafeReason(false, false, 1000, true));
    }
}
