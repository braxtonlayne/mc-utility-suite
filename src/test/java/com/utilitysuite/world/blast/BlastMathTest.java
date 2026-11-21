package com.utilitysuite.world.blast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BlastMathTest {

    @Test
    void radiiFollowPower() {
        assertEquals(6f, BlastMath.damageRadius(BlastMath.POWER_CREEPER));
        assertEquals(8f, BlastMath.damageRadius(BlastMath.POWER_TNT));
        assertEquals(12f, BlastMath.damageRadius(BlastMath.POWER_CHARGED_CREEPER));
    }

    @Test
    void creeperFuseCountsDown() {
        assertEquals(28, BlastMath.creeperTicksLeft(0f, 30));
        assertEquals(14, BlastMath.creeperTicksLeft(0.5f, 30));
        assertEquals(0, BlastMath.creeperTicksLeft(1f, 30));
        assertEquals(0, BlastMath.creeperTicksLeft(1.3f, 30)); // clamped
    }

    @Test
    void colourGoesGreenYellowRed() {
        int full = BlastMath.fuseColor(80, 80);
        int half = BlastMath.fuseColor(40, 80);
        int none = BlastMath.fuseColor(0, 80);
        assertEquals(0x00, (full >> 16) & 0xFF);
        assertEquals(0xFF, (full >> 8) & 0xFF);
        assertEquals(0xFF, (half >> 16) & 0xFF);
        assertEquals(0xFF, (half >> 8) & 0xFF);
        assertEquals(0xFF, (none >> 16) & 0xFF);
        assertEquals(0x00, (none >> 8) & 0xFF);
        assertEquals(0xE0, full >>> 24);
        // Monotonic: red never decreases as the fuse burns.
        int lastRed = -1;
        for (int t = 80; t >= 0; t--) {
            int red = (BlastMath.fuseColor(t, 80) >> 16) & 0xFF;
            assertEquals(true, red >= lastRed);
            lastRed = red;
        }
    }

    @Test
    void fuseLabelsAreCached() {
        assertEquals("1.5s", BlastMath.fuseLabel(30));
        assertEquals("4.0s", BlastMath.fuseLabel(80));
        assertEquals("0.0s", BlastMath.fuseLabel(0));
        assertSame(BlastMath.fuseLabel(30), BlastMath.fuseLabel(30));
    }
}
