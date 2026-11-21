package com.utilitysuite.core;

import com.google.gson.JsonObject;
import com.utilitysuite.core.LegitPresets.Knob;
import com.utilitysuite.core.LegitPresets.Level;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the combat-reflexes and relaxed-flag knobs added to {@link LegitPresets#KNOBS}. */
class LegitPresetsRunKnobsTest {

    private static ProfileConfig fresh(String id) {
        return new ProfileConfig(id, new JsonObject());
    }

    private static Knob find(String module, String key) {
        for (Knob k : LegitPresets.KNOBS) {
            if (k.moduleId().equals(module) && k.key().equals(key)) {
                return k;
            }
        }
        throw new AssertionError("no knob " + module + "." + key);
    }

    @Test
    void shieldTurnIsOffForLegitAndBalancedAndOnForRelaxed() {
        Knob k = find("combat_reflexes", "shield_turn");
        assertEquals(false, k.value(Level.LEGIT));
        assertEquals(false, k.value(Level.BALANCED));
        assertEquals(true, k.value(Level.RELAXED));
    }

    @Test
    void shieldFrontConeIs70ForLegitAndBalancedAnd90ForRelaxed() {
        Knob k = find("combat_reflexes", "shield_front_cone");
        assertEquals(70.0, (Double) k.value(Level.LEGIT), 1e-9);
        assertEquals(70.0, (Double) k.value(Level.BALANCED), 1e-9);
        assertEquals(90.0, (Double) k.value(Level.RELAXED), 1e-9);
    }

    @Test
    void relaxedFlagIsOffForLegitAndBalancedAndOnForRelaxedForEachCraftModule() {
        for (String module : new String[] {"auto_torch", "lava_plug", "vein_work", "crop_work"}) {
            Knob k = find(module, "relaxed");
            assertEquals(false, k.value(Level.LEGIT), module);
            assertEquals(false, k.value(Level.BALANCED), module);
            assertEquals(true, k.value(Level.RELAXED), module);
        }
    }

    @Test
    void applyingRelaxedWritesTheNewCombatAndCraftKnobsToTheProfile() {
        ProfileConfig world = fresh("w");
        ProfileConfig global = fresh("global");
        LegitPresets.apply(Level.RELAXED, world, global);
        assertTrue(world.getBool("combat_reflexes", "shield_turn", false));
        assertEquals(90.0, world.getDouble("combat_reflexes", "shield_front_cone", 0), 1e-9);
        assertTrue(world.getBool("auto_torch", "relaxed", false));
        assertTrue(world.getBool("crop_work", "relaxed", false));
    }

    @Test
    void noTwoKnobsShareTheSameModuleAndKey() {
        Set<String> seen = new HashSet<>();
        for (Knob k : LegitPresets.KNOBS) {
            String id = k.moduleId() + "." + k.key();
            assertNotNull(id);
            assertTrue(seen.add(id), "duplicate knob " + id);
        }
        assertFalse(seen.isEmpty());
    }
}
