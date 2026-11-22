package com.utilitysuite.ui.holo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.TextRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Role-to-face resolution of the suite font pipeline, without the game. */
class SuiteFontsTest {

    @BeforeEach
    @AfterEach
    void reset() {
        SuiteFonts.resetForTests();
        HoloTheme.suiteFont = true;
    }

    @Test
    void filesAbsentMeansVanillaForEveryRole() {
        for (TextRole r : TextRole.values()) {
            assertEquals(SuiteFonts.VANILLA, SuiteFonts.resolve(r, false, true, false), r.name());
            assertEquals(SuiteFonts.VANILLA, SuiteFonts.resolve(r), "start-up state is vanilla: " + r);
        }
        assertFalse(SuiteFonts.active());
    }

    @Test
    void filesPresentMapsEachRoleToItsFace() {
        assertEquals(SuiteFonts.UI, SuiteFonts.resolve(TextRole.UI, true, true, false));
        assertEquals(SuiteFonts.MICRO, SuiteFonts.resolve(TextRole.MICRO, true, true, false));
        assertEquals(SuiteFonts.MONO, SuiteFonts.resolve(TextRole.MONO, true, true, false));

        SuiteFonts.setState(true, true, false);
        assertTrue(SuiteFonts.active());
        assertEquals(SuiteFonts.MONO, SuiteFonts.resolve(TextRole.MONO));
    }

    @Test
    void vanillaSettingWins() {
        assertEquals(SuiteFonts.VANILLA, SuiteFonts.resolve(TextRole.UI, true, false, false));
        SuiteFonts.setState(true, true, false);
        HoloTheme.suiteFont = false;
        assertEquals(SuiteFonts.VANILLA, SuiteFonts.resolve(TextRole.MONO));
        assertFalse(SuiteFonts.active());
    }

    @Test
    void forceUnicodeFallsBackToVanilla() {
        assertEquals(SuiteFonts.VANILLA, SuiteFonts.resolve(TextRole.UI, true, true, true));
        SuiteFonts.setState(true, true, true);
        assertEquals(SuiteFonts.VANILLA, SuiteFonts.resolve(TextRole.MICRO));
        SuiteFonts.setState(true, true, false);
        assertEquals(SuiteFonts.MICRO, SuiteFonts.resolve(TextRole.MICRO));
    }

    @Test
    void failedLoadFallsBackToVanilla() {
        SuiteFonts.setState(true, false, false);
        assertEquals(SuiteFonts.VANILLA, SuiteFonts.resolve(TextRole.UI));
    }

    @Test
    void stateChangesBumpTheGeneration() {
        int g0 = SuiteFonts.generation();
        SuiteFonts.setState(true, true, false);
        int g1 = SuiteFonts.generation();
        assertTrue(g1 != g0);
        SuiteFonts.setState(true, true, false);
        assertEquals(g1, SuiteFonts.generation(), "no change, no bump");
    }

    @Test
    void definitionsNameTheFilesAndFallBackToVanilla() {
        for (int f = 0; f < SuiteFonts.FONT_IDS.length; f++) {
            JsonObject o = JsonParser.parseString(SuiteFonts.definitionJson(f)).getAsJsonObject();
            JsonArray providers = o.getAsJsonArray("providers");
            assertEquals(2, providers.size());
            JsonObject ttf = providers.get(0).getAsJsonObject();
            assertEquals("ttf", ttf.get("type").getAsString());
            assertEquals("utilitysuite:" + SuiteFonts.FILES[f], ttf.get("file").getAsString());
            assertEquals(2, ttf.getAsJsonArray("shift").size());
            assertFalse(ttf.getAsJsonObject("filter").get("uniform").getAsBoolean(),
                    "the face is off under Force Unicode Font");
            JsonObject ref = providers.get(1).getAsJsonObject();
            assertEquals("reference", ref.get("type").getAsString());
            assertEquals("minecraft:default", ref.get("id").getAsString());
        }
    }

    @Test
    void variantOversampleMatchesThePixelScale() {
        // one atlas texel per screen pixel: nearest-sampled minification dropped thin horizontal strokes
        assertEquals(2, SuiteFonts.variantFor(4.0 * 0.5), "menu at GUI scale 4 draws at 2 px per GUI px");
        assertEquals(2, SuiteFonts.variantFor(3.0 * (2.0 / 3.0)));
        assertEquals(1, SuiteFonts.variantFor(0.3));
        assertEquals(3, SuiteFonts.variantFor(3.0));
        assertEquals(SuiteFonts.MAX_VARIANT, SuiteFonts.variantFor(40.0));
        for (int f = 0; f < SuiteFonts.FONT_IDS.length; f++) {
            for (int k = 1; k <= SuiteFonts.MAX_VARIANT; k++) {
                JsonObject ttf = JsonParser.parseString(SuiteFonts.definitionJson(f, k)).getAsJsonObject()
                        .getAsJsonArray("providers").get(0).getAsJsonObject();
                assertEquals(k, ttf.get("oversample").getAsFloat(), 0.0f);
            }
        }
    }

    @Test
    void resolveAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        SuiteFonts.setState(true, true, false);
        TextRole[] roles = TextRole.values();
        int[] sink = new int[1];
        long a = AllocationProbe.measure(50_000, () -> {
            for (TextRole r : roles) {
                sink[0] += SuiteFonts.resolve(r);
                sink[0] += SuiteFonts.resolve(r, true, false, false);
            }
        });
        assertTrue(a < 4096, "resolve allocated " + a);
    }
}
