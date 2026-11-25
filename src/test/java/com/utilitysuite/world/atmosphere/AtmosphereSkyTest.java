package com.utilitysuite.world.atmosphere;

import com.utilitysuite.world.WorldHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R6 sky recolour: daylight curve, cloud colour, weather damping and the per-channel blend the mixin applies. */
class AtmosphereSkyTest {

    @AfterEach
    void reset() {
        WorldHooks.clearSkyTint();
    }

    @Test
    void daylightMatchesVanillasSkyCurve() {
        assertEquals(1f, AtmosphereMath.daylight(0f), 1e-6, "noon");
        assertEquals(0f, AtmosphereMath.daylight(0.5f), 1e-6, "midnight");
        assertEquals(0.5f, AtmosphereMath.daylight(0.25f), 1e-5, "sunset: cos = 0");
        for (float t = 0f; t <= 1f; t += 0.01f) {
            float vanilla = Math.max(0f, Math.min(1f, (float) Math.cos(t * Math.PI * 2) * 2f + 0.5f));
            assertEquals(vanilla, AtmosphereMath.daylight(t), 1e-5);
        }
    }

    @Test
    void cloudColourIsAWashedDaylitSky() {
        assertEquals(0xFFFFFF, AtmosphereMath.cloudFor(0xFFFFFF, 1f));
        int noonBlue = AtmosphereMath.cloudFor(0x3060C0, 1f);
        assertTrue(AtmosphereMath.b(noonBlue) > AtmosphereMath.r(noonBlue), "keeps the hue");
        assertTrue(AtmosphereMath.r(noonBlue) > 0x30, "washed toward white");
        int night = AtmosphereMath.cloudFor(0xFFFFFF, 0f);
        assertEquals(26, AtmosphereMath.r(night), 1, "vanilla's 0.1 floor at night");
        assertEquals(0x804020, AtmosphereMath.scaleRgb(0xFF8040, 0.5f) & 0xFEFEFE);
        assertEquals(0xFFFFFF, AtmosphereMath.scaleRgb(0xFFFFFF, 2f), "clamped");
    }

    @Test
    void weatherDampsTheTint() {
        assertEquals(0.8f, AtmosphereMath.weatherDamp(0.8f, 0f, 0f), 1e-6);
        assertEquals(0.8f * 0.4f, AtmosphereMath.weatherDamp(0.8f, 1f, 0f), 1e-6);
        assertEquals(0.8f * 0.4f * 0.5f, AtmosphereMath.weatherDamp(0.8f, 1f, 1f), 1e-6);
        assertEquals(1f, AtmosphereMath.weatherDamp(3f, 0f, 0f), 1e-6, "clamped");
    }

    @Test
    void skyChannelsBlendTowardTheTargetAndZeroWeightIsVanilla() {
        assertEquals(0.3, WorldHooks.skyChannel(0, 0.3), 1e-9, "no tint published");
        WorldHooks.setSkyTint(0xFF0080, 0.5f);
        assertEquals(0.5f, WorldHooks.skyTintWeight(), 1e-6);
        assertEquals(0.3 + (1.0 - 0.3) * 0.5, WorldHooks.skyChannel(0, 0.3), 1e-6);
        assertEquals(0.6 * 0.5, WorldHooks.skyChannel(1, 0.6), 1e-6);
        assertEquals(0.2 + (128 / 255.0 - 0.2) * 0.5, WorldHooks.skyChannel(2, 0.2), 1e-6);
        assertEquals(0.9, WorldHooks.cloudChannel(0, 0.9), 1e-9, "clouds have their own weight");
        WorldHooks.setCloudTint(0x000000, 1f);
        assertEquals(0.0, WorldHooks.cloudChannel(1, 0.9), 1e-9);
        WorldHooks.setSkyTint(0x000000, 7f);
        assertEquals(1f, WorldHooks.skyTintWeight(), 1e-6, "weight clamped");
        WorldHooks.clearSkyTint();
        assertEquals(0.42, WorldHooks.skyChannel(2, 0.42), 1e-9);
        assertEquals(0.42, WorldHooks.cloudChannel(2, 0.42), 1e-9);
    }

    @Test
    void skyRampGivesTheNightColourAtMidnightAndBlendsAtDusk() {
        AtmosphereMath.ColorRamp ramp = AtmosphereMath.ColorRamp.fourKey(0xF0A070, 0xA8CCF8, 0xE07850, 0x141C38);
        assertEquals(0x141C38, ramp.sample(18000));
        assertEquals(0xA8CCF8, ramp.sample(6000 + 24000 * 3));
        int halfway = ramp.sample(9000);
        assertEquals(AtmosphereMath.lerpRgb(0xA8CCF8, 0xE07850, 0.5f), halfway);
    }
}
