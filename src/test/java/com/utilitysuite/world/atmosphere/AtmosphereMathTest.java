package com.utilitysuite.world.atmosphere;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtmosphereMathTest {

    @Test
    void lerpEndsAndMiddle() {
        assertEquals(0x000000, AtmosphereMath.lerpRgb(0x000000, 0xFFFFFF, 0f));
        assertEquals(0xFFFFFF, AtmosphereMath.lerpRgb(0x000000, 0xFFFFFF, 1f));
        assertEquals(0x808080, AtmosphereMath.lerpRgb(0x000000, 0xFFFFFF, 0.5f));
        assertEquals(0xFFFFFF, AtmosphereMath.lerpRgb(0x000000, 0xFFFFFF, 7f), "t is clamped");
        assertEquals(0x102030, AtmosphereMath.lerpRgb(0x102030, 0xFFFFFF, -1f));
    }

    @Test
    void parseHexForms() {
        assertEquals(0xF0A070, AtmosphereMath.parseHex("#F0A070", 0));
        assertEquals(0xF0A070, AtmosphereMath.parseHex("f0a070", 0));
        assertEquals(0xF0A070, AtmosphereMath.parseHex(" 0xF0A070 ", 0));
        assertEquals(0x12345, AtmosphereMath.parseHex("12345", 0));
        assertEquals(7, AtmosphereMath.parseHex("nope", 7));
        assertEquals(7, AtmosphereMath.parseHex("", 7));
        assertEquals(7, AtmosphereMath.parseHex("#1234567", 7));
        assertEquals(7, AtmosphereMath.parseHex(null, 7));
    }

    @Test
    void rampHitsKeysAndInterpolatesWithWrap() {
        AtmosphereMath.ColorRamp r = AtmosphereMath.ColorRamp.fourKey(0xFF0000, 0x00FF00, 0x0000FF, 0x000000);
        assertEquals(0xFF0000, r.sample(0));
        assertEquals(0x00FF00, r.sample(6000));
        assertEquals(0x0000FF, r.sample(12000));
        assertEquals(0x000000, r.sample(18000));
        assertEquals(AtmosphereMath.lerpRgb(0xFF0000, 0x00FF00, 0.5f), r.sample(3000));
        // night -> dawn across midnight wrap
        assertEquals(AtmosphereMath.lerpRgb(0x000000, 0xFF0000, 0.5f), r.sample(21000));
        assertEquals(r.sample(3000), r.sample(24000L * 5 + 3000), "day count ignored");
        assertEquals(r.sample(21000), r.sample(-3000));
    }

    @Test
    void rampRejectsBadKeys() {
        assertThrows(IllegalArgumentException.class, () -> new AtmosphereMath.ColorRamp(new int[0], new int[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new AtmosphereMath.ColorRamp(new int[] {5, 5}, new int[] {1, 2}));
        assertEquals(0xABCDEF, new AtmosphereMath.ColorRamp(new int[] {100}, new int[] {0xABCDEF}).sample(9000));
    }

    @Test
    void blendChannelMovesTowardTarget() {
        assertEquals(0.5f, AtmosphereMath.blendChannel(0.5f, 255f, 0f), 1e-6);
        assertEquals(1f, AtmosphereMath.blendChannel(0.5f, 255f, 1f), 1e-6);
        assertEquals(0.75f, AtmosphereMath.blendChannel(0.5f, 255f, 0.5f), 1e-6);
    }

    @Test
    void lightLiftIsPartialNeverFull() {
        assertEquals(0f, AtmosphereMath.lightLift(0f));
        assertTrue(AtmosphereMath.lightLift(1f) < 1f);
        assertTrue(AtmosphereMath.lightLift(1f) > AtmosphereMath.lightLift(0.3f));
        assertEquals(AtmosphereMath.lightLift(1f), AtmosphereMath.lightLift(5f));
    }

    @Test
    void biomeTable() {
        assertEquals(AtmosphereMath.NO_TINT, AtmosphereMath.biomeTint("plains"));
        assertEquals(AtmosphereMath.NO_TINT, AtmosphereMath.biomeTint(null));
        assertNotEquals(AtmosphereMath.NO_TINT, AtmosphereMath.biomeTint("desert"));
        assertNotEquals(AtmosphereMath.biomeTint("desert"), AtmosphereMath.biomeTint("snowy_taiga"));
        assertEquals(AtmosphereMath.biomeTint("crimson_forest"), 0xA03030);
        assertEquals(AtmosphereMath.biomeTint("deep_dark"), 0x203048, "deep dark beats the 'dark' forest rule");
        assertEquals(AtmosphereMath.biomeTint("end_highlands"), 0x806090);
    }

    @Test
    void blendedTintEasesInAndOut() {
        AtmosphereMath.BlendedTint t = new AtmosphereMath.BlendedTint();
        t.step(0xFF0000, 0.5f);
        assertEquals(255f, t.r, 1e-3, "first tint snaps the colour, eases the weight");
        assertEquals(0.5f, t.weight, 1e-6);
        for (int i = 0; i < 40; i++) {
            t.step(0x0000FF, 0.2f);
        }
        assertTrue(t.b > 250f && t.r < 5f);
        assertTrue(t.weight > 0.99f);
        for (int i = 0; i < 60; i++) {
            t.step(AtmosphereMath.NO_TINT, 0.2f);
        }
        assertTrue(t.weight < 0.01f);
    }
}
