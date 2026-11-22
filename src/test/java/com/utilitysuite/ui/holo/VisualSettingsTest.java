package com.utilitysuite.ui.holo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VisualSettingsTest {

    @AfterEach
    void restoreDefaults() {
        VisualSettings.apply(new ProfileConfig("defaults", new JsonObject()));
    }

    @Test
    void emptyProfileGivesTheDefaults() {
        HoloTheme.effects = EffectLevel.OFF;
        HoloTheme.hudScale = 1.3f;
        VisualSettings.apply(new ProfileConfig("p", new JsonObject()));
        assertEquals(EffectLevel.FULL, HoloTheme.effects);
        assertEquals(Motion.FULL, HoloTheme.motion);
        assertEquals(1f, HoloTheme.hudOpacity);
        assertEquals(1f, HoloTheme.hudScale);
        assertTrue(HoloTheme.suiteFont, "suite font by default");
        assertFalse(HoloTheme.armedMarker, "armed marker is opt-in");
        assertEquals(0xFF000000 | HoloTheme.DEFAULT_ACCENT_RGB, HoloTheme.argb(HoloToken.ACCENT));
    }

    @Test
    void valuesRoundTripThroughTheProfile() {
        ProfileConfig p = new ProfileConfig("p", new JsonObject());
        p.set(VisualSettings.ID, VisualSettings.K_EFFECTS, "SUBTLE");
        p.set(VisualSettings.ID, VisualSettings.K_MOTION, "REDUCED");
        p.set(VisualSettings.ID, VisualSettings.K_ACCENT, "AMBER");
        p.set(VisualSettings.ID, VisualSettings.K_HUD_OPACITY, 55);
        p.set(VisualSettings.ID, VisualSettings.K_HUD_SCALE, 125);
        p.set(VisualSettings.ID, VisualSettings.K_FONT, "vanilla");
        p.set(VisualSettings.ID, VisualSettings.K_ARMED_MARKER, true);
        VisualSettings.apply(p);
        assertEquals(EffectLevel.SUBTLE, HoloTheme.effects);
        assertEquals(Motion.REDUCED, HoloTheme.motion);
        assertEquals(0xFFFFB547, HoloTheme.argb(HoloToken.ACCENT));
        assertEquals(0.55f, HoloTheme.hudOpacity, 1e-6f);
        assertFalse(HoloTheme.suiteFont);
        assertTrue(HoloTheme.armedMarker);
        assertEquals(1.25f, HoloTheme.hudScale, 1e-6f);
        assertEquals(0xFFFF4D5E, HoloTheme.argb(HoloToken.DANGER), "status hues do not move with the accent");

        ProfileConfig copy = new ProfileConfig("copy", new JsonObject());
        VisualSettings.store(copy, VisualSettings.Accent.AMBER);
        HoloTheme.effects = EffectLevel.OFF;
        HoloTheme.suiteFont = true;
        HoloTheme.armedMarker = false;
        HoloTheme.hudOpacity = 1f;
        VisualSettings.apply(copy);
        assertFalse(HoloTheme.suiteFont);
        assertTrue(HoloTheme.armedMarker);
        assertEquals(EffectLevel.SUBTLE, HoloTheme.effects);
        assertEquals(Motion.REDUCED, HoloTheme.motion);
        assertEquals(0.55f, HoloTheme.hudOpacity, 1e-6f);
        assertEquals(1.25f, HoloTheme.hudScale, 1e-6f);
    }

    @Test
    void outOfRangeAndUnknownValuesAreClampedOrDefaulted() {
        ProfileConfig p = new ProfileConfig("p", new JsonObject());
        p.set(VisualSettings.ID, VisualSettings.K_EFFECTS, "sparkly");
        p.set(VisualSettings.ID, VisualSettings.K_MOTION, "off");
        p.set(VisualSettings.ID, VisualSettings.K_ACCENT, "plaid");
        p.set(VisualSettings.ID, VisualSettings.K_HUD_OPACITY, 5);
        p.set(VisualSettings.ID, VisualSettings.K_HUD_SCALE, 400);
        p.set(VisualSettings.ID, VisualSettings.K_FONT, "comic");
        VisualSettings.apply(p);
        assertEquals(EffectLevel.FULL, HoloTheme.effects);
        assertEquals(Motion.OFF, HoloTheme.motion);
        assertEquals(0xFF000000 | HoloTheme.DEFAULT_ACCENT_RGB, HoloTheme.argb(HoloToken.ACCENT));
        assertEquals(0.4f, HoloTheme.hudOpacity, 1e-6f);
        assertEquals(1.5f, HoloTheme.hudScale, 1e-6f);
        assertTrue(HoloTheme.suiteFont, "unknown face falls back to suite");
    }

    @Test
    void settingsCardListsEveryKeyWithItsBounds() {
        VisualSettings v = new VisualSettings();
        assertEquals(7, v.settings().size());
        assertEquals(40.0, v.settings().get(3).min());
        assertEquals(150.0, v.settings().get(4).max());
        assertEquals(VisualSettings.K_FONT, v.settings().get(5).key());
        assertEquals(VisualSettings.K_ARMED_MARKER, v.settings().get(6).key());
    }
}
