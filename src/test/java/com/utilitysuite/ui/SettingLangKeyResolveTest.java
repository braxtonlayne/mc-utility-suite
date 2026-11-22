package com.utilitysuite.ui;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Setting labels and tooltips resolve through lang keys and fall back to the spec text (no game needed). */
class SettingLangKeyResolveTest {

    private static final Map<String, String> TABLE = Map.of(
            "setting.utilitysuite.autopilot.black_box_always", "Record while off",
            "setting.utilitysuite.autopilot.black_box_always.tooltip", "Keeps recording.",
            "setting.utilitysuite.autopilot.blank", "  ");

    private static final Function<String, String> LOOKUP = TABLE::get;

    @Test
    void keyBuilding() {
        assertEquals("setting.utilitysuite.autopilot.black_box_always",
                SettingsRenderer.langKey("autopilot", "black_box_always"));
        assertEquals("setting.utilitysuite.autopilot.black_box_always.tooltip",
                SettingsRenderer.langKey("autopilot", "black_box_always" + SettingsRenderer.TOOLTIP_SUFFIX));
    }

    @Test
    void presentKeyWins() {
        assertEquals("Record while off",
                SettingsRenderer.resolve("autopilot", "black_box_always", "Old label", LOOKUP));
    }

    @Test
    void absentKeyFallsBackToTheSpecLabel() {
        assertEquals("Old label", SettingsRenderer.resolve("autopilot", "missing", "Old label", LOOKUP));
        assertEquals("Old label", SettingsRenderer.resolve("other_module", "black_box_always", "Old label", LOOKUP));
    }

    @Test
    void blankEntryCountsAsAbsent() {
        assertEquals("Old label", SettingsRenderer.resolve("autopilot", "blank", "Old label", LOOKUP));
    }

    @Test
    void absentTooltipStaysEmpty() {
        assertEquals("", SettingsRenderer.resolve("autopilot", "missing" + SettingsRenderer.TOOLTIP_SUFFIX, "", LOOKUP));
        assertEquals("Keeps recording.", SettingsRenderer.resolve("autopilot",
                "black_box_always" + SettingsRenderer.TOOLTIP_SUFFIX, "", LOOKUP));
    }
}
