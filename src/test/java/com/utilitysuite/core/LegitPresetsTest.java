package com.utilitysuite.core;

import com.google.gson.JsonObject;
import com.utilitysuite.core.LegitPresets.Level;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegitPresetsTest {

    private static ProfileConfig fresh(String id) {
        return new ProfileConfig(id, new JsonObject());
    }

    @Test
    void anEmptyProfileIsBalancedBecauseBalancedIsTheDefault() {
        assertEquals(Level.BALANCED, LegitPresets.current(fresh("w"), fresh("global")));
    }

    @Test
    void applyingALevelMakesItCurrentAndWritesBothProfiles() {
        ProfileConfig world = fresh("w");
        ProfileConfig global = fresh("global");
        for (Level level : Level.values()) {
            LegitPresets.apply(level, world, global);
            assertEquals(level, LegitPresets.current(world, global));
        }
        assertEquals(600.0, global.getDouble("core", "camera_speed_deg_per_sec", 0), 1e-9);
        assertEquals("INSTANT", world.getString(LegitPresets.BRIDGE, "walk_pace", ""));
        assertFalse(world.getBool(LegitPresets.SCHEMATIC, "requireLineOfSight", true));
    }

    @Test
    void legitRequiresLineOfSightAndKeepsTheBridgeDisguise() {
        ProfileConfig world = fresh("w");
        LegitPresets.apply(Level.LEGIT, world, fresh("global"));
        assertTrue(world.getBool(LegitPresets.SCHEMATIC, "requireLineOfSight", false));
        assertEquals("VISIBLE", world.getString(LegitPresets.BRIDGE, "rotation_mode", ""));
        assertEquals("BRIDGER", world.getString(LegitPresets.BRIDGE, "server_look", ""));
    }

    @Test
    void reapplyingTheSameLevelChangesNothing() {
        ProfileConfig world = fresh("w");
        ProfileConfig global = fresh("global");
        assertFalse(LegitPresets.apply(Level.RELAXED, world, global).isEmpty());
        assertTrue(LegitPresets.apply(Level.RELAXED, world, global).isEmpty());
    }

    @Test
    void oneHandTunedKnobMakesItCustom() {
        ProfileConfig world = fresh("w");
        ProfileConfig global = fresh("global");
        LegitPresets.apply(Level.LEGIT, world, global);
        world.set(LegitPresets.COMBAT, "aim_speed", 1.7);
        assertNull(LegitPresets.current(world, global));
    }

    @Test
    void enumValuesMatchCaseInsensitivelyAndIntsMatchDoubles() {
        assertTrue(LegitPresets.matches("instant", "INSTANT"));
        assertTrue(LegitPresets.matches(4.0, 4));
        assertFalse(LegitPresets.matches(true, "true"));
    }
}
