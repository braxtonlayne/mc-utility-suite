package com.utilitysuite.world.overlays;

import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.schematic.SchematicSettings;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import com.utilitysuite.travel.terrain.TerrainAssist;
import com.utilitysuite.world.camera.ScoutCameraModule;
import com.utilitysuite.world.oresim.OreSimModule;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Every preset references real module ids and setting keys, with values that fit the setting's type. */
class VisualPresetsTest {

    private static Map<String, List<SettingSpec>> specsByModule() {
        return Map.of(
                WorldOverlaysModule.ID, WorldOverlaysModule.specs(),
                ScoutCameraModule.ID, ScoutCameraModule.specs(),
                TerrainAssist.ID, new TerrainAssist().settings(),
                SchematicToolkit.ID, SchematicSettings.specs());
    }

    @Test
    void everyReferencedModuleAndKeyExists() {
        Map<String, List<SettingSpec>> specs = specsByModule();
        Set<String> knownIds = new HashSet<>(specs.keySet());
        knownIds.add(OreSimModule.ID);
        knownIds.add(WorldRadarModule.ID);
        assertEquals(4, VisualPresets.ALL.size());
        for (VisualPresets.Preset p : VisualPresets.ALL) {
            assertTrue(!p.toggles().isEmpty() || !p.settings().isEmpty(), p.name());
            for (VisualPresets.Toggle t : p.toggles()) {
                assertTrue(knownIds.contains(t.moduleId()), p.name() + ": unknown module " + t.moduleId());
            }
            for (VisualPresets.Setting s : p.settings()) {
                List<SettingSpec> list = specs.get(s.moduleId());
                assertNotNull(list, p.name() + ": unknown module " + s.moduleId());
                SettingSpec spec = list.stream().filter(x -> x.key().equals(s.key())).findFirst()
                        .orElseThrow(() -> new AssertionError(p.name() + ": " + s.moduleId() + " has no " + s.key()));
                switch (spec.type()) {
                    case BOOL -> assertTrue(s.value() instanceof Boolean, s.key());
                    case INT, DOUBLE -> assertTrue(s.value() instanceof Number n
                            && n.doubleValue() >= spec.min() && n.doubleValue() <= spec.max(), s.key());
                    case ENUM -> assertTrue(spec.options().contains(String.valueOf(s.value())),
                            s.key() + "=" + s.value() + " not in " + spec.options());
                    case STRING -> assertTrue(s.value() instanceof String, s.key());
                    default -> fail("unhandled type " + spec.type());
                }
            }
        }
    }

    @Test
    void cleanTurnsOverlaysOff() {
        for (VisualPresets.Toggle t : VisualPresets.CLEAN.toggles()) {
            assertTrue(!t.enabled(), t.moduleId());
        }
        for (VisualPresets.Setting s : VisualPresets.CLEAN.settings()) {
            assertEquals(Boolean.FALSE, s.value(), s.key());
        }
    }

    @Test
    void focusDimsOtherLayersAndScalesAlpha() {
        assertEquals(1f, OverlayStyle.layerScale(OverlayStyle.Focus.ALL, OverlayStyle.Focus.CAVES, 1f));
        assertEquals(1f, OverlayStyle.layerScale(OverlayStyle.Focus.ORES, OverlayStyle.Focus.ORES, 1f));
        assertEquals(OverlayStyle.DIM, OverlayStyle.layerScale(OverlayStyle.Focus.ORES, OverlayStyle.Focus.FLUIDS, 1f));
        assertEquals(0x90FF6010, OverlayStyle.scaleAlpha(0x90FF6010, 1f));
        assertEquals(0x24FF6010, OverlayStyle.scaleAlpha(0x90FF6010, 0.25f));
        assertEquals(0x00123456, OverlayStyle.scaleAlpha(0xFF123456, -1f));
        assertEquals(0x80123456, OverlayStyle.scaleAlpha(0xFF123456, 0.5f));
    }
}
