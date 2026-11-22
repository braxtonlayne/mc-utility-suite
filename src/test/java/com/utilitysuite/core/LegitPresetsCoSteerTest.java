package com.utilitysuite.core;

import com.google.gson.JsonObject;
import com.utilitysuite.core.LegitPresets.Level;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Co-steer knobs (owner decision D1): 0 on Legit, bounded on Balanced, the hard cap on Relaxed; older profiles. */
class LegitPresetsCoSteerTest {

    private static final String MA = "movement_assist";
    private static final String[] KEYS = {LegitPresets.CO_STEER_RATE, LegitPresets.CO_STEER_MAX,
            LegitPresets.CO_STEER_CARRY_RATE, LegitPresets.CO_STEER_CARRY_MAX};

    private static ProfileConfig fresh(String id) {
        return new ProfileConfig(id, new JsonObject());
    }

    /** A profile on {@code level} written before the co-steer knobs (and optionally every other movement knob). */
    private static ProfileConfig[] olderProfileOn(Level level, boolean dropAllMovementKnobs) {
        ProfileConfig world = fresh("w");
        ProfileConfig global = fresh("global");
        LegitPresets.apply(level, world, global);
        JsonObject ma = world.root().getAsJsonObject(MA);
        if (dropAllMovementKnobs) {
            world.root().remove(MA);
        } else {
            for (String k : KEYS) {
                ma.remove(k);
            }
        }
        return new ProfileConfig[] {world, global};
    }

    @Test
    void appliedLevelsWriteTheValues() {
        for (Level level : Level.values()) {
            ProfileConfig world = fresh("w");
            ProfileConfig global = fresh("global");
            LegitPresets.apply(level, world, global);
            double rate = LegitPresets.numKnob(world, global, MA, LegitPresets.CO_STEER_RATE);
            double carry = LegitPresets.numKnob(world, global, MA, LegitPresets.CO_STEER_CARRY_RATE);
            double max = LegitPresets.numKnob(world, global, MA, LegitPresets.CO_STEER_MAX);
            switch (level) {
                case LEGIT -> {
                    assertEquals(0.0, rate);
                    assertEquals(0.0, carry);
                    assertEquals(0.0, max);
                }
                case BALANCED -> {
                    assertEquals(150.0, rate);
                    assertEquals(240.0, carry);
                    assertEquals(25.0, max);
                }
                case RELAXED -> {
                    assertEquals(450.0, rate);
                    assertEquals(450.0, carry);
                    assertEquals(180.0, max);
                }
            }
            assertTrue(rate <= 450.0 && carry <= 450.0, "never above the core's cap");
            assertEquals(level, LegitPresets.current(world, global));
            assertEquals(level, LegitPresets.storedLevel(world, global));
        }
    }

    @Test
    void anOlderLegitProfileNeverCoSteers() {
        for (boolean dropAll : new boolean[] {false, true}) {
            ProfileConfig[] p = olderProfileOn(Level.LEGIT, dropAll);
            assertEquals(Level.LEGIT, LegitPresets.storedLevel(p[0], p[1]));
            for (String k : KEYS) {
                assertEquals(0.0, LegitPresets.numKnob(p[0], p[1], MA, k), k);
            }
        }
    }

    @Test
    void anOlderBalancedOrRelaxedProfileReadsItsLevel() {
        ProfileConfig[] b = olderProfileOn(Level.BALANCED, false);
        assertEquals(150.0, LegitPresets.numKnob(b[0], b[1], MA, LegitPresets.CO_STEER_RATE));
        ProfileConfig[] r = olderProfileOn(Level.RELAXED, true);
        assertEquals(450.0, LegitPresets.numKnob(r[0], r[1], MA, LegitPresets.CO_STEER_RATE));
    }

    @Test
    void anEmptyProfileReadsBalanced() {
        assertEquals(Level.BALANCED, LegitPresets.storedLevel(fresh("w"), fresh("g")));
        assertEquals(25.0, LegitPresets.numKnob(fresh("w"), null, MA, LegitPresets.CO_STEER_MAX));
    }

    @Test
    void aStoredValueWins() {
        ProfileConfig world = fresh("w");
        world.set(MA, LegitPresets.CO_STEER_MAX, 40.0);
        assertEquals(40.0, LegitPresets.numKnob(world, fresh("g"), MA, LegitPresets.CO_STEER_MAX));
    }
}
